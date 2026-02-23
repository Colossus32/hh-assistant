package com.hhassistant.vacancy.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.aspect.Loggable
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.OllamaException
import com.hhassistant.vacancy.repository.VacancyAnalysisRepository
import com.hhassistant.monitoring.service.CircuitBreakerStateService
import com.hhassistant.vacancy.port.ContentValidatorPort
import com.hhassistant.vacancy.port.ResumeProvider
import com.hhassistant.vacancy.port.SkillSaverPort
import com.hhassistant.vacancy.port.VacancyLlmAnalyzer
import com.hhassistant.vacancy.port.VacancyStatusUpdater
import com.hhassistant.vacancy.port.VacancyUrlChecker
import com.hhassistant.service.util.AnalysisTimeService
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Оркестратор анализа вакансий.
 * Координирует: проверку URL, валидацию контента, анализ через LLM, сохранение.
 * Выделены: VacancyUrlValidationService (URL), VacancyAnalyzerService (LLM), VacancyContentValidator (контент).
 * PROJECT_REVIEW issue 3 - SRP.
 */
/**
 * Оркестратор анализа вакансий.
 * Использует порты (ResumeProvider, VacancyLlmAnalyzer и др.) для изоляции внешних зависимостей (PROJECT_REVIEW 3.2).
 * Упрощает тестирование: вместо 13 моков — 6 портов с чёткими контрактами.
 */
