package com.hhassistant.service

import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.config.AppConstants
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.model.ResumeStructure
import com.hhassistant.exception.OllamaException
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Сервис для генерации сопроводительных писем
 * 
 * Отвечает только за генерацию письма на основе вакансии, резюме и результата анализа.
 * Не зависит от других сервисов обработки вакансий, что позволяет избежать циклических зависимостей.
 */
@Service
class CoverLetterGenerationService(
    private val ollamaClient: OllamaClient,
    private val promptConfig: PromptConfig,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Генерирует сопроводительное письмо (одна попытка)
     * 
     * @param vacancy Вакансия
     * @param resume Резюме
     * @param resumeStructure Структурированные данные резюме
     * @param analysisResult Результат анализа вакансии
     * @return Сгенерированное письмо
     * @throws OllamaException если не удалось сгенерировать письмо
     */
    suspend fun generateCoverLetter(
        vacancy: Vacancy,
        resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: ResumeStructure?,
        analysisResult: VacancyAnalysisService.AnalysisResult,
    ): String {
        log.debug("🔄 [CoverLetterGeneration] Generating cover letter for vacancy: ${vacancy.id}")

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

    /**
     * Строит промпт для генерации сопроводительного письма
     */
    private fun buildCoverLetterPrompt(
        vacancy: Vacancy,
        @Suppress("UNUSED_PARAMETER") resume: com.hhassistant.domain.entity.Resume,
        resumeStructure: ResumeStructure?,
        analysisResult: VacancyAnalysisService.AnalysisResult,
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
}

