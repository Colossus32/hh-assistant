package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.TelegramException
import com.hhassistant.exception.VacancyProcessingException
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class VacancySchedulerService(
    private val vacancyService: VacancyService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val telegramClient: TelegramClient,
    @Value("\${app.dry-run:false}") private val dryRun: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Периодически проверяет новые вакансии, анализирует их и отправляет релевантные в Telegram.
     * Запускается по расписанию из application.yml (app.schedule.vacancy-check).
     */
    @Scheduled(cron = "\${app.schedule.vacancy-check:0 */15 * * * *}")
    fun checkNewVacancies() {
        if (dryRun) {
            log.info("Dry-run mode enabled, skipping vacancy check")
            return
        }

        log.info("Starting scheduled vacancy check")

        runBlocking {
            try {
                // 1. Загружаем новые вакансии из HH.ru
                val newVacancies = vacancyService.fetchAndSaveNewVacancies()
                log.info("Fetched ${newVacancies.size} new vacancies from HH.ru")

                // 2. Получаем все новые вакансии для анализа (включая ранее загруженные)
                val vacanciesToAnalyze = vacancyService.getNewVacanciesForAnalysis()
                log.info("Found ${vacanciesToAnalyze.size} vacancies to analyze")

                // 3. Анализируем каждую вакансию
                var analyzedCount = 0
                var relevantCount = 0

                for (vacancy in vacanciesToAnalyze) {
                    try {
                        val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

                        // Обновляем статус вакансии
                        vacancyService.updateVacancyStatus(
                            vacancy,
                            if (analysis.isRelevant) VacancyStatus.ANALYZED else VacancyStatus.SKIPPED,
                        )

                        analyzedCount++

                        // 4. Отправляем релевантные вакансии в Telegram
                        if (analysis.isRelevant) {
                            relevantCount++
                            try {
                                sendVacancyToTelegram(vacancy, analysis)
                                vacancyService.updateVacancyStatus(vacancy, VacancyStatus.SENT_TO_USER)
                            } catch (e: TelegramException.RateLimitException) {
                                log.warn("Rate limit exceeded for Telegram, skipping vacancy ${vacancy.id}")
                                // Не обновляем статус, попробуем отправить в следующий раз
                            } catch (e: TelegramException) {
                                log.error("Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
                                // Вакансия уже проанализирована, но не отправлена
                            }
                        }
                    } catch (e: OllamaException) {
                        log.error("Ollama error analyzing vacancy ${vacancy.id}: ${e.message}", e)
                        // Помечаем как пропущенную, чтобы не анализировать снова
                        try {
                            vacancyService.updateVacancyStatus(vacancy, VacancyStatus.SKIPPED)
                        } catch (updateError: Exception) {
                            log.error("Failed to update status for vacancy ${vacancy.id} after Ollama error", updateError)
                        }
                    } catch (e: VacancyProcessingException) {
                        log.error("Error processing vacancy ${vacancy.id}: ${e.message}", e)
                        // Продолжаем обработку других вакансий
                    } catch (e: Exception) {
                        log.error("Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
                        // Продолжаем обработку других вакансий
                    }
                }

                log.info(
                    "Vacancy check completed: analyzed $analyzedCount, " +
                        "relevant $relevantCount, sent to Telegram $relevantCount",
                )
            } catch (e: Exception) {
                log.error("Error during scheduled vacancy check: ${e.message}", e)
            }
        }
    }

    private suspend fun sendVacancyToTelegram(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ) {
        val message = buildTelegramMessage(vacancy, analysis)

        try {
            val sent = telegramClient.sendMessage(message)
            if (sent) {
                log.info("Sent vacancy ${vacancy.id} to Telegram")
            } else {
                log.warn("Failed to send vacancy ${vacancy.id} to Telegram (returned false)")
            }
        } catch (e: TelegramException) {
            log.error("Telegram exception sending vacancy ${vacancy.id}: ${e.message}", e)
            throw e // Пробрасываем для обработки в вызывающем коде
        }
    }

    private fun buildTelegramMessage(
        vacancy: Vacancy,
        analysis: com.hhassistant.domain.entity.VacancyAnalysis,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("🎯 <b>Новая релевантная вакансия!</b>")
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
