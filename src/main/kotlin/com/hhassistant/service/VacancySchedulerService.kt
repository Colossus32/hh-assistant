package com.hhassistant.service

import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
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
    private val vacancyFetchService: VacancyFetchService,
    private val vacancyService: VacancyService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val vacancyStatusService: VacancyStatusService,
    private val notificationService: NotificationService,
    private val resumeService: ResumeService,
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
        // Проверяем наличие резюме
        checkResumeAndNotify()
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
        logCycleStart()

        runBlocking {
            try {
                // Получаем вакансии через VacancyFetchService (публикует VacancyFetchedEvent)
                val fetchResult = vacancyFetchService.fetchAndSaveNewVacancies()
                sendStatusUpdate(VacancyService.FetchResult(fetchResult.vacancies, fetchResult.searchKeywords))

                val vacanciesToAnalyze = getVacanciesForAnalysis()
                if (vacanciesToAnalyze.isEmpty()) {
                    log.info("ℹ️ [Scheduler] No vacancies to analyze, cycle completed")
                    return@runBlocking
                }

                // Анализируем вакансии (VacancyAnalysisService публикует VacancyAnalyzedEvent)
                val analysisResults = analyzeVacancies(vacanciesToAnalyze)
                logCycleSummary(cycleStartTime, fetchResult.vacancies.size, analysisResults)
            } catch (e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
                handleUnauthorizedError(e)
            } catch (e: Exception) {
                handleGeneralError(e)
            }
        }
    }

    /**
     * Загружает новые вакансии из HH.ru API
     */
    private suspend fun fetchNewVacancies(): VacancyService.FetchResult {
        log.info("📥 [Scheduler] Step 1: Fetching new vacancies from HH.ru API...")
        val fetchResult = vacancyService.fetchAndSaveNewVacancies()
        log.info("✅ [Scheduler] Step 1 completed: Fetched ${fetchResult.vacancies.size} new vacancies from HH.ru")
        return fetchResult
    }

    /**
     * Отправляет обновление статуса в Telegram
     */
    private fun sendStatusUpdate(fetchResult: VacancyService.FetchResult) {
        val hhApiStatus = buildStatusMessage(fetchResult)
        notificationService.sendStatusUpdate(hhApiStatus, fetchResult.searchKeywords, fetchResult.vacancies.size)
    }

    /**
     * Формирует сообщение о статусе HH.ru API
     */
    private fun buildStatusMessage(fetchResult: VacancyService.FetchResult): String {
        return when {
            fetchResult.vacancies.isNotEmpty() -> "✅ UP (найдено ${fetchResult.vacancies.size} вакансий)"
            fetchResult.searchKeywords.isNotEmpty() -> "✅ UP (запрос выполнен, новых вакансий не найдено)"
            else -> "⚠️ Проверка выполнена, но вакансии не найдены"
        }
    }

    /**
     * Получает вакансии для анализа
     */
    private fun getVacanciesForAnalysis(): List<Vacancy> {
        log.info("🔍 [Scheduler] Step 2: Getting vacancies for analysis...")
        val vacanciesToAnalyze = vacancyService.getNewVacanciesForAnalysis()
        log.info("✅ [Scheduler] Step 2 completed: Found ${vacanciesToAnalyze.size} vacancies to analyze")
        return vacanciesToAnalyze
    }

    /**
     * Анализирует вакансии параллельно
     */
    private suspend fun analyzeVacancies(vacanciesToAnalyze: List<Vacancy>): List<VacancyAnalysis?> {
        log.info("🤖 [Scheduler] Step 3: Analyzing ${vacanciesToAnalyze.size} vacancies via Ollama (max concurrent: $maxConcurrentRequests)...")
        val analysisResults = coroutineScope {
            vacanciesToAnalyze.map { vacancy ->
                async {
                    processVacancy(vacancy)
                }
            }.awaitAll()
        }
        log.info("✅ [Scheduler] Step 3 completed: Analyzed ${analysisResults.count { it != null }} vacancies")
        return analysisResults
    }

    /**
     * Логирует начало цикла проверки
     */
    private fun logCycleStart() {
        log.info("🚀 [Scheduler] ========================================")
        log.info("🚀 [Scheduler] Starting scheduled vacancy check cycle")
        log.info("🚀 [Scheduler] ========================================")
    }

    /**
     * Логирует итоги цикла проверки
     */
    private fun logCycleSummary(
        cycleStartTime: Long,
        newVacanciesCount: Int,
        analysisResults: List<VacancyAnalysis?>,
    ) {
        val analyzedCount = analysisResults.count { it != null }
        val relevantCount = analysisResults.count { it?.isRelevant == true }
        val sentToTelegramCount = analysisResults.count { it?.isRelevant == true }
        val cycleDuration = System.currentTimeMillis() - cycleStartTime

        log.info("📊 [Scheduler] ========================================")
        log.info("📊 [Scheduler] Cycle Summary:")
        log.info("📊 [Scheduler]   - New vacancies fetched: $newVacanciesCount")
        log.info("📊 [Scheduler]   - Vacancies analyzed: $analyzedCount")
        log.info("📊 [Scheduler]   - Relevant vacancies: $relevantCount")
        log.info("📊 [Scheduler]   - Sent to Telegram: $sentToTelegramCount")
        log.info("📊 [Scheduler]   - Total cycle time: ${cycleDuration}ms")
        log.info("📊 [Scheduler] ========================================")
    }

    /**
     * Обрабатывает ошибку UnauthorizedException
     */
    private fun handleUnauthorizedError(e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
        log.error("❌ [Scheduler] HH.ru API unauthorized/forbidden error: ${e.message}", e)
        notificationService.sendTokenExpiredAlert(
            e.message ?: "Unauthorized or Forbidden access to HH.ru API. " +
                "Token may be invalid, expired, or lacks required permissions.",
        )
        notificationService.sendStatusUpdate(
            "❌ ERROR: Token invalid or insufficient permissions",
            emptyList(),
            0,
        )
    }

    /**
     * Обрабатывает общие ошибки
     */
    private fun handleGeneralError(e: Exception) {
        log.error("❌ [Scheduler] Error during scheduled vacancy check: ${e.message}", e)
        notificationService.sendStatusUpdate(
            "❌ ERROR: ${e.message?.take(AppConstants.TextLimits.ERROR_MESSAGE_MAX_LENGTH) ?: "Unknown error"}",
            emptyList(),
            0,
        )
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

                // Обновляем статус вакансии через VacancyStatusService (публикует VacancyStatusChangedEvent)
                val newStatus = if (analysis.isRelevant) VacancyStatus.ANALYZED else VacancyStatus.SKIPPED
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
                log.debug("📝 [Scheduler] Updated vacancy ${vacancy.id} status to: $newStatus")

                // Обработка релевантных вакансий теперь происходит через события:
                // - VacancyAnalyzedEvent публикуется в VacancyAnalysisService
                // - CoverLetterQueueService обрабатывает очередь и публикует VacancyReadyForTelegramEvent
                // - VacancyNotificationService слушает VacancyReadyForTelegramEvent и отправляет в Telegram
                if (analysis.isRelevant) {
                    log.info("📱 [Scheduler] Vacancy ${vacancy.id} is relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%)")
                    log.info("ℹ️ [Scheduler] Vacancy will be processed by event-driven pipeline (cover letter queue -> notification service)")
                } else {
                    log.debug("ℹ️ [Scheduler] Vacancy ${vacancy.id} is not relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%), skipping")
                }

                analysis
            }
        } catch (e: OllamaException) {
            log.error("Ollama error analyzing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как пропущенную, чтобы не анализировать снова (Rich Domain Model)
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
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

    /**
     * Проверяет наличие резюме и отправляет уведомление, если его нет
     */
    private fun checkResumeAndNotify() {
        runBlocking {
            try {
                val hasResume = resumeService.hasActiveResume()
                if (!hasResume) {
                    log.warn("⚠️ [Scheduler] No active resume found. Sending notification to user.")
                    notificationService.sendMessage(
                        """
                        ⚠️ <b>Резюме не найдено!</b>
                        
                        Для начала работы с HH Assistant необходимо загрузить резюме.
                        
                        <b>Как загрузить резюме:</b>
                        1. Отправьте PDF файл с резюме в этот чат
                        2. Дождитесь подтверждения обработки
                        3. После этого вы начнете получать подходящие вакансии
                        
                        <i>Примечание: Резюме должно быть в формате PDF</i>
                        """.trimIndent(),
                    )
                } else {
                    log.info("✅ [Scheduler] Active resume found, no notification needed")
                }
            } catch (e: Exception) {
                log.error("❌ [Scheduler] Error checking resume: ${e.message}", e)
            }
        }
    }
}
