package com.hhassistant.vacancy.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.integration.ollama.OllamaClient
import com.hhassistant.integration.ollama.dto.ChatMessage
import com.hhassistant.config.AppConstants
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.model.ResumeStructure
import com.hhassistant.exception.OllamaException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import com.hhassistant.vacancy.port.VacancyLlmAnalyzer
import org.springframework.stereotype.Service

/**
 * Сервис анализа вакансий через LLM (Ollama).
 * Выделен из VacancyAnalysisService для соблюдения SRP (PROJECT_REVIEW issue 3).
 * Отвечает только за: формирование промптов, вызов LLM, парсинг JSON.
 */
@Service
class VacancyAnalyzerService(
    private val ollamaClient: OllamaClient,
    private val objectMapper: ObjectMapper,
    private val promptConfig: PromptConfig,
    @Qualifier("ollamaCircuitBreaker") private val ollamaCircuitBreaker: CircuitBreaker,
    @Qualifier("ollamaRetry") private val ollamaRetry: Retry,
    @Value("\${app.analysis.min-relevance-score:0.6}") private val minRelevanceScore: Double,
) : VacancyLlmAnalyzer {
    private val log = KotlinLogging.logger {}

    /**
     * Анализирует вакансию через LLM.
     *
     * @param vacancy Вакансия для анализа
     * @param resume Резюме кандидата
     * @param resumeStructure Структура резюме (навыки и т.д.)
     * @return Результат анализа (навыки, relevance_score)
     */
    override suspend fun analyze(
        vacancy: Vacancy,
        resume: Resume,
        resumeStructure: ResumeStructure?,
    ): VacancyLlmAnalyzer.AnalysisResult {
        val analysisPrompt = buildAnalysisPrompt(vacancy, resume, resumeStructure)
        log.debug("🤖 [Ollama] Analysis prompt prepared (length: ${analysisPrompt.length} chars)")

        val analysisResponse = try {
            ollamaRetry.executeSuspendFunction {
                ollamaCircuitBreaker.executeSuspendFunction {
                    ollamaClient.chatForAnalysis(
                        listOf(
                            ChatMessage(role = "system", content = promptConfig.analysisSystem),
                            ChatMessage(role = "user", content = analysisPrompt),
                        ),
                    )
                }
            }
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.error("🤖 [Ollama] Circuit Breaker is OPEN for vacancy ${vacancy.id}: ${e.message}")
            throw OllamaException.ConnectionException(
                "Ollama service is temporarily unavailable (Circuit Breaker is OPEN). Please try again later.",
                e,
            )
        } catch (e: Exception) {
            log.error("🤖 [Ollama] Failed to analyze vacancy ${vacancy.id} via Ollama after retries: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to connect to Ollama service for vacancy analysis after retries: ${e.message}",
                e,
            )
        }

        val parsed = parseAnalysisResponse(analysisResponse, vacancy.id)
        return validateAnalysisResult(parsed)
    }

    private fun buildAnalysisPrompt(
        vacancy: Vacancy,
        resume: Resume,
        resumeStructure: ResumeStructure?,
    ): String {
        val resumeContent = if (resumeStructure != null) {
            buildString {
                appendLine("Навыки: ${resumeStructure.skills.joinToString(", ")}")
                resumeStructure.desiredPosition?.let { appendLine("Позиция: $it") }
                resumeStructure.desiredSalary?.let { appendLine("Зарплата: от $it руб") }
                resumeStructure.summary?.take(200)?.let { appendLine("О себе: $it") }
            }
        } else {
            "Резюме:\n${resume.rawText.take(500)}"
        }

        val description = (vacancy.description ?: "Описание отсутствует").take(1000)

        return promptConfig.analysisTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{salary}", vacancy.salary ?: "Не указана")
            .replace("{experience}", vacancy.experience ?: "Не указан")
            .replace("{description}", description)
            .replace("{resumeContent}", resumeContent)
    }

    private fun parseAnalysisResponse(response: String, vacancyId: String): RawAnalysisResult {
        return try {
            val cleanedResponse = extractJsonFromMarkdown(response)
            val jsonString = extractJsonObject(cleanedResponse, vacancyId)
            val sanitizedJson = sanitizeJsonString(jsonString)
            validateJsonSchema(sanitizedJson, vacancyId)

            val parsed = try {
                objectMapper.readValue(sanitizedJson, RawAnalysisResult::class.java)
            } catch (e: JsonProcessingException) {
                log.warn(
                    "Failed to parse JSON after sanitization for vacancy $vacancyId, trying alternative. Error: ${e.message}",
                )
                val alternativeJson = sanitizeJsonStringAlternative(jsonString)
                try {
                    objectMapper.readValue(alternativeJson, RawAnalysisResult::class.java)
                } catch (e2: Exception) {
                    log.error(
                        "Failed to parse JSON after alternative sanitization for vacancy $vacancyId. " +
                            "Original: ${e.message}, Alternative: ${e2.message}",
                    )
                    throw OllamaException.ParsingException(
                        "Failed to parse JSON response from LLM for vacancy $vacancyId after all attempts: ${e.message}",
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

    private fun validateJsonSchema(jsonString: String, vacancyId: String) {
        try {
            val jsonNode = objectMapper.readTree(jsonString)
            if (!jsonNode.has("relevance_score")) {
                throw OllamaException.ParsingException(
                    "JSON schema validation failed for vacancy $vacancyId: missing 'relevance_score'. " +
                        "JSON: ${jsonString.take(200)}",
                )
            }
            val relevanceScoreNode = jsonNode.get("relevance_score")
            if (!relevanceScoreNode.isNumber) {
                throw OllamaException.ParsingException(
                    "JSON schema validation failed for vacancy $vacancyId: " +
                        "relevance_score must be a number. JSON: ${jsonString.take(200)}",
                )
            }
            log.debug("[JSON Schema] Validation passed for vacancy $vacancyId")
        } catch (e: OllamaException.ParsingException) {
            throw e
        } catch (e: Exception) {
            log.warn("[JSON Schema] Failed to validate JSON for vacancy $vacancyId: ${e.message}")
        }
    }

    private fun extractJsonFromMarkdown(response: String): String {
        val pattern = Regex("```(?:json)?\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        pattern.findAll(response).firstOrNull()?.let {
            return it.groupValues[1].trim()
        }
        return response
    }

    private fun extractJsonObject(response: String, vacancyId: String): String {
        val jsonObjects = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < response.length) {
            if (response[i] == '{') {
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
                if (braceCount == 0) i = jsonEnd else i++
            } else i++
        }

        if (jsonObjects.isEmpty()) {
            throw OllamaException.ParsingException("No valid JSON object found in LLM response for vacancy $vacancyId")
        }

        for ((start, end) in jsonObjects) {
            val candidate = response.substring(start, end)
            try {
                objectMapper.readTree(candidate)
                return candidate
            } catch (_: Exception) { /* try next */ }
        }
        return response.substring(jsonObjects[0].first, jsonObjects[0].second)
    }

    private fun sanitizeJsonString(jsonString: String): String {
        val result = StringBuilder()
        var insideString = false
        var escapeNext = false
        var i = 0
        while (i < jsonString.length) {
            val char = jsonString[i]
            when {
                escapeNext -> { result.append(char); escapeNext = false }
                char == '\\' -> { result.append(char); escapeNext = true }
                char == '"' -> { result.append(char); insideString = !insideString }
                insideString && char in "\n\r\t" -> {
                    result.append(when (char) { '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> char })
                }
                else -> result.append(char)
            }
            i++
        }
        return result.toString()
    }

    private fun sanitizeJsonStringAlternative(jsonString: String): String {
        return jsonString
            .replace(Regex("""("reasoning"\s*:\s*")([^"]*?)(\n|\r|\t)([^"]*?)(")""")) { m ->
                val (k, vBefore, nl, vAfter, q) = m.destructured
                val escaped = when (nl) { "\n" -> "\\n"; "\r" -> "\\r"; "\t" -> "\\t"; else -> nl }
                "$k$vBefore$escaped$vAfter$q"
            }
            .replace(Regex("""("reasoning"\s*:\s*"[^"]*?)(\n|\r|\t)""")) { m ->
                val (before, nl) = m.destructured
                val escaped = when (nl) { "\n" -> "\\n"; "\r" -> "\\r"; "\t" -> "\\t"; else -> nl }
                "$before$escaped"
            }
    }

    private fun validateAnalysisResult(result: RawAnalysisResult): VacancyLlmAnalyzer.AnalysisResult {
        require(
            result.relevanceScore in AppConstants.Validation.RELEVANCE_SCORE_MIN..AppConstants.Validation.RELEVANCE_SCORE_MAX,
        ) {
            "Relevance score must be in [${AppConstants.Validation.RELEVANCE_SCORE_MIN}, ${AppConstants.Validation.RELEVANCE_SCORE_MAX}], got: ${result.relevanceScore}"
        }

        val allSkills = result.extractSkills()
        val validatedSkills = allSkills.filter { it.isNotBlank() }.distinct().take(20)
        val isRelevant = result.isRelevant ?: (result.relevanceScore >= minRelevanceScore)

        return VacancyLlmAnalyzer.AnalysisResult(
            skills = validatedSkills,
            relevanceScore = result.relevanceScore,
            isRelevant = isRelevant,
            reasoning = result.reasoning,
        )
    }

    /**
     * Сырой результат парсинга JSON (поддерживает оба формата).
     */
    data class RawAnalysisResult(
        @com.fasterxml.jackson.annotation.JsonProperty("skills") val skills: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("matched_skills") val matchedSkills: List<String>? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") val relevanceScore: Double,
        @com.fasterxml.jackson.annotation.JsonProperty("is_relevant") val isRelevant: Boolean? = null,
        val reasoning: String? = null,
    ) {
        fun extractSkills(): List<String> = skills ?: matchedSkills ?: emptyList()
    }
}
