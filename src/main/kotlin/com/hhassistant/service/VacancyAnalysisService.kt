package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.repository.VacancyAnalysisRepository
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

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
        val analysisResponse = ollamaClient.chat(
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

        // Парсим ответ
        val analysisResult = parseAnalysisResponse(analysisResponse)

        // Генерируем сопроводительное письмо для релевантных вакансий
        val coverLetter = if (analysisResult.isRelevant && analysisResult.relevanceScore >= minRelevanceScore) {
            generateCoverLetter(vacancy, resume, resumeStructure, analysisResult)
        } else {
            null
        }

        // Сохраняем результат
        val analysis = VacancyAnalysis(
            vacancyId = vacancy.id,
            isRelevant = analysisResult.isRelevant,
            relevanceScore = analysisResult.relevanceScore,
            reasoning = analysisResult.reasoning,
            matchedSkills = objectMapper.writeValueAsString(analysisResult.matchedSkills),
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

    private fun parseAnalysisResponse(response: String): AnalysisResult {
        return try {
            // Пытаемся извлечь JSON из ответа (на случай, если LLM добавит текст до/после JSON)
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = response.substring(jsonStart, jsonEnd)
                val parsed = objectMapper.readValue(jsonString, AnalysisResult::class.java)
                parsed
            } else {
                log.warn("Failed to parse analysis response, using defaults. Response: $response")
                AnalysisResult(
                    isRelevant = false,
                    relevanceScore = 0.0,
                    reasoning = "Не удалось распарсить ответ LLM",
                    matchedSkills = emptyList(),
                )
            }
        } catch (e: Exception) {
            log.error("Error parsing analysis response: ${e.message}", e)
            AnalysisResult(
                isRelevant = false,
                relevanceScore = 0.0,
                reasoning = "Ошибка парсинга ответа: ${e.message}",
                matchedSkills = emptyList(),
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

        return ollamaClient.chat(
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
