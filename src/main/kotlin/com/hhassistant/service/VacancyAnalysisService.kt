package com.hhassistant.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.config.AppConstants
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlinx.coroutines.delay

@Service
class VacancyAnalysisService(
    private val ollamaClient: OllamaClient,
    private val resumeService: ResumeService,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
    private val promptConfig: PromptConfig,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
    @Value("\${app.analysis.cover-letter.max-retries:3}") private val maxCoverLetterRetries: Int,
    @Value("\${app.analysis.cover-letter.retry-delay-seconds:5}") private val coverLetterRetryDelaySeconds: Long,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Анализирует вакансию на релевантность для кандидата с использованием LLM.
     *
     * @param vacancy Вакансия для анализа
     * @return Результат анализа с оценкой релевантности и обоснованием
     * @throws OllamaException если не удалось связаться с LLM или получить ответ
     */
    suspend fun analyzeVacancy(vacancy: Vacancy): VacancyAnalysis {
        // Проверяем, не анализировалась ли вакансия ранее
        repository.findByVacancyId(vacancy.id)?.let {
            log.debug("Vacancy ${vacancy.id} already analyzed, returning existing analysis")
            return it
        }

        log.info("🤖 [Ollama] Starting analysis for vacancy: ${vacancy.id} - '${vacancy.name}' (${vacancy.employer})")

        // Загружаем резюме
        val resume = resumeService.loadResume()
        val resumeStructure = resumeService.getResumeStructure(resume)
        log.debug("📄 [Ollama] Loaded resume for analysis (skills: ${resumeStructure?.skills?.size ?: 0})")

        // Формируем промпт для анализа
        val analysisPrompt = buildAnalysisPrompt(vacancy, resume, resumeStructure)
        log.debug("📝 [Ollama] Analysis prompt prepared (length: ${analysisPrompt.length} chars)")

        // Анализируем через LLM
        log.info("🔄 [Ollama] Sending analysis request to Ollama...")
        val analysisStartTime = System.currentTimeMillis()
        val analysisResponse = try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(
                        role = "system",
                        content = buildSystemPrompt(),
                    ),
                    ChatMessage(
                        role = "user",
                        content = analysisPrompt,
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("❌ [Ollama] Failed to analyze vacancy ${vacancy.id} via Ollama: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to connect to Ollama service for vacancy analysis: ${e.message}",
                e,
            )
        }
        val analysisDuration = System.currentTimeMillis() - analysisStartTime
        log.info("✅ [Ollama] Received analysis response from Ollama (took ${analysisDuration}ms, response length: ${analysisResponse.length} chars)")

        // Парсим ответ
        val analysisResult = parseAnalysisResponse(analysisResponse, vacancy.id)
        log.debug("📊 [Ollama] Parsed analysis result: isRelevant=${analysisResult.isRelevant}, score=${analysisResult.relevanceScore}")

        // Валидируем результат анализа
        val validatedResult = validateAnalysisResult(analysisResult)

        log.info("📊 [Ollama] Analysis result for '${vacancy.name}': isRelevant=${validatedResult.isRelevant}, relevanceScore=${String.format("%.2f", validatedResult.relevanceScore * 100)}%, matchedSkills=${validatedResult.matchedSkills.size}")

        // Генерируем сопроводительное письмо для релевантных вакансий с ретраями
        // Генерируем письмо, если вакансия релевантна ИЛИ score >= minRelevanceScore
        // Это гарантирует, что письмо будет сгенерировано для всех вакансий, которые отправляются в Telegram
        val coverLetter = if (validatedResult.isRelevant || validatedResult.relevanceScore >= minRelevanceScore) {
            generateCoverLetterWithRetry(vacancy, resume, resumeStructure, validatedResult)
        } else {
            log.debug("ℹ️ [Ollama] Skipping cover letter generation (not relevant and score too low: ${String.format("%.2f", validatedResult.relevanceScore * 100)}% < ${minRelevanceScore * 100}%)")
            null
        }

        // Сохраняем результат
        val analysis = VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = validatedResult.isRelevant,
            relevanceScore = validatedResult.relevanceScore,
            reasoning = validatedResult.reasoning,
            matchedSkills = objectMapper.writeValueAsString(validatedResult.matchedSkills),
            suggestedCoverLetter = coverLetter,
            coverLetterGenerationStatus = if (coverLetter != null) {
                CoverLetterGenerationStatus.SUCCESS
            } else {
                // Если письмо не сгенерировано, добавляем в очередь ретраев
                CoverLetterGenerationStatus.RETRY_QUEUED
            },
            // При первой неудаче: устанавливаем attempts = maxRetries (все попытки использованы)
            // Но в очереди ретраев можно будет попробовать еще раз (до maxRetries * 2 общих попыток)
            coverLetterAttempts = if (coverLetter == null) maxCoverLetterRetries else 0,
            coverLetterLastAttemptAt = if (coverLetter == null) LocalDateTime.now() else null,
        )

        val savedAnalysis = repository.save(analysis)
        log.info("💾 [Ollama] ✅ Saved analysis to database for vacancy ${vacancy.id} (isRelevant=${savedAnalysis.isRelevant}, score=${String.format("%.2f", savedAnalysis.relevanceScore * 100)}%)")

        return savedAnalysis
    }

    private fun buildSystemPrompt(): String {
        return promptConfig.analysisSystem
    }

    private fun buildAnalysisPrompt(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
    ): String {
        // Формируем содержимое резюме
        val resumeContent = if (resumeStructure != null) {
            buildString {
                appendLine("Навыки: ${resumeStructure.skills.joinToString(", ")}")
                resumeStructure.desiredPosition?.let {
                    appendLine("Желаемая позиция: $it")
                }
                resumeStructure.desiredSalary?.let {
                    appendLine("Желаемая зарплата: от $it руб")
                }
                resumeStructure.summary?.let {
                    appendLine("О себе: $it")
                }
            }
        } else {
            "Полный текст резюме:\n${resume.rawText}"
        }

        // Заменяем переменные в шаблоне
        return promptConfig.analysisTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{employer}", vacancy.employer)
            .replace("{salary}", vacancy.salary ?: "Не указана")
            .replace("{area}", vacancy.area)
            .replace("{experience}", vacancy.experience ?: "Не указан")
            .replace("{description}", vacancy.description ?: "Описание отсутствует")
            .replace("{resumeContent}", resumeContent)
    }

    private fun parseAnalysisResponse(response: String, vacancyId: String): AnalysisResult {
        return try {
            // Пытаемся извлечь JSON из ответа (на случай, если LLM добавит текст до/после JSON)
            val jsonStart = response.indexOf(AppConstants.Indices.JSON_START_CHAR)
            val jsonEnd = response.lastIndexOf(AppConstants.Indices.JSON_END_CHAR) + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = response.substring(jsonStart, jsonEnd)
                val parsed = objectMapper.readValue(jsonString, AnalysisResult::class.java)
                parsed
            } else {
                log.warn("Failed to find JSON in LLM response for vacancy $vacancyId. Response: $response")
                throw OllamaException.ParsingException(
                    "No valid JSON found in LLM response for vacancy $vacancyId",
                )
            }
        } catch (e: JsonProcessingException) {
            log.error("Invalid JSON from LLM for vacancy $vacancyId: ${e.message}. Response: $response", e)
            throw OllamaException.ParsingException(
                "Failed to parse JSON response from LLM for vacancy $vacancyId: ${e.message}",
                e,
            )
        } catch (e: OllamaException) {
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error parsing analysis response for vacancy $vacancyId: ${e.message}", e)
            throw OllamaException.ParsingException(
                "Unexpected error parsing LLM response for vacancy $vacancyId: ${e.message}",
                e,
            )
        }
    }

    /**
     * Генерирует сопроводительное письмо с ретраями (до maxCoverLetterRetries попыток)
     *
     * @param vacancy Вакансия
     * @param resume Резюме
     * @param resumeStructure Структурированные данные резюме
     * @param analysisResult Результат анализа
     * @return Сгенерированное письмо или null, если все попытки неудачны
     */
    private suspend fun generateCoverLetterWithRetry(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
        analysisResult: AnalysisResult,
    ): String? {
        var lastException: Exception? = null

        for (attempt in 1..maxCoverLetterRetries) {
            try {
                log.info("✍️ [Ollama] Generating cover letter for vacancy ${vacancy.id} (attempt $attempt/$maxCoverLetterRetries)...")
                val coverLetter = generateCoverLetter(vacancy, resume, resumeStructure, analysisResult)
                log.info("✅ [Ollama] Cover letter generated successfully on attempt $attempt (length: ${coverLetter.length} chars)")
                return coverLetter
            } catch (e: Exception) {
                lastException = e
                log.warn("⚠️ [Ollama] Cover letter generation attempt $attempt/$maxCoverLetterRetries failed for vacancy ${vacancy.id}: ${e.message}")
                
                if (attempt < maxCoverLetterRetries) {
                    val delayMs = attempt * coverLetterRetryDelaySeconds * 1000L // Экспоненциальная задержка
                    log.info("🔄 [Ollama] Retrying cover letter generation in ${delayMs}ms...")
                    delay(delayMs)
                } else {
                    log.error("❌ [Ollama] All $maxCoverLetterRetries attempts to generate cover letter failed for vacancy ${vacancy.id}", e)
                }
            }
        }

        // Все попытки неудачны
        log.error("❌ [Ollama] Failed to generate cover letter after $maxCoverLetterRetries attempts for vacancy ${vacancy.id}. Last error: ${lastException?.message}")
        return null
    }

    /**
     * Генерирует сопроводительное письмо (одна попытка)
     * Публичный метод для использования в CoverLetterRetryService
     */
    suspend fun generateCoverLetter(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
        analysisResult: AnalysisResult,
    ): String {
        log.debug("🔄 [Ollama] Generating cover letter for vacancy: ${vacancy.id}")

        val coverLetterPrompt = buildCoverLetterPrompt(vacancy, resume, resumeStructure, analysisResult)

        return try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(
                        role = "system",
                        content = promptConfig.coverLetterSystem,
                    ),
                    ChatMessage(
                        role = "user",
                        content = coverLetterPrompt,
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to generate cover letter for vacancy ${vacancy.id}: ${e.message}", e)
            throw OllamaException.CoverLetterGenerationException(
                "Failed to generate cover letter for vacancy ${vacancy.id}: ${e.message}",
                e,
            )
        }
    }

    private fun buildCoverLetterPrompt(
        vacancy: Vacancy,
        @Suppress("UNUSED_PARAMETER") resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
        analysisResult: AnalysisResult,
    ): String {
        val summary = if (resumeStructure?.summary != null) {
            "О кандидате: ${resumeStructure.summary}"
        } else {
            ""
        }

        // Заменяем переменные в шаблоне
        return promptConfig.coverLetterTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{employer}", vacancy.employer)
            .replace("{description}", vacancy.description?.take(AppConstants.TextLimits.COVER_LETTER_DESCRIPTION_PREVIEW_LENGTH) ?: "Не указано")
            .replace("{matchedSkills}", analysisResult.matchedSkills.joinToString(", "))
            .replace("{summary}", summary)
    }

    /**
     * Валидирует результат анализа от LLM.
     *
     * @param result Результат анализа для валидации
     * @return Валидированный результат
     * @throws IllegalArgumentException если relevanceScore вне допустимого диапазона
     */
    private fun validateAnalysisResult(result: AnalysisResult): AnalysisResult {
        require(result.relevanceScore in AppConstants.Validation.RELEVANCE_SCORE_MIN..AppConstants.Validation.RELEVANCE_SCORE_MAX) {
            "Relevance score must be between ${AppConstants.Validation.RELEVANCE_SCORE_MIN} and ${AppConstants.Validation.RELEVANCE_SCORE_MAX}, got: ${result.relevanceScore}"
        }
        return result
    }

    /**
     * Результат анализа вакансии (используется внутри сервиса и в CoverLetterRetryService)
     */
    data class AnalysisResult(
        @com.fasterxml.jackson.annotation.JsonProperty("is_relevant")
        val isRelevant: Boolean,
        @com.fasterxml.jackson.annotation.JsonProperty("relevance_score")
        val relevanceScore: Double,
        val reasoning: String,
        @com.fasterxml.jackson.annotation.JsonProperty("matched_skills")
        val matchedSkills: List<String>,
    )
}
