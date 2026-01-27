package com.hhassistant.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class VacancyAnalysisService(
    private val ollamaClient: OllamaClient,
    private val resumeService: ResumeService,
    private val repository: VacancyAnalysisRepository,
    private val objectMapper: ObjectMapper,
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

        log.info("Analyzing vacancy: ${vacancy.id} - ${vacancy.name}")

        // Загружаем резюме
        val resume = resumeService.loadResume()
        val resumeStructure = resumeService.getResumeStructure(resume)

        // Формируем промпт для анализа
        val analysisPrompt = buildAnalysisPrompt(vacancy, resume, resumeStructure)

        // Анализируем через LLM
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
            log.error("Failed to analyze vacancy ${vacancy.id} via Ollama: ${e.message}", e)
            throw OllamaException.ConnectionException(
                "Failed to connect to Ollama service for vacancy analysis: ${e.message}",
                e,
            )
        }

        // Парсим ответ
        val analysisResult = parseAnalysisResponse(analysisResponse, vacancy.id)

        // Валидируем результат анализа
        val validatedResult = validateAnalysisResult(analysisResult)

        // Генерируем сопроводительное письмо для релевантных вакансий
        val coverLetter = if (validatedResult.isRelevant && validatedResult.relevanceScore >= minRelevanceScore) {
            try {
                generateCoverLetter(vacancy, resume, resumeStructure, validatedResult)
            } catch (e: Exception) {
                log.warn("Failed to generate cover letter for vacancy ${vacancy.id}: ${e.message}", e)
                // Не прерываем процесс, просто не генерируем письмо
                null
            }
        } else {
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
        )

        return repository.save(analysis)
    }

    private fun buildSystemPrompt(): String {
        return """
            Ты - эксперт по подбору персонала. Твоя задача - проанализировать вакансию и резюме кандидата, 
            определить релевантность вакансии для кандидата и предоставить структурированный ответ в формате JSON.
            
            Формат ответа:
            {
                "is_relevant": true/false,
                "relevance_score": 0.0-1.0,
                "reasoning": "Обоснование решения на русском языке",
                "matched_skills": ["навык1", "навык2", ...]
            }
            
            Критерии релевантности:
            - Совпадение ключевых навыков (вес: 40%)
            - Соответствие опыта работы требованиям (вес: 30%)
            - Соответствие желаемой позиции (вес: 20%)
            - Соответствие зарплатных ожиданий (вес: 10%)
            
            Отвечай ТОЛЬКО валидным JSON, без дополнительных комментариев.
        """.trimIndent()
    }

    private fun buildAnalysisPrompt(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("ВАКАНСИЯ:")
        sb.appendLine("Название: ${vacancy.name}")
        sb.appendLine("Работодатель: ${vacancy.employer}")
        sb.appendLine("Зарплата: ${vacancy.salary ?: "Не указана"}")
        sb.appendLine("Регион: ${vacancy.area}")
        sb.appendLine("Требуемый опыт: ${vacancy.experience ?: "Не указан"}")
        sb.appendLine()
        sb.appendLine("Описание вакансии:")
        sb.appendLine(vacancy.description ?: "Описание отсутствует")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("РЕЗЮМЕ КАНДИДАТА:")
        if (resumeStructure != null) {
            sb.appendLine("Навыки: ${resumeStructure.skills.joinToString(", ")}")
            resumeStructure.desiredPosition?.let {
                sb.appendLine("Желаемая позиция: $it")
            }
            resumeStructure.desiredSalary?.let {
                sb.appendLine("Желаемая зарплата: от $it руб")
            }
            resumeStructure.summary?.let {
                sb.appendLine("О себе: $it")
            }
        } else {
            sb.appendLine("Полный текст резюме:")
            sb.appendLine(resume.rawText)
        }

        sb.appendLine()
        sb.appendLine("Проанализируй релевантность вакансии для кандидата и верни JSON ответ.")

        return sb.toString()
    }

    private fun parseAnalysisResponse(response: String, vacancyId: String): AnalysisResult {
        return try {
            // Пытаемся извлечь JSON из ответа (на случай, если LLM добавит текст до/после JSON)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

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

    private suspend fun generateCoverLetter(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: com.hhassistant.domain.model.ResumeStructure?,
        analysisResult: AnalysisResult,
    ): String {
        log.info("Generating cover letter for vacancy: ${vacancy.id}")

        val coverLetterPrompt = buildCoverLetterPrompt(vacancy, resume, resumeStructure, analysisResult)

        return try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(
                        role = "system",
                        content = """
                            Ты - эксперт по написанию сопроводительных писем. Напиши профессиональное, 
                            краткое (до 200 слов) сопроводительное письмо на русском языке для отклика на вакансию.
                            
                            Письмо должно:
                            - Быть персонализированным под конкретную вакансию
                            - Подчеркивать релевантный опыт и навыки
                            - Быть профессиональным, но не шаблонным
                            - Показывать заинтересованность в позиции
                            
                            Начни с обращения к работодателю, затем кратко представься и объясни, 
                            почему ты подходишь для этой позиции.
                        """.trimIndent(),
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
        val sb = StringBuilder()

        sb.appendLine("Напиши сопроводительное письмо для следующей вакансии:")
        sb.appendLine()
        sb.appendLine("Вакансия: ${vacancy.name}")
        sb.appendLine("Работодатель: ${vacancy.employer}")
        sb.appendLine("Описание: ${vacancy.description?.take(500) ?: "Не указано"}")
        sb.appendLine()
        sb.appendLine("Релевантные навыки кандидата: ${analysisResult.matchedSkills.joinToString(", ")}")
        if (resumeStructure != null) {
            resumeStructure.summary?.let {
                sb.appendLine("О кандидате: $it")
            }
        }

        return sb.toString()
    }

    /**
     * Валидирует результат анализа от LLM.
     *
     * @param result Результат анализа для валидации
     * @return Валидированный результат
     * @throws IllegalArgumentException если relevanceScore вне допустимого диапазона
     */
    private fun validateAnalysisResult(result: AnalysisResult): AnalysisResult {
        require(result.relevanceScore in 0.0..1.0) {
            "Relevance score must be between 0.0 and 1.0, got: ${result.relevanceScore}"
        }
        return result
    }

    private data class AnalysisResult(
        @com.fasterxml.jackson.annotation.JsonProperty("is_relevant")
        val isRelevant: Boolean,
        @com.fasterxml.jackson.annotation.JsonProperty("relevance_score")
        val relevanceScore: Double,
        val reasoning: String,
        @com.fasterxml.jackson.annotation.JsonProperty("matched_skills")
        val matchedSkills: List<String>,
    )
}
