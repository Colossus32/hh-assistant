package com.hhassistant.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.TelegramException
import com.hhassistant.health.OllamaHealthIndicator
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Отдельная ручка для dry-run режима, которая имитирует ответ от HH.ru,
 * прогоняет вакансию через LLM и (опционально) отправляет результат в Telegram.
 *
 * Не влияет на основную бизнес-логику и не зависит от реального HH API.
 *
 * Активируется только при профиле "dry-run".
 */
@RestController
@RequestMapping("/api/dry-run")
@org.springframework.context.annotation.Profile("dry-run")
class DryRunDemoController(
    private val ollamaClient: OllamaClient,
    private val promptConfig: PromptConfig,
    private val objectMapper: ObjectMapper,
    private val telegramClient: TelegramClient,
    private val ollamaHealthIndicator: OllamaHealthIndicator,
    @Value("\${app.dry-run:false}") private val dryRun: Boolean,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
) {
    private val log = KotlinLogging.logger {}

    data class DryRunTelegramResult(
        val attempted: Boolean,
        val sent: Boolean,
        val error: String? = null,
    )

    data class DryRunAnalysisResponse(
        val vacancy: Vacancy,
        val analysis: VacancyAnalysis,
        val telegram: DryRunTelegramResult,
    )

    /**
     * Выполняет полный dry-run сценарий:
     * 1) \"Имитация\" ответа HH.ru (локально созданная вакансия)
     * 2) Анализ вакансии через LLM (VacancyAnalysisService / Ollama)
     * 3) Попытка отправить результат в Telegram (как в реальном пайплайне)
     *
     * GET /api/dry-run/sample-analysis
     *
     * - В обычном режиме (dry-run=false) возвращает 400
     * - В dry-run режиме выполняет цепочку HH -> LLM -> Telegram
     */
    @GetMapping("/sample-analysis")
    fun runSampleAnalysis(): ResponseEntity<Any> {
        // Проверка dryRun оставлена для обратной совместимости,
        // но контроллер доступен только при профиле "dry-run"
        if (!dryRun) {
            log.warn { "DryRunDemoController is active but app.dry-run=false. This should not happen with dry-run profile." }
        }

        val vacancy = createSampleVacancy()

        // Проверяем доступность Ollama перед началом
        val ollamaHealth = ollamaHealthIndicator.health()
        if (ollamaHealth.status.code != "UP") {
            log.warn { "Ollama health check failed: ${ollamaHealth.status.code}" }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                    mapOf(
                        "error" to "Ollama is not available",
                        "health" to ollamaHealth.status.code,
                        "details" to ollamaHealth.details,
                    ),
                )
        }
        log.info { "Ollama health check passed: ${ollamaHealth.details}, proceeding with analysis" }

        val response: DryRunAnalysisResponse = runBlocking {
            log.info { "Running dry-run sample analysis for vacancy ${vacancy.id}" }

            // 1. Анализ вакансии через LLM (без обращения к HH и без чтения PDF)
            val analysis = try {
                log.info { "Step 1: Calling Ollama to analyze vacancy..." }
                analyzeVacancyWithFakeResume(vacancy)
            } catch (e: OllamaException) {
                log.error("Dry-run: OllamaException during analysis: ${e.message}", e)
                return@runBlocking DryRunAnalysisResponse(
                    vacancy = vacancy,
                    analysis = VacancyAnalysis(
                        vacancyId = vacancy.id,
                        isRelevant = false,
                        relevanceScore = 0.0,
                        reasoning = "Ollama error: ${e.message}",
                        matchedSkills = null,
                        suggestedCoverLetter = null,
                    ),
                    telegram = DryRunTelegramResult(
                        attempted = false,
                        sent = false,
                        error = "Ollama analysis failed: ${e.message}",
                    ),
                )
            } catch (e: Exception) {
                log.error("Dry-run: Unexpected error analyzing vacancy with Ollama: ${e.message}", e)
                return@runBlocking DryRunAnalysisResponse(
                    vacancy = vacancy,
                    analysis = VacancyAnalysis(
                        vacancyId = vacancy.id,
                        isRelevant = false,
                        relevanceScore = 0.0,
                        reasoning = "Unexpected error during analysis: ${e.message}",
                        matchedSkills = null,
                        suggestedCoverLetter = null,
                    ),
                    telegram = DryRunTelegramResult(
                        attempted = false,
                        sent = false,
                        error = "Analysis failed: ${e.javaClass.simpleName}: ${e.message}",
                    ),
                )
            }

            log.info { "Step 1 completed: analysis.isRelevant=${analysis.isRelevant}, relevanceScore=${analysis.relevanceScore}" }

            // 2. Попытка отправить сообщение в Telegram (только если релевантна)
            val telegramResult =
                if (analysis.isRelevant) {
                    log.info { "Step 2: Vacancy is relevant, attempting to send to Telegram..." }
                    try {
                        val message = buildTelegramMessage(vacancy, analysis)
                        log.info { "Step 2a: Built Telegram message, sending..." }
                        val sent = telegramClient.sendMessage(message)
                        log.info { "Step 2b: Telegram send result: $sent" }
                        DryRunTelegramResult(
                            attempted = true,
                            sent = sent,
                            error = if (!sent) "Telegram is disabled or not configured" else null,
                        )
                    } catch (e: TelegramException) {
                        log.error("Dry-run: Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
                        DryRunTelegramResult(
                            attempted = true,
                            sent = false,
                            error = e.message ?: "Telegram exception",
                        )
                    } catch (e: Exception) {
                        log.error("Dry-run: unexpected Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
                        DryRunTelegramResult(
                            attempted = true,
                            sent = false,
                            error = e.message ?: "Unexpected Telegram error",
                        )
                    }
                } else {
                    log.info { "Step 2: Vacancy is not relevant (score=${analysis.relevanceScore}), skipping Telegram" }
                    DryRunTelegramResult(
                        attempted = false,
                        sent = false,
                        error = "Vacancy is not relevant, Telegram not called",
                    )
                }

            log.info { "Dry-run completed successfully: vacancy=${vacancy.id}, relevant=${analysis.isRelevant}, telegramSent=${telegramResult.sent}" }

            DryRunAnalysisResponse(vacancy = vacancy, analysis = analysis, telegram = telegramResult)
        }

        return ResponseEntity.ok(response)
    }

    private fun createSampleVacancy(): Vacancy {
        return Vacancy(
            id = "dry-run-senior-kotlin-dev",
            name = "Senior Kotlin Developer (HH Assistant Demo)",
            employer = "ООО Технологии Будущего",
            salary = "200000 - 350000 RUR",
            area = "Москва",
            url = "https://hh.ru/vacancy/dry-run-senior-kotlin-dev",
            description = """
                Ищем опытного Kotlin разработчика для работы над высоконагруженными микросервисами.
                
                Требования:
                - Опыт разработки на Kotlin от 3 лет
                - Знание Spring Boot, Coroutines
                - Опыт работы с PostgreSQL, Redis
                - Понимание принципов микросервисной архитектуры
                - Опыт работы с Docker, Kubernetes
                
                Будет плюсом:
                - Опыт работы с Kafka
                - Знание gRPC
                - Опыт работы в команде по Agile/Scrum
            """.trimIndent(),
            experience = "От 3 до 6 лет",
            publishedAt = LocalDateTime.now().minusDays(1),
            // fetchedAt и status используют значения по умолчанию из сущности Vacancy
        )
    }

    /**
     * Упрощенный анализ вакансии для dry-run:
     * - Использует промпты из PromptConfig
     * - Подставляет фейковое резюме (строка), чтобы не дергать HH и не читать PDF
     * - Не сохраняет результат в базу, возвращает его только в ответе API
     */
    private suspend fun analyzeVacancyWithFakeResume(vacancy: Vacancy): VacancyAnalysis {
        // Фейковое \"резюме\" для промпта
        val fakeResumeContent = """
            Навыки: Kotlin, Java, Spring Boot, PostgreSQL, Redis, Docker, Kubernetes, Kafka, gRPC
            Желаемая позиция: Senior Kotlin Developer
            Желаемая зарплата: от 250000 руб
            О себе: более 5 лет опыта разработки backend-сервисов на Kotlin/Java, 
            опыт проектирования микросервисной архитектуры, наставничества и участия в код-ревью.
        """.trimIndent()

        val analysisPrompt = promptConfig.analysisTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{employer}", vacancy.employer)
            .replace("{salary}", vacancy.salary ?: "Не указана")
            .replace("{area}", vacancy.area)
            .replace("{experience}", vacancy.experience ?: "Не указан")
            .replace("{description}", vacancy.description ?: "Описание отсутствует")
            .replace("{resumeContent}", fakeResumeContent)

        log.info { "Calling Ollama chat API with model..." }
        val startTime = System.currentTimeMillis()
        val response = try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(
                        role = "system",
                        content = promptConfig.analysisSystem,
                    ),
                    ChatMessage(
                        role = "user",
                        content = analysisPrompt,
                    ),
                ),
            )
        } catch (e: java.util.concurrent.TimeoutException) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Dry-run: Ollama chat call timed out after ${elapsed}ms: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Ollama API call timed out after ${elapsed}ms. Check if model is loaded and Ollama is responsive.",
                e,
            )
        } catch (e: org.springframework.web.reactive.function.client.WebClientResponseException) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Dry-run: Ollama HTTP error after ${elapsed}ms: ${e.statusCode} - ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Ollama API returned error ${e.statusCode}: ${e.message}",
                e,
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("Dry-run: Ollama chat call failed after ${elapsed}ms: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to call Ollama API after ${elapsed}ms: ${e.message}",
                e,
            )
        }
        val elapsed = System.currentTimeMillis() - startTime
        log.info { "Received response from Ollama after ${elapsed}ms (length=${response.length} chars), parsing..." }

        val parsed = parseAnalysisResponse(response)
        val validated = validateAnalysisResult(parsed)

        val coverLetter =
            if (validated.isRelevant && validated.relevanceScore >= minRelevanceScore) {
                // Для dry-run достаточно увидеть, что LLM вернул релевантный ответ,
                // генерировать сопроводительное письмо не обязательно.
                null
            } else {
                null
            }

        return VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = validated.isRelevant,
            relevanceScore = validated.relevanceScore,
            reasoning = validated.reasoning,
            matchedSkills = objectMapper.writeValueAsString(validated.matchedSkills),
            suggestedCoverLetter = coverLetter,
        )
    }

    private fun parseAnalysisResponse(response: String): AnalysisResult {
        return try {
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = response.substring(jsonStart, jsonEnd)
                objectMapper.readValue(jsonString, AnalysisResult::class.java)
            } else {
                log.warn("Dry-run: failed to find JSON in LLM response. Response: $response")
                throw IllegalArgumentException("No valid JSON found in LLM response")
            }
        } catch (e: JsonProcessingException) {
            log.error("Dry-run: invalid JSON from LLM: ${e.message}. Response: $response", e)
            throw IllegalArgumentException("Failed to parse JSON response from LLM: ${e.message}", e)
        } catch (e: Exception) {
            log.error("Dry-run: unexpected error parsing analysis response: ${e.message}", e)
            throw IllegalArgumentException("Unexpected error parsing LLM response: ${e.message}", e)
        }
    }

    private fun validateAnalysisResult(result: AnalysisResult): AnalysisResult {
        require(result.relevanceScore in 0.0..1.0) {
            "Relevance score must be between 0.0 and 1.0, got: ${result.relevanceScore}"
        }
        return result
    }

    private data class AnalysisResult(
        val is_relevant: Boolean,
        val relevance_score: Double,
        val reasoning: String,
        val matched_skills: List<String>,
    ) {
        val isRelevant: Boolean get() = is_relevant
        val relevanceScore: Double get() = relevance_score
        val matchedSkills: List<String> get() = matched_skills
    }

    private fun buildTelegramMessage(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("🎯 <b>Новая релевантная вакансия (dry-run)!</b>")
        sb.appendLine()
        sb.appendLine("<b>${vacancy.name}</b>")
        sb.appendLine("🏢 ${vacancy.employer}")
        if (vacancy.salary != null) {
            sb.appendLine("💰 ${vacancy.salary}")
        }
        sb.appendLine("📍 ${vacancy.area}")
        if (vacancy.experience != null) {
            sb.appendLine("💼 ${vacancy.experience}")
        }
        sb.appendLine()
        sb.appendLine("🔗 <a href=\"${vacancy.url}\">Открыть вакансию</a>")
        sb.appendLine()
        sb.appendLine("<b>Оценка релевантности:</b> ${(analysis.relevanceScore * 100).toInt()}%")
        sb.appendLine()
        sb.appendLine("<b>Обоснование:</b>")
        sb.appendLine(analysis.reasoning)

        if (analysis.suggestedCoverLetter != null) {
            sb.appendLine()
            sb.appendLine("<b>💌 Предложенное сопроводительное письмо:</b>")
            sb.appendLine(analysis.suggestedCoverLetter)
        }

        return sb.toString()
    }
}
