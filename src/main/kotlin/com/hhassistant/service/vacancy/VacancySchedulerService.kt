package com.hhassistant.service.vacancy

import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.service.monitoring.OllamaMonitoringService
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.service.skill.SkillExtractionQueueService
import com.hhassistant.service.skill.SkillExtractionService
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
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
    private val skillExtractionService: SkillExtractionService,
    private val vacancyProcessingQueueService: VacancyProcessingQueueService,
    private val skillExtractionQueueService: SkillExtractionQueueService,
    private val vacancyRepository: com.hhassistant.repository.VacancyRepository,
    private val vacancyContentValidator: VacancyContentValidator,
    @Autowired(required = false) private val ollamaMonitoringService: OllamaMonitoringService?,
    @Value("\${app.analysis.max-concurrent-requests:3}") private val maxConcurrentRequests: Int,
) {
    private val log = KotlinLogging.logger {}
    private val analysisSemaphore = Semaphore(maxConcurrentRequests)

    // CoroutineScope для асинхронной обработки анализа вакансий
    private val schedulerScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error(" [Scheduler] Unhandled exception in scheduler coroutine: ${exception.message}", exception)
        },
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

        log.info("[Scheduler] Running initial vacancy check on startup...")
        checkNewVacancies()
    }

    /**
     * Периодически проверяет и восстанавливает вакансии со статусом SKIPPED,
     * которые были пропущены из-за Circuit Breaker OPEN.
     * Запускается каждые 5 минут, когда Circuit Breaker закрыт.
     * Обработка выполняется асинхронно, не блокируя поток.
     */
    @Scheduled(cron = "\${app.schedule.skipped-retry:0 */5 * * * *}")
    fun retrySkippedVacancies() {
        val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
        if (circuitBreakerState == "OPEN") {
            log.debug("[Scheduler] Circuit Breaker is still OPEN, skipping retry of skipped vacancies")
            return
        }

        log.info(
            "[Scheduler] Circuit Breaker is $circuitBreakerState, checking for skipped/failed vacancies to retry (retry window: 48 hours)...",
        )

        // Запускаем обработку асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val skippedVacancies = vacancyService.getSkippedVacanciesForRetry(limit = 10, retryWindowHours = 48)
                if (skippedVacancies.isEmpty()) {
                    log.debug("[Scheduler] No skipped/failed vacancies to retry (within 48 hour window)")
                    return@launch
                }

                log.info(
                    "[Scheduler] Found ${skippedVacancies.size} SKIPPED vacancies to retry " +
                        "within 48 hour window, checking exclusion rules...",
                )

                // Проверяем вакансии на бан-слова перед повторной обработкой
                var validCount = 0
                var deletedCount = 0

                skippedVacancies.forEach { vacancy ->
                    try {
                        // ВАЖНО: Проверяем, не была ли вакансия уже проанализирована
                        // Если анализ существует и вакансия нерелевантна - не сбрасываем статус,
                        // чтобы избежать бесконечного цикла обработки нерелевантных вакансий
                        val existingAnalysis = runBlocking {
                            vacancyAnalysisService.findByVacancyId(vacancy.id)
                        }
                        if (existingAnalysis != null && !existingAnalysis.isRelevant) {
                            log.info(
                                "[Scheduler] Retry: Vacancy ${vacancy.id} already analyzed and not relevant " +
                                    "(score: ${String.format("%.2f", existingAnalysis.relevanceScore * 100)}%), " +
                                    "skipping retry to avoid infinite loop",
                            )
                            return@forEach
                        }

                        // Проверяем на бан-слова перед повторной обработкой
                        val validationResult = vacancyContentValidator.validate(vacancy)
                        if (!validationResult.isValid) {
                            log.warn(
                                "[Scheduler] Retry: Vacancy ${vacancy.id} ('${vacancy.name}') " +
                                    "contains exclusion rules: ${validationResult.rejectionReason}, " +
                                    "deleting from database",
                            )

                            // Удаляем вакансию из БД, так как она содержит бан-слова
                            try {
                                skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                                log.info(
                                    "[Scheduler] Retry: Deleted vacancy ${vacancy.id} from database due to exclusion rules",
                                )
                                deletedCount++
                            } catch (e: Exception) {
                                log.error("[Scheduler] Retry: Failed to delete vacancy ${vacancy.id}: ${e.message}", e)
                            }
                        } else {
                            // Вакансия прошла проверку, сбрасываем статус на NEW для повторной обработки
                            // (только если не было анализа или анализ был релевантным, но вакансия была пропущена по другой причине)
                            val oldStatus = vacancy.status
                            vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                            log.info("[Scheduler] Reset vacancy ${vacancy.id} status from $oldStatus to NEW for retry")
                            validCount++
                        }
                    } catch (e: Exception) {
                        log.error("[Scheduler] Failed to process vacancy ${vacancy.id} for retry: ${e.message}", e)
                    }
                }

                log.info(
                    "[Scheduler] Retry: Reset $validCount SKIPPED vacancies to NEW status, deleted $deletedCount vacancies with exclusion rules",
                )
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
        val cycleStartTime = System.currentTimeMillis()
        logCycleStart()

        // Запускаем анализ асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val fetchResult = vacancyFetchService.fetchAndSaveNewVacancies()
                sendStatusUpdate(VacancyService.FetchResult(fetchResult.vacancies, fetchResult.searchKeywords))

                // Вакансии теперь обрабатываются через очередь, поэтому не нужно анализировать здесь
                // Обработка происходит в VacancyProcessingQueueService
                log.info("[Scheduler] Vacancies are being processed by VacancyProcessingQueueService")
                logCycleSummary(cycleStartTime, fetchResult.vacancies.size, emptyList())
            } catch (e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
                handleUnauthorizedError(e)
            } catch (e: Exception) {
                handleGeneralError(e)
            }
        }
    }

    /**
     * Периодически обрабатывает QUEUED вакансии из БД, добавляя их в очередь обработки.
     * Запускается по расписанию из application.yml (app.schedule.process-queued-vacancies).
     * Обработка выполняется асинхронно, не блокируя поток - команды Telegram будут работать даже во время обработки.
     */
    @Scheduled(cron = "\${app.schedule.process-queued-vacancies:0 */10 * * * *}")
    fun processQueuedVacancies() {
        log.info("[Scheduler] Processing QUEUED vacancies from database...")

        // Запускаем обработку асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val queuedVacancies = vacancyService.getQueuedVacanciesForProcessing(limit = 50)
                if (queuedVacancies.isEmpty()) {
                    log.debug("[Scheduler] No QUEUED vacancies found in database")
                    return@launch
                }

                log.info("[Scheduler] Found ${queuedVacancies.size} QUEUED vacancies, adding to processing queue...")
                val vacancyIds = queuedVacancies.map { it.id }
                val enqueuedCount = vacancyProcessingQueueService.enqueueBatch(vacancyIds)
                val skippedCount = vacancyIds.size - enqueuedCount
                log.info(
                    "[Scheduler] Added $enqueuedCount QUEUED vacancies to processing queue ($skippedCount skipped as duplicates)",
                )
            } catch (e: Exception) {
                log.error("[Scheduler] Error processing QUEUED vacancies: ${e.message}", e)
            }
        }
    }

    /**
     * Периодически извлекает навыки из релевантных вакансий, которые еще не имеют навыков.
     * Запускается по расписанию из application.yml (app.schedule.extract-relevant-skills).
     * Обработка выполняется асинхронно, не блокируя поток.
     */
    @Scheduled(cron = "\${app.schedule.extract-relevant-skills:0 0 3 * * *}")
    fun extractSkillsForRelevantVacancies() {
        log.info("[Scheduler] Starting skill extraction for relevant vacancies without skills...")

        // Запускаем извлечение асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                val processedCount = skillExtractionService.extractSkillsForRelevantVacancies()
                if (processedCount > 0) {
                    log.info("[Scheduler]  Extracted skills from $processedCount relevant vacancies")
                } else {
                    log.info("[Scheduler] ℹ️ No relevant vacancies without skills found")
                }
            } catch (e: Exception) {
                log.error("[Scheduler]  Error extracting skills for relevant vacancies: ${e.message}", e)
            }
        }
    }

    /**
     * Recovery механизм: если очередь обработки новых вакансий пуста и Ollama не занята,
     * обрабатывает по одной вакансии из БД для извлечения навыков.
     * Новые вакансии всегда имеют приоритет - если они приходят, recovery уступает им место.
     * Запускается по расписанию из application.yml (app.schedule.recovery-skill-extraction).
     * Обработка выполняется асинхронно, не блокируя поток.
     */
    @Scheduled(cron = "\${app.schedule.recovery-skill-extraction:0 */5 * * * *}")
    fun recoverySkillExtraction() {
        log.debug("[Scheduler] Recovery skill extraction scheduled task triggered")

        // Подсчитываем количество вакансий без навыков для логирования (всегда, даже если recovery пропускается)
        val allVacanciesWithoutSkills = vacancyRepository.findVacanciesWithoutSkills()
        val totalCount = allVacanciesWithoutSkills.size
        val relevantCount = vacancyRepository.findRelevantVacanciesWithoutSkills().size

        // Запускаем обработку асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                // Проверяем состояние Circuit Breaker перед запуском recovery
                val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
                if (circuitBreakerState == "OPEN") {
                    log.info(
                        "[Scheduler] Recovery skill extraction skipped: Circuit Breaker is OPEN (total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                // Проверяем, пуста ли очередь обработки новых вакансий (приоритет новым вакансиям)
                if (!vacancyProcessingQueueService.isQueueEmpty()) {
                    log.info(
                        "[Scheduler] Recovery skill extraction skipped: " +
                            "Vacancy processing queue is not empty (new vacancies have priority) " +
                            "(total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                // Проверяем, не занята ли Ollama обработкой новых вакансий
                val activeOllamaRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
                if (activeOllamaRequests > 0) {
                    log.info(
                        "[Scheduler] Recovery skill extraction skipped: " +
                            "Ollama is busy ($activeOllamaRequests active request(s)) " +
                            "(new vacancies have priority) " +
                            "(total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                log.info(
                    "[Scheduler] Starting recovery skill extraction " +
                        "(Circuit Breaker: $circuitBreakerState, queue empty: true, " +
                        "Ollama idle: true, total without skills: $totalCount, " +
                        "relevant: $relevantCount)",
                )

                // Получаем одну вакансию без навыков из БД (приоритет релевантным)
                val pageable = PageRequest.of(0, 1)
                val vacanciesWithoutSkills = vacancyRepository.findOneVacancyWithoutSkills(pageable)

                if (vacanciesWithoutSkills.isEmpty()) {
                    log.info("[Scheduler] No vacancies without skills found for recovery")
                    return@launch
                }

                val vacancy = vacanciesWithoutSkills.first()
                log.info(
                    "[Scheduler] Recovery: Found vacancy ${vacancy.id} without skills, checking for exclusion rules (remaining: ${totalCount - 1})",
                )

                // Проверяем вакансию на бан-слова перед добавлением в очередь
                val validationResult = vacancyContentValidator.validate(vacancy)
                if (!validationResult.isValid) {
                    log.warn(
                        "[Scheduler] Recovery: Vacancy ${vacancy.id} ('${vacancy.name}') " +
                            "contains exclusion rules: ${validationResult.rejectionReason}, " +
                            "deleting from database",
                    )

                    // Удаляем вакансию из БД, так как она содержит бан-слова
                    try {
                        skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                        log.info(
                            "[Scheduler] Recovery: Deleted vacancy ${vacancy.id} from database due to exclusion rules",
                        )
                    } catch (e: Exception) {
                        log.error("[Scheduler] Recovery: Failed to delete vacancy ${vacancy.id}: ${e.message}", e)
                    }
                    return@launch
                }

                // Вакансия прошла проверку на бан-слова, добавляем в очередь извлечения навыков
                log.info(
                    "[Scheduler] Recovery: Vacancy ${vacancy.id} passed exclusion rules check, adding to skill extraction queue",
                )
                val enqueued = skillExtractionQueueService.enqueue(vacancy.id)
                if (enqueued) {
                    log.info("[Scheduler] Recovery: Added vacancy ${vacancy.id} to skill extraction queue")
                } else {
                    log.info(
                        "[Scheduler] Recovery: Vacancy ${vacancy.id} was not enqueued (already processing or has skills)",
                    )
                }
            } catch (e: Exception) {
                log.error("[Scheduler] Error in recovery skill extraction: ${e.message}", e)
            }
        }
    }

    /**
     * Загружает новые вакансии из HH.ru API
     */
    private suspend fun fetchNewVacancies(): VacancyService.FetchResult {
        log.info(" [Scheduler] Step 1: Fetching new vacancies from HH.ru API...")
        val fetchResult = vacancyService.fetchAndSaveNewVacancies()
        log.info(" [Scheduler] Step 1 completed: Fetched ${fetchResult.vacancies.size} new vacancies from HH.ru")
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
            fetchResult.vacancies.isNotEmpty() -> " UP (found ${fetchResult.vacancies.size} vacancies)"
            fetchResult.searchKeywords.isNotEmpty() -> " UP (request completed, no new vacancies found)"
            else -> " Check completed, but no vacancies found"
        }
    }

    private fun getVacanciesForAnalysis(): List<Vacancy> {
        log.debug("[Scheduler] Getting vacancies for analysis...")
        val vacanciesToAnalyze = vacancyService.getNewVacanciesForAnalysis()
        log.debug("[Scheduler] Found ${vacanciesToAnalyze.size} vacancies to analyze")
        return vacanciesToAnalyze
    }

    private suspend fun analyzeVacancies(vacanciesToAnalyze: List<Vacancy>): List<VacancyAnalysis?> {
        log.info(
            "[Scheduler] Analyzing ${vacanciesToAnalyze.size} vacancies via Ollama (max concurrent: $maxConcurrentRequests)...",
        )
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
        val cycleDuration = System.currentTimeMillis() - cycleStartTime

        log.info("[Scheduler] ========================================")
        log.info("[Scheduler] Cycle Summary:")
        log.info("[Scheduler]   - New vacancies fetched: $newVacanciesCount")
        if (analysisResults.isNotEmpty()) {
            val analyzedCount = analysisResults.count { it != null }
            val relevantCount = analysisResults.count { it?.isRelevant == true }
            log.info("[Scheduler]   - Vacancies analyzed: $analyzedCount")
            log.info("[Scheduler]   - Relevant vacancies: $relevantCount")
        } else {
            log.info("[Scheduler]   - Vacancies are being processed by VacancyProcessingQueueService")
        }
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
            " ERROR: Token invalid or insufficient permissions",
            emptyList(),
            0,
        )
    }

    private fun handleGeneralError(e: Exception) {
        log.error("[Scheduler] Error during scheduled vacancy check: ${e.message}", e)
        notificationService.sendStatusUpdate(
            " ERROR: ${e.message?.take(AppConstants.TextLimits.ERROR_MESSAGE_MAX_LENGTH) ?: "Unknown error"}",
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
        log.debug(" [Scheduler] Processing vacancy: ${vacancy.id} - '${vacancy.name}'")
        return try {
            // Проверяем состояние Circuit Breaker перед анализом (до semaphore, чтобы не блокировать поток)
            val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
            if (circuitBreakerState == "OPEN") {
                log.warn(" [Scheduler] Circuit Breaker is OPEN, skipping vacancy ${vacancy.id} for retry later")
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                return null
            }

            // Используем semaphore для ограничения параллельных запросов к LLM
            analysisSemaphore.withPermit {
                val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

                // Если анализ вернул null - вакансия была отклонена валидатором и удалена из БД
                if (analysis == null) {
                    log.info(" [Scheduler] Vacancy ${vacancy.id} was rejected by validator and deleted from database")
                    return null
                }

                // Обновляем статус вакансии через VacancyStatusService
                val newStatus = if (analysis.isRelevant) VacancyStatus.ANALYZED else VacancyStatus.SKIPPED
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
                log.debug(" [Scheduler] Updated vacancy ${vacancy.id} status to: $newStatus")

                // Обработка релевантных вакансий теперь происходит через VacancyProcessingQueueService:
                // - Анализ вакансии на соответствие резюме
                // - Если релевантна - отправка в Telegram и добавление в очередь навыков
                if (analysis.isRelevant) {
                    log.info(
                        " [Scheduler] Vacancy ${vacancy.id} is relevant (score: ${String.format(
                            "%.2f",
                            analysis.relevanceScore * 100,
                        )}%)",
                    )
                    log.info(
                        "ℹ️ [Scheduler] Vacancy will be processed by event-driven pipeline (notification service -> skill extraction queue)",
                    )
                } else {
                    log.debug(
                        "ℹ️ [Scheduler] Vacancy ${vacancy.id} is not relevant (score: ${String.format(
                            "%.2f",
                            analysis.relevanceScore * 100,
                        )}%), skipping",
                    )
                }

                analysis
            }
        } catch (e: OllamaException) {
            // Если это ошибка Circuit Breaker OPEN, мы уже обработали её выше (до semaphore)
            // Здесь обрабатываем только другие ошибки Ollama
            if (e.message?.contains("Circuit Breaker is OPEN") == true) {
                // Это не должно произойти, так как мы проверяем Circuit Breaker до анализа
                // Но на всякий случай обрабатываем
                log.warn(
                    " [Scheduler] Circuit Breaker is OPEN (caught in exception handler), marking vacancy ${vacancy.id} as SKIPPED",
                )
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                } catch (updateError: Exception) {
                    log.error(
                        " [Scheduler] Failed to update status for vacancy ${vacancy.id} after Circuit Breaker error",
                        updateError,
                    )
                }
                return null
            }

            log.error(" [Scheduler] Ollama error analyzing vacancy ${vacancy.id}: ${e.message}", e)
            // Критическая ошибка после всех retry - помечаем как SKIPPED для повторной обработки
            log.warn(
                " [Scheduler] Critical error after retries, marking vacancy ${vacancy.id} as SKIPPED for retry",
            )
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                metricsService.incrementVacanciesSkipped()
            } catch (updateError: Exception) {
                log.error(" [Scheduler] Failed to update status for vacancy ${vacancy.id} after error", updateError)
            }
            null
        } catch (e: VacancyProcessingException) {
            log.error(" [Scheduler] Error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как SKIPPED для проблемных вакансий
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                metricsService.incrementVacanciesSkipped()
            } catch (updateError: Exception) {
                log.error(
                    " [Scheduler] Failed to update status for vacancy ${vacancy.id} after processing error",
                    updateError,
                )
            }
            null
        } catch (e: Exception) {
            log.error(" [Scheduler] Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как SKIPPED для неожиданных ошибок
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                metricsService.incrementVacanciesSkipped()
            } catch (updateError: Exception) {
                log.error(
                    " [Scheduler] Failed to update status for vacancy ${vacancy.id} after unexpected error",
                    updateError,
                )
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
                    log.warn(" [Scheduler] No active resume found. Sending notification to user.")
                    notificationService.sendMessage(
                        """
 <b>Резюме не найдено!</b>
                        
                        Для начала работы с HH Assistant необходимо загрузить резюме.
                        
                        <b>Как загрузить резюме:</b>
                        1. Отправьте PDF файл с резюме в этот чат
                        2. Дождитесь подтверждения обработки
                        3. После этого вы начнете получать подходящие вакансии
                        
                        <i>Примечание: Резюме должно быть в формате PDF</i>
                        """.trimIndent(),
                    )
                } else {
                    log.info(" [Scheduler] Active resume found, no notification needed")
                }
            } catch (e: Exception) {
                log.error(" [Scheduler] Error checking resume: ${e.message}", e)
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
