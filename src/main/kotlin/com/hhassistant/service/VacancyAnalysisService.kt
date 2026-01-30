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
import com.hhassistant.event.VacancyAnalyzedEvent
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class VacancyAnalysisService(
    private val ollamaClient: OllamaClient,
    private val resumeService: ResumeService,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
    private val promptConfig: PromptConfig,
    private val coverLetterQueueService: CoverLetterQueueService,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
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

        // Для релевантных вакансий НЕ генерируем письмо сразу при анализе
        // Вместо этого добавляем в очередь генерации писем
        // Это позволяет обрабатывать генерацию асинхронно и контролировать количество попыток
        val coverLetter = if (validatedResult.isRelevant) {
            log.info("✍️ [Ollama] Relevant vacancy ${vacancy.id} will be processed by cover letter queue (score: ${String.format("%.2f", validatedResult.relevanceScore * 100)}%)")
            // НЕ генерируем письмо здесь - очередь сама это сделает
            null
        } else {
            log.debug("ℹ️ [Ollama] Skipping cover letter generation (vacancy is not relevant, score: ${String.format("%.2f", validatedResult.relevanceScore * 100)}%)")
            null
        }

        // Сохраняем результат
        val analysis = VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = validatedResult.isRelevant,
            relevanceScore = validatedResult.relevanceScore,
            reasoning = validatedResult.reasoning,
            matchedSkills = objectMapper.writeValueAsString(validatedResult.matchedSkills),
            suggestedCoverLetter = coverLetter, // Всегда null, так как письмо генерируется в очереди
            coverLetterGenerationStatus = if (validatedResult.isRelevant) {
                // Если вакансия релевантна, добавляем в очередь генерации писем
                CoverLetterGenerationStatus.RETRY_QUEUED
            } else {
                // Если вакансия не релевантна, письмо не нужно - помечаем как NOT_ATTEMPTED
                CoverLetterGenerationStatus.NOT_ATTEMPTED
            },
            // Для релевантных вакансий без письма - добавляем в очередь (attempts = 0, так как еще не пытались)
            // Для нерелевантных - attempts = 0 (письмо не нужно)
            coverLetterAttempts = 0,
            coverLetterLastAttemptAt = null,
        )

        val savedAnalysis = repository.save(analysis)
        log.info("💾 [Ollama] ✅ Saved analysis to database for vacancy ${vacancy.id} (isRelevant=${savedAnalysis.isRelevant}, score=${String.format("%.2f", savedAnalysis.relevanceScore * 100)}%)")
        
        // Публикуем событие анализа вакансии
        eventPublisher.publishEvent(VacancyAnalyzedEvent(this, vacancy, savedAnalysis))
        
        // Если вакансия релевантна, но письмо не сгенерировано - добавляем в очередь
        if (savedAnalysis.isRelevant && !savedAnalysis.hasCoverLetter() && savedAnalysis.coverLetterGenerationStatus == CoverLetterGenerationStatus.RETRY_QUEUED) {
            // Добавляем в очередь генерации писем (будет обработано асинхронно)
            if (savedAnalysis.id != null) {
                coverLetterQueueService.enqueue(savedAnalysis.id, savedAnalysis.vacancyId, savedAnalysis.coverLetterAttempts + 1)
            }
        }
        
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
     * Результат анализа вакансии (используется внутри сервиса и в CoverLetterQueueService)
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
