package com.hhassistant.service

import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.VacancyProcessingException
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private val metricsService: com.hhassistant.metrics.MetricsService,
    @Value("\${app.dry-run:false}") private val dryRun: Boolean,
    @Value("\${app.analysis.max-concurrent-requests:3}") private val maxConcurrentRequests: Int,
) {
    private val log = KotlinLogging.logger {}
    private val analysisSemaphore = Semaphore(maxConcurrentRequests)

    // CoroutineScope для асинхронной обработки анализа вакансий
    private val schedulerScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error("❌ [Scheduler] Unhandled exception in scheduler coroutine: ${exception.message}", exception)
        }
    )

    /**
     * Запускает проверку вакансий сразу после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        checkResumeAndNotify()
        log.info("[Scheduler] Application ready, preloading resume and sending startup notification...")

        // Запускаем предзагрузку резюме асинхронно
        schedulerScope.launch {
            try {
                resumeService.preloadResume()
            } catch (e: Exception) {
                log.error("[Scheduler] Failed to preload resume: ${e.message}", e)
            }
        }

        notificationService.sendStartupNotification()

        if (!dryRun) {
            log.info("[Scheduler] Running initial vacancy check on startup...")
            checkNewVacancies()
        } else {
            log.debug("[Scheduler] Dry-run mode enabled, skipping initial check")
        }
    }

    /**
     * Периодически проверяет и восстанавливает вакансии со статусом SKIPPED,
     * которые были пропущены из-за Circuit Breaker OPEN.
     * Запускается каждые 5 минут, когда Circuit Breaker закрыт.
     * Обработка выполняется асинхронно, не блокируя поток.
     */
    @Scheduled(cron = "\${app.schedule.skipped-retry:0 */5 * * * *}")
    fun retrySkippedVacancies() {
        if (dryRun) {
            log.debug("[Scheduler] Dry-run mode enabled, skipping skipped vacancies retry")
            return
        }

        val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
        if (circuitBreakerState == "OPEN") {
            log.debug("[Scheduler] Circuit Breaker is still OPEN, skipping retry of skipped vacancies")
            return
        }

        log.info("[Scheduler] Circuit Breaker is $circuitBreakerState, checking for skipped vacancies to retry...")

        // Запускаем обработку асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val skippedVacancies = vacancyService.getSkippedVacanciesForRetry(limit = 10)
                if (skippedVacancies.isEmpty()) {
                    log.debug("[Scheduler] No skipped vacancies to retry")
                    return@launch
                }

                log.info("[Scheduler] Found ${skippedVacancies.size} skipped vacancies to retry, resetting status to NEW...")

                // Сбрасываем статус на NEW для повторной обработки
                skippedVacancies.forEach { vacancy ->
                    try {
                        vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                        log.debug("[Scheduler] Reset vacancy ${vacancy.id} status from SKIPPED to NEW for retry")
                    } catch (e: Exception) {
                        log.error("[Scheduler] Failed to reset status for vacancy ${vacancy.id}: ${e.message}", e)
                    }
                }

                log.info("[Scheduler] Reset ${skippedVacancies.size} vacancies to NEW status, they will be processed in the next cycle")
            } catch (e: Exception) {
                log.error("[Scheduler] Error retrying skipped vacancies: ${e.message}", e)
            }
        }
    }

    /**
     * Периодически проверяет новые вакансии, анализирует их и отправляет релевантные в Telegram.
     * Запускается по расписанию из application.yml (app.schedule.vacancy-check).
     * Обработка выполняется асинхронно, не блокируя поток - команды Telegram будут работать даже во время анализа.
     */
    @Scheduled(cron = "\${app.schedule.vacancy-check:0 */15 * * * *}")
    fun checkNewVacancies() {
        if (dryRun) {
            log.debug("[Scheduler] Dry-run mode enabled, skipping vacancy check")
            return
        }

        val cycleStartTime = System.currentTimeMillis()
        logCycleStart()

        // Запускаем анализ асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val fetchResult = vacancyFetchService.fetchAndSaveNewVacancies()
                sendStatusUpdate(VacancyService.FetchResult(fetchResult.vacancies, fetchResult.searchKeywords))

                val vacanciesToAnalyze = getVacanciesForAnalysis()
                if (vacanciesToAnalyze.isEmpty()) {
                    log.debug("[Scheduler] No vacancies to analyze, cycle completed")
                    return@launch
                }

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
            fetchResult.vacancies.isNotEmpty() -> "✅ UP (found ${fetchResult.vacancies.size} vacancies)"
            fetchResult.searchKeywords.isNotEmpty() -> "✅ UP (request completed, no new vacancies found)"
            else -> "⚠️ Check completed, but no vacancies found"
        }
    }

    private fun getVacanciesForAnalysis(): List<Vacancy> {
        log.debug("[Scheduler] Getting vacancies for analysis...")
        val vacanciesToAnalyze = vacancyService.getNewVacanciesForAnalysis()
        log.debug("[Scheduler] Found ${vacanciesToAnalyze.size} vacancies to analyze")
        return vacanciesToAnalyze
    }

    private suspend fun analyzeVacancies(vacanciesToAnalyze: List<Vacancy>): List<VacancyAnalysis?> {
        log.info("[Scheduler] Analyzing ${vacanciesToAnalyze.size} vacancies via Ollama (max concurrent: $maxConcurrentRequests)...")
        val analysisResults = coroutineScope {
            vacanciesToAnalyze.map { vacancy ->
                async {
                    processVacancy(vacancy)
                }
            }.awaitAll()
        }
        log.info("[Scheduler] Analyzed ${analysisResults.count { it != null }} vacancies")
        return analysisResults
    }

    private fun logCycleStart() {
        log.info("[Scheduler] ========================================")
        log.info("[Scheduler] Starting scheduled vacancy check cycle")
        log.info("[Scheduler] ========================================")
    }

    private fun logCycleSummary(
        cycleStartTime: Long,
        newVacanciesCount: Int,
        analysisResults: List<VacancyAnalysis?>,
    ) {
        val analyzedCount = analysisResults.count { it != null }
        val relevantCount = analysisResults.count { it?.isRelevant == true }
        val sentToTelegramCount = analysisResults.count { it?.isRelevant == true }
        val cycleDuration = System.currentTimeMillis() - cycleStartTime

        log.info("[Scheduler] ========================================")
        log.info("[Scheduler] Cycle Summary:")
        log.info("[Scheduler]   - New vacancies fetched: $newVacanciesCount")
        log.info("[Scheduler]   - Vacancies analyzed: $analyzedCount")
        log.info("[Scheduler]   - Relevant vacancies: $relevantCount")
        log.info("[Scheduler]   - Sent to Telegram: $sentToTelegramCount")
        log.info("[Scheduler]   - Total cycle time: ${cycleDuration}ms")
        log.info("[Scheduler] ========================================")
    }

    private fun handleUnauthorizedError(e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
        log.error("[Scheduler] HH.ru API unauthorized/forbidden error: ${e.message}", e)
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

    private fun handleGeneralError(e: Exception) {
        log.error("[Scheduler] Error during scheduled vacancy check: ${e.message}", e)
        notificationService.sendStatusUpdate(
            "❌ ERROR: ${e.message?.take(AppConstants.TextLimits.ERROR_MESSAGE_MAX_LENGTH) ?: "Unknown error"}",
            emptyList(),
            0,
        )
    }

    /**
     * Обрабатывает одну вакансию: анализирует, обновляет статус и отправляет в Telegram при необходимости.
     * Использует semaphore для ограничения количества одновременных запросов к LLM.
     * Продолжает обработку других вакансий даже при ошибке одной (graceful degradation).
     *
     * @param vacancy Вакансия для обработки
     * @return Результат анализа или null, если обработка не удалась
     */
    private suspend fun processVacancy(vacancy: Vacancy): VacancyAnalysis? {
        log.debug("🔄 [Scheduler] Processing vacancy: ${vacancy.id} - '${vacancy.name}'")
        return try {
            // Проверяем состояние Circuit Breaker перед анализом (до semaphore, чтобы не блокировать поток)
            val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
            if (circuitBreakerState == "OPEN") {
                log.warn("⚠️ [Scheduler] Circuit Breaker is OPEN, skipping vacancy ${vacancy.id} for retry later")
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                return null
            }

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
            // Если это ошибка Circuit Breaker OPEN, мы уже обработали её выше (до semaphore)
            // Здесь обрабатываем только другие ошибки Ollama
            if (e.message?.contains("Circuit Breaker is OPEN") == true) {
                // Это не должно произойти, так как мы проверяем Circuit Breaker до анализа
                // Но на всякий случай обрабатываем
                log.warn("⚠️ [Scheduler] Circuit Breaker is OPEN (caught in exception handler), marking vacancy ${vacancy.id} as SKIPPED")
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                } catch (updateError: Exception) {
                    log.error("❌ [Scheduler] Failed to update status for vacancy ${vacancy.id} after Circuit Breaker error", updateError)
                }
                return null
            }

            log.error("❌ [Scheduler] Ollama error analyzing vacancy ${vacancy.id}: ${e.message}", e)
            // Критическая ошибка после всех retry - помечаем как FAILED
            log.error("❌ [Scheduler] Critical error after retries, marking vacancy ${vacancy.id} as FAILED (dead letter queue)")
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [Scheduler] Failed to update status for vacancy ${vacancy.id} after error", updateError)
            }
            null
        } catch (e: VacancyProcessingException) {
            log.error("❌ [Scheduler] Error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как FAILED для проблемных вакансий
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [Scheduler] Failed to update status for vacancy ${vacancy.id} after processing error", updateError)
            }
            null
        } catch (e: Exception) {
            log.error("❌ [Scheduler] Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как FAILED для неожиданных ошибок
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [Scheduler] Failed to update status for vacancy ${vacancy.id} after unexpected error", updateError)
            }
            null
        }
    }

    /**
     * Проверяет наличие резюме и отправляет уведомление, если его нет
     */
    private fun checkResumeAndNotify() {
        // Запускаем проверку асинхронно
        schedulerScope.launch {
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

    /**
     * Корректное завершение работы при остановке приложения
     */
    @PreDestroy
    fun shutdown() {
        log.info("[Scheduler] Shutting down scheduler service...")
        schedulerScope.coroutineContext.cancel()
    }
}
