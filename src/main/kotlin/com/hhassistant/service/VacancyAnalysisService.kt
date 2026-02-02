package com.hhassistant.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.hh.HHVacancyClient
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
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class VacancyAnalysisService(
    private val ollamaClient: OllamaClient,
    private val resumeService: ResumeService,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
    private val promptConfig: PromptConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val vacancyContentValidator: VacancyContentValidator,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    private val analysisTimeService: AnalysisTimeService,
    private val skillExtractionService: SkillExtractionService,
    @Qualifier("ollamaCircuitBreaker") private val ollamaCircuitBreaker: CircuitBreaker,
    @Qualifier("ollamaRetry") private val ollamaRetry: Retry,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает текущее состояние Circuit Breaker для Ollama
     * @return Состояние Circuit Breaker: "CLOSED", "OPEN", "HALF_OPEN"
     */
    fun getCircuitBreakerState(): String {
        return ollamaCircuitBreaker.state.name
    }

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

        // Проверяем состояние Circuit Breaker перед анализом
        val circuitBreakerState = ollamaCircuitBreaker.state
        if (circuitBreakerState.name == "OPEN") {
            log.warn("⚠️ [Ollama] Circuit Breaker is OPEN, skipping analysis for vacancy ${vacancy.id}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
            )
        }

        log.info("🤖 [Ollama] Starting analysis for vacancy: ${vacancy.id} - '${vacancy.name}' (${vacancy.employer})")

        // Проверяем вакансию на запрещенные слова/фразы ДО анализа через LLM
        val contentValidation = vacancyContentValidator.validate(vacancy)
        if (!contentValidation.isValid) {
            log.info("🚫 [Ollama] Vacancy ${vacancy.id} rejected by content validator: ${contentValidation.rejectionReason}")

            // Обновляем метрики
            metricsService.incrementVacanciesRejectedByValidator()
            metricsService.incrementVacanciesSkipped()

            // Создаем анализ с результатом "не релевантна" без обращения к LLM
            val rejectedAnalysis = VacancyAnalysis(
                vacancyId = vacancy.id,
                isRelevant = false,
                relevanceScore = 0.0,
                reasoning = "Вакансия отклонена: ${contentValidation.rejectionReason}",
                matchedSkills = "[]",
                suggestedCoverLetter = null,
                coverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,
                coverLetterAttempts = 0,
                coverLetterLastAttemptAt = null,
            )

            val savedAnalysis = repository.save(rejectedAnalysis)
            eventPublisher.publishEvent(VacancyAnalyzedEvent(this, vacancy, savedAnalysis))
            return savedAnalysis
        }

        // Загружаем резюме
        val resume = resumeService.loadResume()
        val resumeStructure = resumeService.getResumeStructure(resume)
        log.debug("📄 [Ollama] Loaded resume for analysis (skills: ${resumeStructure?.skills?.size ?: 0})")

        // Формируем промпт для анализа
        val analysisPrompt = buildAnalysisPrompt(vacancy, resume, resumeStructure)
        log.debug("📝 [Ollama] Analysis prompt prepared (length: ${analysisPrompt.length} chars)")

        // Анализируем через LLM с использованием Circuit Breaker и Retry
        log.info("🔄 [Ollama] Sending analysis request to Ollama...")
        val analysisStartTime = System.currentTimeMillis()
        val analysisResponse = try {
            // Используем Circuit Breaker и Retry для защиты от сбоев
            ollamaRetry.executeSuspendFunction {
                ollamaCircuitBreaker.executeSuspendFunction {
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
                }
            }
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.error("❌ [Ollama] Circuit Breaker is OPEN for vacancy ${vacancy.id}: ${e.message}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
                e,
            )
        } catch (e: Exception) {
            log.error("❌ [Ollama] Failed to analyze vacancy ${vacancy.id} via Ollama after retries: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to connect to Ollama service for vacancy analysis after retries: ${e.message}",
                e,
            )
        }
        val analysisDuration = System.currentTimeMillis() - analysisStartTime
        metricsService.recordVacancyAnalysisTime(analysisDuration)
        // Обновляем среднее время обработки
        analysisTimeService.updateAverageTime(analysisDuration)
        log.info("✅ [Ollama] Received analysis response from Ollama (took ${analysisDuration}ms, response length: ${analysisResponse.length} chars)")

        // Парсим ответ
        val analysisResult = parseAnalysisResponse(analysisResponse, vacancy.id)
        val extractedSkills = analysisResult.extractSkills()
        log.debug("📊 [Ollama] Parsed analysis result: skills=${extractedSkills.size}, relevanceScore=${analysisResult.relevanceScore}")

        // Валидируем результат анализа
        val validatedResult = validateAnalysisResult(analysisResult)

        // Определяем релевантность на основе relevance_score
        val isRelevant = validatedResult.isRelevantResult(minRelevanceScore)
        val validatedSkills = validatedResult.extractSkills()
        log.info("📊 [Ollama] Analysis result for '${vacancy.name}': isRelevant=$isRelevant, relevanceScore=${String.format("%.2f", validatedResult.relevanceScore * 100)}%, skills=${validatedSkills.size}")

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
        log.info("💾 [Ollama] ✅ Saved analysis to database for vacancy ${vacancy.id} (isRelevant=$isRelevant, score=${String.format("%.2f", savedAnalysis.relevanceScore * 100)}%)")

        // Сохраняем навыки в БД, если вакансия релевантна (relevance_score >= minRelevanceScore)
        if (isRelevant && validatedSkills.isNotEmpty()) {
            try {
                saveSkillsFromAnalysis(vacancy, validatedSkills)
                log.info("💾 [Ollama] ✅ Saved ${validatedSkills.size} skills to database for vacancy ${vacancy.id}")
            } catch (e: Exception) {
                log.error("❌ [Ollama] Failed to save skills for vacancy ${vacancy.id}: ${e.message}", e)
                // Не прерываем обработку из-за ошибки сохранения навыков
            }
        } else {
            log.debug("⏭️ [Ollama] Skipping skill extraction for vacancy ${vacancy.id} (isRelevant=$isRelevant, skills=${validatedSkills.size})")
        }

        // Обновляем метрики
        metricsService.incrementVacanciesAnalyzed()
        if (savedAnalysis.isRelevant) {
            metricsService.incrementVacanciesRelevant()
        } else {
            metricsService.incrementVacanciesSkipped()
        }

        // Публикуем событие анализа вакансии
        eventPublisher.publishEvent(VacancyAnalyzedEvent(this, vacancy, savedAnalysis))

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
                log.warn("Failed to parse JSON after sanitization for vacancy $vacancyId, trying alternative parsing. Error: ${e.message}")
                val alternativeJson = sanitizeJsonStringAlternative(jsonString)
                try {
                    objectMapper.readValue(alternativeJson, AnalysisResult::class.java)
                } catch (e2: Exception) {
                    log.error("Failed to parse JSON even after alternative sanitization for vacancy $vacancyId. Original error: ${e.message}, Alternative error: ${e2.message}")
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
            log.warn("Found ${jsonObjects.size} JSON objects in response for vacancy $vacancyId, will try to parse each")
        }

        // Пытаемся распарсить каждый JSON объект до первого успешного
        for ((start, end) in jsonObjects) {
            val jsonCandidate = response.substring(start, end)
            try {
                // Пробуем распарсить (без sanitize сначала, чтобы проверить валидность)
                objectMapper.readTree(jsonCandidate)
                log.debug("Successfully extracted JSON object (start: $start, end: $end, length: ${jsonCandidate.length})")
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
        require(result.relevanceScore in AppConstants.Validation.RELEVANCE_SCORE_MIN..AppConstants.Validation.RELEVANCE_SCORE_MAX) {
            "Relevance score must be between ${AppConstants.Validation.RELEVANCE_SCORE_MIN} and ${AppConstants.Validation.RELEVANCE_SCORE_MAX}, got: ${result.relevanceScore}"
        }

        // Получаем навыки из нового или старого формата
        val allSkills = result.extractSkills()

        // Валидируем навыки: минимум 3, максимум 20
        val validatedSkills = allSkills
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        if (validatedSkills.size < 3 && allSkills.isNotEmpty()) {
            log.warn("⚠️ [Ollama] Only ${validatedSkills.size} valid skills extracted (minimum 3 expected)")
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
            log.debug("⏭️ [Ollama] No skills to save for vacancy ${vacancy.id}")
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
