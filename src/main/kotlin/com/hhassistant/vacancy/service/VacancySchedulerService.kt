package com.hhassistant.vacancy.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.monitoring.service.CircuitBreakerStateService
import com.hhassistant.monitoring.service.OllamaMonitoringService
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.analysis.service.SkillExtractionQueueService
import com.hhassistant.analysis.service.SkillExtractionService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class VacancySchedulerService(
    private val vacancyFetchService: VacancyFetchService,
    private val vacancyService: VacancyService,
    private val vacancyStatusService: VacancyStatusService,
    private val notificationService: NotificationService,
    private val resumeService: ResumeService,
    private val metricsService: com.hhassistant.monitoring.metrics.MetricsService,
    private val skillExtractionService: SkillExtractionService,
    private val vacancyProcessingQueueService: VacancyProcessingQueueService,
    private val skillExtractionQueueService: SkillExtractionQueueService,
    private val vacancyRepository: com.hhassistant.vacancy.repository.VacancyRepository,
    private val vacancyContentValidator: VacancyContentValidator,
    private val vacancyRecoveryService: VacancyRecoveryService,
    private val circuitBreakerStateService: CircuitBreakerStateService,
    @Autowired(required = false) private val ollamaMonitoringService: OllamaMonitoringService?,
) {
    private val log = KotlinLogging.logger {}

    // CoroutineScope для асинхронной обработки анализа вакансий
    private val schedulerScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error("⏰ [Scheduler] Unhandled exception in scheduler coroutine: ${exception.message}", exception)
        },
    )

    /**
     * Запускает проверку вакансий сразу после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        checkResumeAndNotify()
        log.info("⏰ [Scheduler] Application ready, preloading resume and sending startup notification...")

        // Запускаем предзагрузку резюме асинхронно
        schedulerScope.launch {
            try {
                resumeService.preloadResume()
            } catch (e: Exception) {
                log.error("⏰ [Scheduler] Failed to preload resume: ${e.message}", e)
            }
        }

        notificationService.sendStartupNotification()

        log.info("⏰ [Scheduler] Running initial vacancy check on startup...")
        checkNewVacancies()
    }

    /**
     * Периодически проверяет и восстанавливает вакансии со статусом SKIPPED,
     * которые были пропущены из-за Circuit Breaker OPEN.
     * Запускается каждые 5 минут, когда Circuit Breaker закрыт.
     * Использует унифицированный VacancyRecoveryService для обработки.
     * Обработка выполняется асинхронно, не блокируя поток.
     */
    @Scheduled(cron = "\${app.schedule.skipped-retry:0 */5 * * * *}")
    fun retrySkippedVacancies() {
        val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
        if (circuitBreakerState == "OPEN") {
            log.debug("⏰ [Scheduler] Circuit Breaker is still OPEN, skipping retry of skipped vacancies")
            return
        }

        log.info(
            "⏰ [Scheduler] Circuit Breaker is $circuitBreakerState, checking for skipped/failed vacancies to retry (retry window: 48 hours)...",
        )

        // Используем унифицированный VacancyRecoveryService
        // Ограничиваем batch size для scheduled задачи (меньше чем для автоматического recovery)
        vacancyRecoveryService.recoverFailedAndSkippedVacancies { recoveredCount, deletedCount ->
            log.info(
                "⏰ [Scheduler] Retry completed - Reset $recoveredCount SKIPPED vacancies to NEW status, " +
                    "deleted $deletedCount vacancies with exclusion rules",
            )
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
        schedulerScope.launch {
            try {
                val fetchResult = vacancyFetchService.fetchAndSaveNewVacancies()
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
        schedulerScope.launch {
            try {
                val queuedVacancies = vacancyService.getQueuedVacanciesForProcessing(limit = 50)
                if (queuedVacancies.isEmpty()) return@launch

                val vacancyIds = queuedVacancies.map { it.id }
                val enqueuedCount = vacancyProcessingQueueService.enqueueBatch(vacancyIds)
                if (enqueuedCount > 0) {
                    log.debug("⏰ [Scheduler] Enqueued $enqueuedCount vacancies (${vacancyIds.size - enqueuedCount} skipped)")
                }
            } catch (e: Exception) {
                log.error("⏰ [Scheduler] Error processing QUEUED vacancies: ${e.message}", e)
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
        schedulerScope.launch {
            try {
                val processedCount = skillExtractionService.extractSkillsForRelevantVacancies()
                if (processedCount > 0) {
                    log.info("⏰ [Scheduler] Extracted skills from $processedCount vacancies")
                }
            } catch (e: Exception) {
                log.error("⏰ [Scheduler]  Error extracting skills for relevant vacancies: ${e.message}", e)
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
        log.debug("⏰ [Scheduler] Recovery skill extraction scheduled task triggered")

        // Подсчитываем количество вакансий без навыков для логирования (всегда, даже если recovery пропускается)
        val allVacanciesWithoutSkills = vacancyRepository.findVacanciesWithoutSkills()
        val totalCount = allVacanciesWithoutSkills.size
        val relevantCount = vacancyRepository.findRelevantVacanciesWithoutSkills().size

        // Запускаем обработку асинхронно, не блокируя поток
        schedulerScope.launch {
            try {
                // Проверяем состояние Circuit Breaker перед запуском recovery
                val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
                if (circuitBreakerState == "OPEN") {
                    log.info(
                        "⏰ [Scheduler] Recovery skill extraction skipped: Circuit Breaker is OPEN (total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                // Проверяем, пуста ли очередь обработки новых вакансий (приоритет новым вакансиям)
                if (!vacancyProcessingQueueService.isQueueEmpty()) {
                    log.info(
                        "⏰ [Scheduler] Recovery skill extraction skipped: " +
                            "Vacancy processing queue is not empty (new vacancies have priority) " +
                            "(total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                // Проверяем, не занята ли Ollama обработкой новых вакансий
                val activeOllamaRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
                if (activeOllamaRequests > 0) {
                    log.info(
                        "⏰ [Scheduler] Recovery skill extraction skipped: " +
                            "Ollama is busy ($activeOllamaRequests active request(s)) " +
                            "(new vacancies have priority) " +
                            "(total without skills: $totalCount, relevant: $relevantCount)",
                    )
                    return@launch
                }

                log.info(
                    "⏰ [Scheduler] Starting recovery skill extraction " +
                        "(Circuit Breaker: $circuitBreakerState, queue empty: true, " +
                        "Ollama idle: true, total without skills: $totalCount, " +
                        "relevant: $relevantCount)",
                )

                // Получаем одну вакансию без навыков из БД (приоритет релевантным)
                val pageable = PageRequest.of(0, 1)
                val vacanciesWithoutSkills = vacancyRepository.findOneVacancyWithoutSkills(pageable)

                if (vacanciesWithoutSkills.isEmpty()) {
                    log.info("⏰ [Scheduler] No vacancies without skills found for recovery")
                    return@launch
                }

                val vacancy = vacanciesWithoutSkills.first()
                log.info(
                    "⏰ [Scheduler] Recovery: Found vacancy ${vacancy.id} without skills, checking for exclusion rules (remaining: ${totalCount - 1})",
                )

                // Проверяем вакансию на бан-слова перед добавлением в очередь
                val validationResult = vacancyContentValidator.validate(vacancy)
                if (!validationResult.isValid) {
                    log.warn(
                        "⏰ [Scheduler] Recovery: Vacancy ${vacancy.id} ('${vacancy.name}') " +
                            "contains exclusion rules: ${validationResult.rejectionReason}, " +
                            "deleting from database",
                    )

                    // Удаляем вакансию из БД, так как она содержит бан-слова
                    try {
                        skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                        log.info(
                            "⏰ [Scheduler] Recovery: Deleted vacancy ${vacancy.id} from database due to exclusion rules",
                        )
                    } catch (e: Exception) {
                        log.error("⏰ [Scheduler] Recovery: Failed to delete vacancy ${vacancy.id}: ${e.message}", e)
                    }
                    return@launch
                }

                // Вакансия прошла проверку на бан-слова, добавляем в очередь извлечения навыков
                log.info(
                    "⏰ [Scheduler] Recovery: Vacancy ${vacancy.id} passed exclusion rules check, adding to skill extraction queue",
                )
                val enqueued = skillExtractionQueueService.enqueue(vacancy.id)
                if (enqueued) {
                    log.info("⏰ [Scheduler] Recovery: Added vacancy ${vacancy.id} to skill extraction queue")
                } else {
                    log.info(
                        "⏰ [Scheduler] Recovery: Vacancy ${vacancy.id} was not enqueued (already processing or has skills)",
                    )
                }
            } catch (e: Exception) {
                log.error("⏰ [Scheduler] Error in recovery skill extraction: ${e.message}", e)
            }
        }
    }

    /**
     * Отправляет обновление статуса в Telegram
     * Теперь не отправляет сообщения - только логирует
     */
    private fun sendStatusUpdate(fetchResult: VacancyService.FetchResult) {
        // Убрали отправку сообщений каждые 15 минут
        // Healthcheck теперь выполняется отдельным сервисом HealthCheckService
        log.debug(
            "⏰ [Scheduler] Status update: ${fetchResult.vacancies.size} vacancies found, " +
                "keywords: ${fetchResult.searchKeywords.joinToString(", ")}",
        )
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

    private fun logCycleSummary(
        cycleStartTime: Long,
        newVacanciesCount: Int,
        analysisResults: List<VacancyAnalysis?>,
    ) {
        val cycleDuration = System.currentTimeMillis() - cycleStartTime
        log.info("⏰ [Scheduler] Cycle: fetched $newVacanciesCount, ${cycleDuration}ms")
    }

    private fun handleUnauthorizedError(e: com.hhassistant.exception.HHAPIException.UnauthorizedException) {
        log.error("⏰ [Scheduler] HH.ru API unauthorized/forbidden error: ${e.message}", e)
        notificationService.sendTokenExpiredAlert(
            e.message ?: "Unauthorized or Forbidden access to HH.ru API. " +
                "Token may be invalid, expired, or lacks required permissions.",
        )
        // Не отправляем статус-сообщение, если вакансий не найдено (sendStatusUpdate пропустит отправку при vacanciesFound = 0)
    }

    private fun handleGeneralError(e: Exception) {
        log.error("⏰ [Scheduler] Error during scheduled vacancy check: ${e.message}", e)
        // Не отправляем статус-сообщение, если вакансий не найдено (sendStatusUpdate пропустит отправку при vacanciesFound = 0)
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
                    log.warn("⏰ [Scheduler] No resume found, sent notification")
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
                    log.debug("⏰ [Scheduler] Resume found")
                }
            } catch (e: Exception) {
                log.error("⏰ [Scheduler] Error checking resume: ${e.message}", e)
            }
        }
    }

    /**
     * Корректное завершение работы при остановке приложения
     */
    @PreDestroy
    fun shutdown() {
        log.info("⏰ [Scheduler] Shutting down scheduler service...")
        schedulerScope.coroutineContext.cancel()
    }
}
