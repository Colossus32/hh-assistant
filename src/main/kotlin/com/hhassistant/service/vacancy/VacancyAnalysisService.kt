package com.hhassistant.service.vacancy

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.aspect.Loggable
import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.config.AppConstants
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.service.monitoring.CircuitBreakerStateService
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.service.skill.SkillExtractionService
import com.hhassistant.service.util.AnalysisTimeService
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class VacancyAnalysisService(
    private val ollamaClient: OllamaClient,
    private val resumeService: ResumeService,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
    private val promptConfig: PromptConfig,
    private val vacancyContentValidator: VacancyContentValidator,
    private val vacancyStatusService: VacancyStatusService,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    private val analysisTimeService: AnalysisTimeService,
    private val skillExtractionService: SkillExtractionService,
    private val hhVacancyClient: HHVacancyClient,
    private val circuitBreakerStateService: CircuitBreakerStateService,
    private val processedVacancyCacheService: ProcessedVacancyCacheService,
    private val vacancyProcessingControlService: VacancyProcessingControlService,
    @Qualifier("ollamaCircuitBreaker") private val ollamaCircuitBreaker: CircuitBreaker,
    @Qualifier("ollamaRetry") private val ollamaRetry: Retry,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
    @Value("\${app.analysis.max-concurrent-url-checks:2}") private val maxConcurrentUrlChecks: Int,
) {
    private val log = KotlinLogging.logger {}

    // Семафор для ограничения параллелизма проверок URL
    // Ограничивает количество одновременных HTTP запросов к HH.ru API для проверки валидности URL
    // Работает в паре с RateLimitService для предотвращения перегрузки API
    private val urlCheckSemaphore = Semaphore(maxConcurrentUrlChecks)

    /**
     * Получает анализ вакансии по ID, если он существует
     * @param vacancyId ID вакансии
     * @return VacancyAnalysis если анализ существует, null в противном случае
     */
    suspend fun findByVacancyId(vacancyId: String): VacancyAnalysis? {
        log.debug("🔍 [VacancyAnalysis] Querying database for vacancy $vacancyId")
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            val result = repository.findByVacancyId(vacancyId)
            val duration = System.currentTimeMillis() - startTime
            if (result != null) {
                log.debug("✅ [VacancyAnalysis] Found analysis for vacancy $vacancyId in ${duration}ms")
            } else {
                log.debug("❌ [VacancyAnalysis] No analysis found for vacancy $vacancyId in ${duration}ms")
            }
            result
        }
    }

    /**
     * Получает текущее состояние Circuit Breaker для Ollama
     * @return Состояние Circuit Breaker: "CLOSED", "OPEN", "HALF_OPEN"
     * @deprecated Используйте CircuitBreakerStateService напрямую
     */
    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.getCircuitBreakerState()"))
    fun getCircuitBreakerState(): String {
        return circuitBreakerStateService.getCircuitBreakerState()
    }

    /**
     * Сбрасывает Circuit Breaker в состояние CLOSED
     * Используется когда все активные запросы завершились и circuit breaker находится в OPEN
     * @deprecated Используйте CircuitBreakerStateService напрямую
     */
    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.resetCircuitBreaker()"))
    fun resetCircuitBreaker() {
        circuitBreakerStateService.resetCircuitBreaker()
    }

    /**
     * Пытается принудительно перевести Circuit Breaker из OPEN в HALF_OPEN
     * Используется когда прошло достаточно времени с момента перехода в OPEN
     * и нет активных запросов
     * @deprecated Используйте CircuitBreakerStateService напрямую
     */
    @Deprecated("Use CircuitBreakerStateService directly", ReplaceWith("circuitBreakerStateService.tryTransitionToHalfOpen()"))
    fun tryTransitionToHalfOpen() {
        circuitBreakerStateService.tryTransitionToHalfOpen()
    }

    /**
     * Анализирует вакансию на релевантность для кандидата с использованием LLM.
     *
     * @param vacancy Вакансия для анализа
     * @return Результат анализа с оценкой релевантности и обоснованием, или null если вакансия была отклонена валидатором и удалена из БД
     * @throws OllamaException если не удалось связаться с LLM или получить ответ
     */
    @Loggable
    suspend fun analyzeVacancy(vacancy: Vacancy): VacancyAnalysis? {
        // Проверяем, не анализировалась ли вакансия ранее (используем кэш для быстрой проверки)
        if (processedVacancyCacheService.isProcessed(vacancy.id)) {
            // Вакансия обработана, получаем анализ из БД (запрос к БД)
            log.debug("📊 [VacancyAnalysis] Cache hit for vacancy ${vacancy.id}, querying database for existing analysis")
            val startTime = System.currentTimeMillis()
            val existingAnalysis = withContext(Dispatchers.IO) {
                repository.findByVacancyId(vacancy.id)
            }
            val duration = System.currentTimeMillis() - startTime
            existingAnalysis?.let {
                log.debug("✅ [VacancyAnalysis] Found existing analysis for vacancy ${vacancy.id} in ${duration}ms, returning cached result")
                return it
            }
            // Кэш говорит, что обработана, но анализа нет - возможно кэш устарел, удаляем из кэша
            log.warn(
                "⚠️ [VacancyAnalysis] Vacancy ${vacancy.id} marked as processed in cache, but analysis not found in ${duration}ms. Removing from cache.",
            )
            processedVacancyCacheService.removeFromCache(vacancy.id)
        }

        // Проверяем, не приостановлена ли обработка перед отправкой в LLM
        if (vacancyProcessingControlService.isProcessingPaused()) {
            log.info("[Ollama] Processing is paused, skipping analysis for vacancy ${vacancy.id}")
            throw OllamaException.ConnectionException(
                "Vacancy processing is paused. Please resume processing using /resume command.",
            )
        }

        // Проверяем состояние Circuit Breaker перед анализом
        val circuitBreakerState = ollamaCircuitBreaker.state
        if (circuitBreakerState.name == "OPEN") {
            log.warn("[Ollama] Circuit Breaker is OPEN, skipping analysis for vacancy ${vacancy.id}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
            )
        }

        log.info("[Ollama] Starting analysis for vacancy: ${vacancy.id} - '${vacancy.name}' (${vacancy.employer})")

        // Шаг 1: Проверяем актуальность URL - доступна ли вакансия на HH.ru (самая первая проверка)
        // Используем семафор для ограничения параллелизма проверок URL и Dispatchers.IO для распараллеливания HTTP запросов
        // Это предотвращает перегрузку HH.ru API и работает в паре с RateLimitService
        try {
            val urlCheckResult = urlCheckSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    try {
                        hhVacancyClient.getVacancyDetails(vacancy.id)
                        // Вакансия существует и доступна
                        true
                    } catch (e: HHAPIException.NotFoundException) {
                        // Вакансия не найдена (404) - помечаем как IN_ARCHIVE
                        log.warn(
                            "[URL Check] Vacancy ${vacancy.id} ('${vacancy.name}') not found on HH.ru (404), " +
                                "marking as IN_ARCHIVE",
                        )
                        false
                    } catch (e: Exception) {
                        // Другие ошибки (кроме rate limit) - логируем, но считаем URL валидным
                        log.warn(
                            "[URL Check] Error checking vacancy ${vacancy.id} URL: ${e.message}, " +
                                "assuming URL is valid and proceeding",
                        )
                        true
                    }
                }
            }

            // Если URL неактуален (404) - помечаем как IN_ARCHIVE
            if (!urlCheckResult) {
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.IN_ARCHIVE))
                    log.info("[URL Check] Marked vacancy ${vacancy.id} as IN_ARCHIVE due to 404")
                } catch (e: Exception) {
                    log.error("[URL Check] Failed to mark vacancy ${vacancy.id} as IN_ARCHIVE: ${e.message}", e)
                }
                // Возвращаем null, чтобы показать, что вакансия недоступна
                return null
            }
        } catch (e: HHAPIException.RateLimitException) {
            // Rate limit - помечаем как SKIPPED для повторной проверки позже
            log.warn(
                "[URL Check] Rate limit exceeded while checking vacancy ${vacancy.id} URL, " +
                    "marking as SKIPPED for retry later",
            )
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                log.info("[URL Check] Marked vacancy ${vacancy.id} as SKIPPED due to rate limit")
            } catch (updateError: Exception) {
                log.error("[URL Check] Failed to mark vacancy ${vacancy.id} as SKIPPED: ${updateError.message}", updateError)
            }
            // Выбрасываем исключение, чтобы оно было обработано в VacancyProcessingQueueService
            throw OllamaException.ConnectionException(
                "Rate limit exceeded while checking vacancy URL. Vacancy marked as SKIPPED for retry later.",
            )
        }

        // Шаг 2: Проверяем вакансию на запрещенные слова и навыки из резюме ДО анализа через LLM
        // VacancyContentValidator выполняет все предварительные проверки:
        // 1. Exclusion keywords
        // 2. Resume skills matching (если включено)
        val contentValidation = vacancyContentValidator.validate(vacancy)
        if (!contentValidation.isValid) {
            log.warn(
                "[Ollama] Vacancy ${vacancy.id} ('${vacancy.name}') rejected by content validator: ${contentValidation.rejectionReason}",
            )

            // Обновляем метрики
            metricsService.incrementVacanciesRejectedByValidator()
            metricsService.incrementVacanciesSkipped()

            // Удаляем вакансию из БД, так как она содержит бан-слова
            // Не сохраняем анализ - такие вакансии нам не нужны
            try {
                skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                log.info("[Ollama] Deleted vacancy ${vacancy.id} from database due to exclusion rules")
            } catch (e: Exception) {
                log.error("[Ollama] Failed to delete vacancy ${vacancy.id}: ${e.message}", e)
            }

            // Возвращаем null, чтобы показать, что вакансия была удалена
            // Это предотвратит дальнейшую обработку
            return null
        }

        // Шаг 3: Загружаем резюме для формирования промпта (только если валидация и URL проверка прошли)
        val resume = resumeService.loadResume()
        val resumeStructure = resumeService.getResumeStructure(resume)
        log.debug("[Ollama] Loaded resume for analysis (skills: ${resumeStructure?.skills?.size ?: 0})")

        // Формируем промпт для анализа
        val analysisPrompt = buildAnalysisPrompt(vacancy, resume, resumeStructure)
        log.debug("[Ollama] Analysis prompt prepared (length: ${analysisPrompt.length} chars)")

        // Анализируем через LLM с использованием Circuit Breaker и Retry
        log.info("[Ollama] Sending analysis request to Ollama...")
        val analysisStartTime = System.currentTimeMillis()
        val analysisResponse = try {
            // Используем Circuit Breaker и Retry для защиты от сбоев
            // Используем chatForAnalysis для более детерминированных ответов (lower temperature)
            ollamaRetry.executeSuspendFunction {
                ollamaCircuitBreaker.executeSuspendFunction {
                    ollamaClient.chatForAnalysis(
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
                }
            }
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.error("[Ollama] Circuit Breaker is OPEN for vacancy ${vacancy.id}: ${e.message}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
                e,
            )
        } catch (e: Exception) {
            log.error("[Ollama] Failed to analyze vacancy ${vacancy.id} via Ollama after retries: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to connect to Ollama service for vacancy analysis after retries: ${e.message}",
                e,
            )
        }
        val analysisDuration = System.currentTimeMillis() - analysisStartTime
        metricsService.recordVacancyAnalysisTime(analysisDuration)
        // Обновляем среднее время обработки
        analysisTimeService.updateAverageTime(analysisDuration)
        log.info(
            "[Ollama] Received analysis response from Ollama (took ${analysisDuration}ms, response length: ${analysisResponse.length} chars)",
        )
        // Логируем полный ответ от LLM
        log.debug(
            "[Ollama] Full LLM response for vacancy ${vacancy.id}:\n$analysisResponse",
        )

        // Парсим ответ
        val analysisResult = parseAnalysisResponse(analysisResponse, vacancy.id)
        val extractedSkills = analysisResult.extractSkills()
        log.debug(
            "[Ollama] Parsed analysis result: skills=${extractedSkills.size}, relevanceScore=${analysisResult.relevanceScore}",
        )

        // Валидируем результат анализа
        val validatedResult = validateAnalysisResult(analysisResult)

        // Определяем релевантность на основе relevance_score
        val isRelevant = validatedResult.isRelevantResult(minRelevanceScore)
        val validatedSkills = validatedResult.extractSkills()
        log.info(
            "[Ollama] Analysis result for '${vacancy.name}': isRelevant=$isRelevant, relevanceScore=${String.format(
                "%.2f",
                validatedResult.relevanceScore * 100,
            )}%, skills=${validatedSkills.size}",
        )

        // Сохраняем результат
        val analysis = VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = isRelevant,
            relevanceScore = validatedResult.relevanceScore,
            reasoning = validatedResult.reasoning ?: "Процент совпадения: ${String.format("%.1f", validatedResult.relevanceScore * 100)}%",
            matchedSkills = objectMapper.writeValueAsString(validatedSkills),
            suggestedCoverLetter = null,
            coverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,
            coverLetterAttempts = 0,
            coverLetterLastAttemptAt = null,
        )

        val savedAnalysis = repository.save(analysis)
        log.info(
            "[Ollama] Saved analysis to database for vacancy ${vacancy.id} (isRelevant=$isRelevant, score=${String.format(
                "%.2f",
                savedAnalysis.relevanceScore * 100,
            )}%)",
        )

        // Обновляем кэш обработанных вакансий
        processedVacancyCacheService.markAsProcessed(vacancy.id)

        // Сохраняем навыки в БД, если вакансия релевантна (relevance_score >= minRelevanceScore)
        if (isRelevant && validatedSkills.isNotEmpty()) {
            try {
                saveSkillsFromAnalysis(vacancy, validatedSkills)
                log.info("[Ollama] Saved ${validatedSkills.size} skills to database for vacancy ${vacancy.id}")
            } catch (e: Exception) {
                log.error("[Ollama] Failed to save skills for vacancy ${vacancy.id}: ${e.message}", e)
                // Не прерываем обработку из-за ошибки сохранения навыков
            }
        } else {
            log.debug(
                "[Ollama] Skipping skill extraction for vacancy ${vacancy.id} (isRelevant=$isRelevant, skills=${validatedSkills.size})",
            )
        }

        // Обновляем метрики
        metricsService.incrementVacanciesAnalyzed()
        if (savedAnalysis.isRelevant) {
            metricsService.incrementVacanciesRelevant()
        } else {
            metricsService.incrementVacanciesSkipped()
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
        // Формируем содержимое резюме - используем только структурированные данные для оптимизации
        val resumeContent = if (resumeStructure != null) {
            buildString {
                appendLine("Навыки: ${resumeStructure.skills.joinToString(", ")}")
                resumeStructure.desiredPosition?.let {
                    appendLine("Позиция: $it")
                }
                resumeStructure.desiredSalary?.let {
                    appendLine("Зарплата: от $it руб")
                }
                // Ограничиваем summary до 200 символов для оптимизации
                resumeStructure.summary?.take(200)?.let {
                    appendLine("О себе: $it")
                }
            }
        } else {
            // Если нет структурированных данных, используем первые 500 символов
            "Резюме:\n${resume.rawText.take(500)}"
        }

        // Ограничиваем описание вакансии до 1000 символов для оптимизации
        val description = (vacancy.description ?: "Описание отсутствует").take(1000)

        // Заменяем переменные в шаблоне (убраны employer и area для оптимизации)
        return promptConfig.analysisTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{salary}", vacancy.salary ?: "Не указана")
            .replace("{experience}", vacancy.experience ?: "Не указан")
            .replace("{description}", description)
            .replace("{resumeContent}", resumeContent)
    }

    private fun parseAnalysisResponse(response: String, vacancyId: String): AnalysisResult {
        return try {
            // Шаг 1: Извлекаем JSON из markdown блоков если есть (```json ... ```)
            val cleanedResponse = extractJsonFromMarkdown(response)

            // Шаг 2: Извлекаем JSON объект из ответа
            val jsonString = extractJsonObject(cleanedResponse, vacancyId)

            // Шаг 3: Очищаем JSON от проблемных символов (неэкранированные переносы строк в строках)
            val sanitizedJson = sanitizeJsonString(jsonString)

            // Шаг 4: Парсим JSON
            val parsed = try {
                objectMapper.readValue(sanitizedJson, AnalysisResult::class.java)
            } catch (e: JsonProcessingException) {
                // Если не получилось распарсить после sanitize, пробуем еще раз с более агрессивной очисткой
                log.warn(
                    "Failed to parse JSON after sanitization for vacancy $vacancyId, trying alternative parsing. Error: ${e.message}",
                )
                val alternativeJson = sanitizeJsonStringAlternative(jsonString)
                try {
                    objectMapper.readValue(alternativeJson, AnalysisResult::class.java)
                } catch (e2: Exception) {
                    log.error(
                        "Failed to parse JSON even after alternative sanitization for vacancy $vacancyId. Original error: ${e.message}, Alternative error: ${e2.message}",
                    )
                    throw OllamaException.ParsingException(
                        "Failed to parse JSON response from LLM for vacancy $vacancyId after all sanitization attempts: ${e.message}",
                        e,
                    )
                }
            }
            parsed
        } catch (e: JsonProcessingException) {
            log.error("Invalid JSON from LLM for vacancy $vacancyId: ${e.message}. Response: ${response.take(500)}", e)
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
     * Извлекает JSON из markdown блоков (```json ... ```)
     * Если найдено несколько блоков, берет первый валидный
     */
    private fun extractJsonFromMarkdown(response: String): String {
        // Ищем все markdown блоки с JSON
        val markdownJsonPattern = Regex("```(?:json)?\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val matches = markdownJsonPattern.findAll(response)

        // Если нашли markdown блоки, берем первый
        matches.firstOrNull()?.let {
            val extracted = it.groupValues[1].trim()
            log.debug("Extracted JSON from markdown block (length: ${extracted.length})")
            return extracted
        }

        // Если markdown блоков нет, возвращаем исходный ответ
        return response
    }

    /**
     * Извлекает JSON объект из ответа
     * Если найдено несколько JSON объектов, пытается распарсить каждый до первого успешного
     */
    private fun extractJsonObject(response: String, vacancyId: String): String {
        // Находим все возможные JSON объекты (начинаются с {)
        val jsonObjects = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < response.length) {
            if (response[i] == '{') {
                // Нашли начало JSON объекта
                var braceCount = 0
                var jsonEnd = i
                for (j in i until response.length) {
                    when (response[j]) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount == 0) {
                                jsonEnd = j + 1
                                jsonObjects.add(Pair(i, jsonEnd))
                                break
                            }
                        }
                    }
                }
                // Если нашли полный объект, переходим к следующему после него
                if (braceCount == 0) {
                    i = jsonEnd
                } else {
                    i++
                }
            } else {
                i++
            }
        }

        if (jsonObjects.isEmpty()) {
            log.warn("No JSON object found in LLM response for vacancy $vacancyId. Response: ${response.take(500)}")
            throw OllamaException.ParsingException(
                "No valid JSON object found in LLM response for vacancy $vacancyId",
            )
        }

        // Если нашли несколько JSON объектов, логируем это
        if (jsonObjects.size > 1) {
            log.warn(
                "Found ${jsonObjects.size} JSON objects in response for vacancy $vacancyId, will try to parse each",
            )
        }

        // Пытаемся распарсить каждый JSON объект до первого успешного
        for ((start, end) in jsonObjects) {
            val jsonCandidate = response.substring(start, end)
            try {
                // Пробуем распарсить (без sanitize сначала, чтобы проверить валидность)
                objectMapper.readTree(jsonCandidate)
                log.debug(
                    "Successfully extracted JSON object (start: $start, end: $end, length: ${jsonCandidate.length})",
                )
                return jsonCandidate
            } catch (e: Exception) {
                log.debug("JSON object at [$start:$end] is not valid, trying next...")
                // Продолжаем поиск
            }
        }

        // Если ни один не распарсился напрямую, берем первый и попробуем sanitize
        val firstJson = response.substring(jsonObjects[0].first, jsonObjects[0].second)
        log.debug("Using first JSON object after sanitization (length: ${firstJson.length})")
        return firstJson
    }

    /**
     * Очищает JSON строку от проблемных символов
     * Экранирует переносы строк и другие управляющие символы в строковых значениях
     */
    private fun sanitizeJsonString(jsonString: String): String {
        // Проходим по строке и экранируем переносы строк внутри строковых значений
        val result = StringBuilder()
        var insideString = false
        var escapeNext = false
        var i = 0

        while (i < jsonString.length) {
            val char = jsonString[i]

            when {
                escapeNext -> {
                    // Если предыдущий символ был \, просто добавляем текущий
                    result.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    // Обратный слэш - следующий символ будет экранирован
                    result.append(char)
                    escapeNext = true
                }
                char == '"' -> {
                    // Кавычка - переключаем состояние "внутри строки"
                    result.append(char)
                    insideString = !insideString
                }
                insideString && (char == '\n' || char == '\r' || char == '\t') -> {
                    // Внутри строки нашли перенос строки или табуляцию - экранируем
                    when (char) {
                        '\n' -> result.append("\\n")
                        '\r' -> result.append("\\r")
                        '\t' -> result.append("\\t")
                        else -> result.append(char)
                    }
                }
                else -> {
                    result.append(char)
                }
            }
            i++
        }

        return result.toString()
    }

    /**
     * Альтернативный метод очистки JSON (более агрессивный)
     * Удаляет все переносы строк и табуляции из строковых значений
     */
    private fun sanitizeJsonStringAlternative(jsonString: String): String {
        // Используем регулярное выражение для замены переносов строк в строковых значениях
        return jsonString
            .replace(Regex("""("reasoning"\s*:\s*")([^"]*?)(\n|\r|\t)([^"]*?)(")""")) { matchResult ->
                val key = matchResult.groupValues[1]
                val valueBefore = matchResult.groupValues[2]
                val newline = matchResult.groupValues[3]
                val valueAfter = matchResult.groupValues[4]
                val quote = matchResult.groupValues[5]

                // Заменяем переносы строк на пробелы
                val escapedNewline = when (newline) {
                    "\n" -> "\\n"
                    "\r" -> "\\r"
                    "\t" -> "\\t"
                    else -> newline
                }

                "$key$valueBefore$escapedNewline$valueAfter$quote"
            }
            .replace(Regex("""("reasoning"\s*:\s*"[^"]*?)(\n|\r|\t)""")) { matchResult ->
                val before = matchResult.groupValues[1]
                val newline = matchResult.groupValues[2]
                val escaped = when (newline) {
                    "\n" -> "\\n"
                    "\r" -> "\\r"
                    "\t" -> "\\t"
                    else -> newline
                }
                "$before$escaped"
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
        require(
            result.relevanceScore in AppConstants.Validation.RELEVANCE_SCORE_MIN..AppConstants.Validation.RELEVANCE_SCORE_MAX,
        ) {
            "Relevance score must be between " +
                "${AppConstants.Validation.RELEVANCE_SCORE_MIN} and " +
                "${AppConstants.Validation.RELEVANCE_SCORE_MAX}, got: ${result.relevanceScore}"
        }

        // Получаем навыки из нового или старого формата
        val allSkills = result.extractSkills()

        // Валидируем навыки: минимум 3, максимум 20
        val validatedSkills = allSkills
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        if (validatedSkills.size < 3 && allSkills.isNotEmpty()) {
            log.warn("[Ollama] Only ${validatedSkills.size} valid skills extracted (minimum 3 expected)")
        }

        // Возвращаем результат с нормализованными навыками в поле skills
        return result.copy(
            skills = validatedSkills,
            matchedSkills = null, // Очищаем старое поле
        )
    }

    /**
     * Сохраняет навыки из анализа LLM в БД.
     * Использует SkillExtractionService для нормализации и сохранения.
     */
    private suspend fun saveSkillsFromAnalysis(vacancy: Vacancy, skills: List<String>) {
        if (skills.isEmpty()) {
            log.debug("[Ollama] No skills to save for vacancy ${vacancy.id}")
            return
        }

        // Преобразуем список строк в KeySkillDto для использования существующего сервиса
        val keySkills = skills.map { skillName ->
            com.hhassistant.client.hh.dto.KeySkillDto(name = skillName)
        }

        // Используем существующий сервис для сохранения навыков
        skillExtractionService.extractAndSaveSkills(vacancy, keySkills)
    }

    /**
     * Результат анализа вакансии (оптимизированный формат)
     * Содержит только навыки из вакансии и процент совпадения
     * Поддерживает оба формата: новый (skills) и старый (matched_skills)
     */
    data class AnalysisResult(
        @com.fasterxml.jackson.annotation.JsonProperty("skills")
        val skills: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("matched_skills")
        val matchedSkills: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("relevance_score")
        val relevanceScore: Double,
        @com.fasterxml.jackson.annotation.JsonProperty("is_relevant")
        val isRelevant: Boolean? = null,
        val reasoning: String? = null,
    ) {
        /**
         * Извлекает список навыков (из нового или старого формата)
         */
        fun extractSkills(): List<String> {
            return skills ?: matchedSkills ?: emptyList()
        }

        /**
         * Определяет, является ли вакансия релевантной на основе relevance_score или is_relevant
         */
        fun isRelevantResult(minScore: Double = 0.6): Boolean {
            return isRelevant ?: (relevanceScore >= minScore)
        }
    }
}