@Service
class VacancyAnalysisService(
    private val resumeProvider: ResumeProvider,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
    private val contentValidator: ContentValidatorPort,
    private val statusUpdater: VacancyStatusUpdater,
    private val metricsService: com.hhassistant.monitoring.metrics.MetricsService,
    private val analysisTimeService: AnalysisTimeService,
    private val skillSaver: SkillSaverPort,
    private val circuitBreakerStateService: CircuitBreakerStateService,
    private val processedVacancyCacheService: ProcessedVacancyCacheService,
    private val vacancyProcessingControlService: VacancyProcessingControlService,
    private val vacancyUrlChecker: VacancyUrlChecker,
    private val llmAnalyzer: VacancyLlmAnalyzer,
    @Qualifier("ollamaCircuitBreaker") private val ollamaCircuitBreaker: CircuitBreaker,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Проверяет URL нескольких вакансий (делегирует VacancyUrlValidationService).
     */
    suspend fun checkVacancyUrlsBatch(
        vacancies: List<Vacancy>,
        batchSize: Int = 5,
    ): Map<String, Boolean> = vacancyUrlChecker.checkVacancyUrlsBatch(vacancies, batchSize)

    /**
     * Получает анализ вакансии по ID.
     */
    suspend fun findByVacancyId(vacancyId: String): VacancyAnalysis? {
        log.trace("🤖 [VacancyAnalysis] find $vacancyId")
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val result = repository.findByVacancyId(vacancyId)
            val duration = System.currentTimeMillis() - startTime
            log.trace("🤖 [VacancyAnalysis] $vacancyId: ${if (result != null) "found" else "not found"} ${duration}ms")
            result
        }
    }

    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.getCircuitBreakerState()"))
    fun getCircuitBreakerState(): String = circuitBreakerStateService.getCircuitBreakerState()

    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.resetCircuitBreaker()"))
    fun resetCircuitBreaker() = circuitBreakerStateService.resetCircuitBreaker()

    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.tryTransitionToHalfOpen()"))
    fun tryTransitionToHalfOpen() = circuitBreakerStateService.tryTransitionToHalfOpen()

    /**
     * Анализирует вакансию: URL check -> content validation -> LLM analysis -> save.
     */
    @Loggable
    suspend fun analyzeVacancy(vacancy: Vacancy): VacancyAnalysis? {
        if (processedVacancyCacheService.isProcessed(vacancy.id)) {
            log.debug("🤖 [Ollama] Cache hit ${vacancy.id}")
            val existingAnalysis = withContext(Dispatchers.IO) { repository.findByVacancyId(vacancy.id) }
            existingAnalysis?.let { return it }
            log.warn("🤖 [Ollama] ${vacancy.id}: processed in cache but no analysis, clearing")
            processedVacancyCacheService.removeFromCache(vacancy.id)
        }

        if (vacancyProcessingControlService.isProcessingPaused()) {
            log.debug("🤖 [Ollama] Paused, skip ${vacancy.id}")
            throw OllamaException.ConnectionException(
                "Vacancy processing is paused. Please resume using /resume command.",
            )
        }

        if (ollamaCircuitBreaker.state.name == "OPEN") {
            log.warn("🤖 [Ollama] Circuit breaker OPEN, skip ${vacancy.id}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
            )
        }

        log.debug("🤖 [Ollama] ${vacancy.id}: ${vacancy.name} @ ${vacancy.employer}")

        // Шаг 1: URL проверка (для HH.ru вакансий)
        if (!vacancy.isFromTelegram()) {
            try {
                val urlCheckResult = vacancyUrlChecker.checkVacancyUrl(vacancy.id)
                if (!urlCheckResult) {
                    try {
                        statusUpdater.updateVacancyStatus(vacancy.withStatus(VacancyStatus.IN_ARCHIVE))
                        log.info("🔗 [URL] ${vacancy.id} 404 -> IN_ARCHIVE")
                    } catch (e: Exception) {
                        log.error("🔗 [URL] ${vacancy.id} IN_ARCHIVE update failed: ${e.message}", e)
                    }
                    return null
                }
            } catch (e: HHAPIException.RateLimitException) {
                log.warn("🔗 [URL] Rate limit ${vacancy.id} -> SKIPPED")
                try {
                    statusUpdater.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                } catch (updateError: Exception) {
                    log.error("🔗 [URL] ${vacancy.id} SKIPPED update failed: ${updateError.message}", updateError)
                }
                throw OllamaException.ConnectionException(
                    "Rate limit exceeded while checking vacancy URL. Vacancy marked as SKIPPED for retry later.",
                )
            }
        } else {
            log.trace("🔗 [URL] Skip check for Telegram ${vacancy.id}")
        }

        // Шаг 2: Валидация контента (исключения, навыки)
        val contentValidation = contentValidator.validate(vacancy)
        if (!contentValidation.isValid) {
            log.warn("🤖 [Ollama] ${vacancy.id} rejected: ${contentValidation.rejectionReason}")
            metricsService.incrementVacanciesRejectedByValidator()
            metricsService.incrementVacanciesSkipped()
            try {
                statusUpdater.updateVacancyStatus(vacancy.withStatus(VacancyStatus.REJECTED_BY_VALIDATOR))
            } catch (e: Exception) {
                log.error("🤖 [Ollama] Failed to update status for ${vacancy.id}: ${e.message}", e)
            }
            return null
        }

        // Шаг 3: Загрузка резюме
        val resume = resumeProvider.loadResume()
        val resumeStructure = resumeProvider.getResumeStructure(resume)
        log.debug("🤖 [Ollama] Loaded resume (skills: ${resumeStructure?.skills?.size ?: 0})")

        // Шаг 4: Анализ через LLM (VacancyAnalyzerService)
        log.debug("🤖 [Ollama] Sending to LLM...")
        val analysisStartTime = System.currentTimeMillis()
        val analysisResult = llmAnalyzer.analyze(vacancy, resume, resumeStructure)
        val analysisDuration = System.currentTimeMillis() - analysisStartTime

        metricsService.recordVacancyAnalysisTime(analysisDuration)
        analysisTimeService.updateAverageTime(analysisDuration)
        log.info("🤖 [Ollama] ${vacancy.id}: ${analysisDuration}ms, relevant=${analysisResult.isRelevant}, score=${String.format("%.1f", analysisResult.relevanceScore * 100)}%")

        val validatedSkills = analysisResult.skills

        val analysis = VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = analysisResult.isRelevant,
            relevanceScore = analysisResult.relevanceScore,
            reasoning = analysisResult.reasoning ?: "Процент совпадения: ${String.format("%.1f", analysisResult.relevanceScore * 100)}%",
            matchedSkills = objectMapper.writeValueAsString(validatedSkills),
            suggestedCoverLetter = null,
            coverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,
            coverLetterAttempts = 0,
            coverLetterLastAttemptAt = null,
        )

        val savedAnalysis = withContext(Dispatchers.IO) {
            saveAnalysisAndSkillsInTransaction(analysis, vacancy, analysisResult.isRelevant, validatedSkills)
        }

        if (analysisResult.isRelevant && validatedSkills.isNotEmpty()) {
            try {
                saveSkillsFromAnalysisAsync(vacancy, validatedSkills)
                log.debug("🤖 [Ollama] ${vacancy.id}: saved ${validatedSkills.size} skills")
            } catch (e: Exception) {
                log.error("🤖 [Ollama] Failed to save skills for ${vacancy.id}: ${e.message}", e)
            }
        }

        processedVacancyCacheService.markAsProcessed(vacancy.id)
        metricsService.incrementVacanciesAnalyzed()
        if (savedAnalysis.isRelevant) metricsService.incrementVacanciesRelevant()
        else metricsService.incrementVacanciesSkipped()

        return savedAnalysis
    }

    @Transactional(rollbackFor = [Exception::class])
    fun saveAnalysisAndSkillsInTransaction(
        analysis: VacancyAnalysis,
        vacancy: Vacancy,
        isRelevant: Boolean,
        validatedSkills: List<String>,
    ): VacancyAnalysis {
        return repository.save(analysis)
    }

    private suspend fun saveSkillsFromAnalysisAsync(vacancy: Vacancy, skills: List<String>) {
        if (skills.isEmpty()) return
        val keySkills = skills.map { com.hhassistant.integration.hh.dto.KeySkillDto(name = it) }
        skillSaver.extractAndSaveSkills(vacancy, keySkills)
    }
}
