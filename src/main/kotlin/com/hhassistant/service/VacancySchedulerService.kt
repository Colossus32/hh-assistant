package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.TelegramException
import com.hhassistant.exception.VacancyProcessingException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class VacancySchedulerService(
    private val vacancyService: VacancyService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val telegramClient: TelegramClient,
    private val notificationService: NotificationService,
    private val resumeService: ResumeService, // Добавляем для предзагрузки резюме
    @Value("\${app.dry-run:false}") private val dryRun: Boolean,
    @Value("\${app.analysis.max-concurrent-requests:3}") private val maxConcurrentRequests: Int,
) {
    private val log = KotlinLogging.logger {}
    private val analysisSemaphore = Semaphore(maxConcurrentRequests)

    /**
     * Запускает проверку вакансий сразу после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("🚀 [Scheduler] Application ready, preloading resume and sending startup notification...")
        
        // Предзагружаем резюме в память при старте
        runBlocking {
            try {
                resumeService.preloadResume()
            } catch (e: Exception) {
                log.error("❌ [Scheduler] Failed to preload resume: ${e.message}", e)
                // Не прерываем старт приложения, если резюме не загрузилось
            }
        }
        
        // Отправляем уведомление о старте
        notificationService.sendStartupNotification()
        
        // Запускаем первую проверку сразу
        if (!dryRun) {
            log.info("🚀 [Scheduler] Running initial vacancy check on startup...")
            checkNewVacancies()
        } else {
            log.info("ℹ️ [Scheduler] Dry-run mode enabled, skipping initial check")
        }
    }

    /**
     * Периодически проверяет новые вакансии, анализирует их и отправляет релевантные в Telegram.
     * Запускается по расписанию из application.yml (app.schedule.vacancy-check).
     */
    @Scheduled(cron = "\${app.schedule.vacancy-check:0 */15 * * * *}")
    fun checkNewVacancies() {
        if (dryRun) {
            log.info("ℹ️ [Scheduler] Dry-run mode enabled, skipping vacancy check")
            return
        }

        val cycleStartTime = System.currentTimeMillis()
        log.info("🚀 [Scheduler] ========================================")
        log.info("🚀 [Scheduler] Starting scheduled vacancy check cycle")
        log.info("🚀 [Scheduler] ========================================")

        runBlocking {
            try {
                // 1. Загружаем новые вакансии из HH.ru
                log.info("📥 [Scheduler] Step 1: Fetching new vacancies from HH.ru API...")
                val fetchResult = vacancyService.fetchAndSaveNewVacancies()
                val newVacancies = fetchResult.vacancies
                val searchKeywords = fetchResult.searchKeywords
                log.info("✅ [Scheduler] Step 1 completed: Fetched ${newVacancies.size} new vacancies from HH.ru")
                
                // Отправляем обновление статуса в Telegram
                val hhApiStatus = if (newVacancies.isNotEmpty()) {
                    "✅ UP (найдено ${newVacancies.size} вакансий)"
                } else if (searchKeywords.isNotEmpty()) {
                    "✅ UP (запрос выполнен, новых вакансий не найдено)"
                } else {
                    "⚠️ Проверка выполнена, но вакансии не найдены"
                }
                notificationService.sendStatusUpdate(hhApiStatus, searchKeywords, newVacancies.size)

                // 2. Получаем все новые вакансии для анализа (включая ранее загруженные)
                log.info("🔍 [Scheduler] Step 2: Getting vacancies for analysis...")
                val vacanciesToAnalyze = vacancyService.getNewVacanciesForAnalysis()
                log.info("✅ [Scheduler] Step 2 completed: Found ${vacanciesToAnalyze.size} vacancies to analyze")
                
                // Если не было новых вакансий, но есть ключевые слова - значит запрос прошел успешно
                if (newVacancies.isEmpty() && searchKeywords.isNotEmpty()) {
                    log.info("ℹ️ [Scheduler] No new vacancies found, but search was successful (keywords: ${searchKeywords.joinToString(", ") { "'$it'" }})")
                }

                if (vacanciesToAnalyze.isEmpty()) {
                    log.info("ℹ️ [Scheduler] No vacancies to analyze, cycle completed")
                    return@runBlocking
                }

                // 3. Анализируем вакансии параллельно с ограничением количества одновременных запросов
                log.info("🤖 [Scheduler] Step 3: Analyzing ${vacanciesToAnalyze.size} vacancies via Ollama (max concurrent: $maxConcurrentRequests)...")
                val analysisResults = coroutineScope {
                    vacanciesToAnalyze.map { vacancy ->
                        async {
                            processVacancy(vacancy)
                        }
                    }.awaitAll()
                }

                val analyzedCount = analysisResults.count { it != null }
                val relevantCount = analysisResults.count { it?.isRelevant == true }
                val sentToTelegramCount = analysisResults.count { it?.isRelevant == true }

                val cycleDuration = System.currentTimeMillis() - cycleStartTime
                log.info("✅ [Scheduler] Step 3 completed: Analyzed $analyzedCount vacancies")
                log.info("📊 [Scheduler] ========================================")
                log.info("📊 [Scheduler] Cycle Summary:")
                log.info("📊 [Scheduler]   - New vacancies fetched: ${newVacancies.size}")
                log.info("📊 [Scheduler]   - Vacancies analyzed: $analyzedCount")
                log.info("📊 [Scheduler]   - Relevant vacancies: $relevantCount")
                log.info("📊 [Scheduler]   - Sent to Telegram: $sentToTelegramCount")
                log.info("📊 [Scheduler]   - Total cycle time: ${cycleDuration}ms")
                log.info("📊 [Scheduler] ========================================")
            } catch (e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
                log.error("❌ [Scheduler] HH.ru API unauthorized/forbidden error: ${e.message}", e)
                // Отправляем алерт в Telegram об истечении токена или проблеме с правами
                notificationService.sendTokenExpiredAlert(
                    e.message ?: "Unauthorized or Forbidden access to HH.ru API. " +
                        "Token may be invalid, expired, or lacks required permissions."
                )
                // Отправляем обновление статуса с ошибкой
                notificationService.sendStatusUpdate(
                    "❌ ERROR: Token invalid or insufficient permissions",
                    emptyList(),
                    0
                )
            } catch (e: Exception) {
                log.error("❌ [Scheduler] Error during scheduled vacancy check: ${e.message}", e)
                // Отправляем обновление статуса с ошибкой
                notificationService.sendStatusUpdate(
                    "❌ ERROR: ${e.message?.take(100) ?: "Unknown error"}",
                    emptyList(),
                    0
                )
            }
        }
    }

    /**
     * Обрабатывает одну вакансию: анализирует, обновляет статус и отправляет в Telegram при необходимости.
     * Использует semaphore для ограничения количества одновременных запросов к LLM.
     *
     * @param vacancy Вакансия для обработки
     * @return Результат анализа или null, если обработка не удалась
     */
    private suspend fun processVacancy(vacancy: Vacancy): VacancyAnalysis? {
        log.debug("🔄 [Scheduler] Processing vacancy: ${vacancy.id} - '${vacancy.name}'")
        return try {
            // Используем semaphore для ограничения параллельных запросов к LLM
            analysisSemaphore.withPermit {
                val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

                // Обновляем статус вакансии
                val newStatus = if (analysis.isRelevant) VacancyStatus.ANALYZED else VacancyStatus.SKIPPED
                vacancyService.updateVacancyStatus(vacancy, newStatus)
                log.debug("📝 [Scheduler] Updated vacancy ${vacancy.id} status to: $newStatus")

                // Отправляем релевантные вакансии в Telegram
                if (analysis.isRelevant) {
                    log.info("📱 [Scheduler] Vacancy ${vacancy.id} is relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%), sending to Telegram...")
                    try {
                        sendVacancyToTelegram(vacancy, analysis)
                        vacancyService.updateVacancyStatus(vacancy, VacancyStatus.SENT_TO_USER)
                        log.info("✅ [Scheduler] Successfully sent vacancy ${vacancy.id} to Telegram and updated status to SENT_TO_USER")
                    } catch (e: TelegramException.RateLimitException) {
                        log.warn("⚠️ [Scheduler] Rate limit exceeded for Telegram, skipping vacancy ${vacancy.id} (will retry next cycle)")
                        // Не обновляем статус, попробуем отправить в следующий раз
                    } catch (e: TelegramException) {
                        log.error("❌ [Scheduler] Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
                        // Вакансия уже проанализирована, но не отправлена
                    }
                } else {
                    log.debug("ℹ️ [Scheduler] Vacancy ${vacancy.id} is not relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%), skipping Telegram")
                }

                analysis
            }
        } catch (e: OllamaException) {
            log.error("Ollama error analyzing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как пропущенную, чтобы не анализировать снова
            try {
                vacancyService.updateVacancyStatus(vacancy, VacancyStatus.SKIPPED)
            } catch (updateError: Exception) {
                log.error("Failed to update status for vacancy ${vacancy.id} after Ollama error", updateError)
            }
            null
        } catch (e: VacancyProcessingException) {
            log.error("Error processing vacancy ${vacancy.id}: ${e.message}", e)
            null
        } catch (e: Exception) {
            log.error("Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
            null
        }
    }

    private suspend fun sendVacancyToTelegram(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ) {
        log.info("📱 [Scheduler] Preparing Telegram message for vacancy: ${vacancy.id} - '${vacancy.name}'")
        val message = buildTelegramMessage(vacancy, analysis)
        log.debug("📱 [Scheduler] Telegram message prepared (length: ${message.length} chars)")

        try {
            val sent = telegramClient.sendMessage(message)
            if (sent) {
                log.info("✅ [Scheduler] Successfully sent vacancy ${vacancy.id} ('${vacancy.name}') to Telegram")
            } else {
                log.warn("⚠️ [Scheduler] Failed to send vacancy ${vacancy.id} to Telegram (returned false)")
            }
        } catch (e: TelegramException) {
            log.error("❌ [Scheduler] Telegram exception sending vacancy ${vacancy.id}: ${e.message}", e)
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
