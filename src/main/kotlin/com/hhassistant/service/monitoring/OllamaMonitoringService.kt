package com.hhassistant.service.monitoring

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.repository.VacancySkillRepository
import com.hhassistant.service.vacancy.VacancyContentValidator
import com.hhassistant.service.vacancy.VacancyService
import com.hhassistant.service.vacancy.VacancyStatusService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Тип задачи для Ollama
 */
enum class OllamaTaskType {
    VACANCY_ANALYSIS, // Анализ новой вакансии
    SKILL_EXTRACTION, // Извлечение навыков (recovery)
    LOG_ANALYSIS, // Анализ логов
    OTHER, // Другие задачи
}

/**
 * Сервис для мониторинга статуса Ollama
 * Отслеживает активные запросы и логирует статус каждые 5 секунд
 */
@Service
class OllamaMonitoringService(
    private val circuitBreakerStateService: CircuitBreakerStateService,
    private val vacancyRepository: VacancyRepository,
    private val vacancySkillRepository: VacancySkillRepository,
    private val vacancyAnalysisRepository: VacancyAnalysisRepository,
    private val vacancyService: VacancyService,
    private val vacancyContentValidator: VacancyContentValidator,
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyStatusService: VacancyStatusService,
    @Value("\${app.ollama-monitoring.enabled:true}") private val enabled: Boolean,
    @Value("\${app.ollama-monitoring.interval-seconds:5}") private val intervalSeconds: Int,
    @Value("\${app.ollama-monitoring.skipped-validation.enabled:true}") private val skippedValidationEnabled: Boolean,
    @Value("\${app.ollama-monitoring.skipped-validation.batch-size:20}") private val skippedValidationBatchSize: Int,
    @Value("\${app.ollama-monitoring.skipped-validation.interval-seconds:5}") private val skippedValidationIntervalSeconds: Int,
    @Value("\${resilience.circuit-breaker.wait-duration-in-open-state-seconds:60}") private val circuitBreakerWaitDurationSeconds: Long,
) {
    private val log = KotlinLogging.logger {}

    // Счетчик активных запросов к Ollama
    private val activeRequests = AtomicInteger(0)

    // Отслеживание активных задач по типам
    private val activeTasksByType = ConcurrentHashMap<OllamaTaskType, AtomicInteger>()

    // Scope для корутин мониторинга
    private val supervisorJob = SupervisorJob()
    private val monitoringScope = CoroutineScope(
        Dispatchers.Default + supervisorJob,
    )

    private var monitoringJob: Job? = null

    // Время последнего запуска валидации skipped вакансий
    private val lastSkippedValidationTime = AtomicLong(0)

    // Флаг для отслеживания активной обработки skipped вакансий
    private val isProcessingSkipped = AtomicInteger(0)

    // Время, когда circuit breaker перешел в состояние OPEN (для отслеживания времени в OPEN)
    private val circuitBreakerOpenTime = AtomicLong(0)

    /**
     * Увеличивает счетчик активных запросов (для обратной совместимости)
     */
    fun incrementActiveRequests() {
        incrementActiveRequests(OllamaTaskType.OTHER)
    }

    /**
     * Уменьшает счетчик активных запросов (для обратной совместимости)
     */
    fun decrementActiveRequests() {
        decrementActiveRequests(OllamaTaskType.OTHER)
    }

    /**
     * Увеличивает счетчик активных запросов для указанного типа задачи
     */
    fun incrementActiveRequests(taskType: OllamaTaskType) {
        activeRequests.incrementAndGet()
        activeTasksByType.computeIfAbsent(taskType) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Уменьшает счетчик активных запросов для указанного типа задачи
     * Если активных запросов становится 0 и Circuit Breaker в состоянии OPEN,
     * переводит его в HALF_OPEN для попытки восстановления
     */
    fun decrementActiveRequests(taskType: OllamaTaskType) {
        val previousCount = activeRequests.getAndDecrement()
        activeTasksByType[taskType]?.decrementAndGet()

        // Если активных запросов стало 0 и Circuit Breaker в OPEN, сбрасываем его в CLOSED
        val currentCount = activeRequests.get()
        if (previousCount > 0 && currentCount == 0) {
            val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
            if (circuitBreakerState == "OPEN") {
                log.info(
                    "[OllamaMonitoring] All active requests completed (was $previousCount, now $currentCount). " +
                        "Resetting Circuit Breaker from OPEN to CLOSED",
                )
                circuitBreakerStateService.resetCircuitBreaker()
                // Сбрасываем время перехода в OPEN
                circuitBreakerOpenTime.set(0L)
            }
        }
    }

    /**
     * Получает количество активных запросов
     */
    fun getActiveRequestsCount(): Int {
        return activeRequests.get()
    }

    /**
     * Получает количество активных задач по типам
     */
    fun getActiveTasksByType(): Map<OllamaTaskType, Int> {
        return activeTasksByType.mapValues { it.value.get() }.filter { it.value > 0 }
    }

    /**
     * Запускает мониторинг после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun startMonitoring() {
        if (!enabled) {
            log.debug("[OllamaMonitoring] Monitoring is disabled, skipping")
            return
        }

        log.info("[OllamaMonitoring] Starting Ollama monitoring (interval: ${intervalSeconds}s)")

        monitoringJob = monitoringScope.launch {
            while (true) {
                try {
                    logOllamaStatus()
                    delay(intervalSeconds * 1000L)
                } catch (e: Exception) {
                    log.error("[OllamaMonitoring] Error in monitoring loop: ${e.message}", e)
                    delay(intervalSeconds * 1000L)
                }
            }
        }
    }

    /**
     * Логирует текущий статус Ollama
     */
    private fun logOllamaStatus() {
        val activeCount = activeRequests.get()
        val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
        val tasksByType = getActiveTasksByType()

        // Отслеживаем время перехода в OPEN
        if (circuitBreakerState == "OPEN") {
            val openTime = circuitBreakerOpenTime.get()
            if (openTime == 0L) {
                // Впервые обнаружили переход в OPEN - сохраняем время
                circuitBreakerOpenTime.set(System.currentTimeMillis())
                log.info("[OllamaMonitoring] Circuit Breaker transitioned to OPEN state")
            }
        } else {
            // Circuit breaker не в OPEN - сбрасываем время
            if (circuitBreakerOpenTime.get() != 0L) {
                circuitBreakerOpenTime.set(0L)
            }
        }

        val status = when {
            activeCount > 0 -> {
                val tasksDescription = if (tasksByType.isNotEmpty()) {
                    tasksByType.entries.joinToString(", ") { (type, count) ->
                        when (type) {
                            OllamaTaskType.VACANCY_ANALYSIS -> "vacancy analysis: $count"
                            OllamaTaskType.SKILL_EXTRACTION -> "skill extraction: $count"
                            OllamaTaskType.LOG_ANALYSIS -> "log analysis: $count"
                            OllamaTaskType.OTHER -> "other: $count"
                        }
                    }
                } else {
                    "$activeCount request(s)"
                }
                "ACTIVE ($tasksDescription)"
            }
            circuitBreakerState == "OPEN" -> "UNAVAILABLE (Circuit Breaker OPEN)"
            else -> "IDLE (no active requests)"
        }

        // Получаем статистику по вакансиям из базы данных
        val pendingCount = vacancyRepository.countPendingVacancies()
        val skippedCount = vacancyRepository.countSkippedVacancies()

        log.info(
            "[OllamaMonitoring] Status: $status | " +
                "Circuit Breaker: $circuitBreakerState | Active requests: $activeCount | Pending: $pendingCount | Skipped: $skippedCount",
        )

        // Если circuit breaker OPEN, активных запросов 0, и прошло достаточно времени - пытаемся перевести в HALF_OPEN
        if (circuitBreakerState == "OPEN" && activeCount == 0) {
            val openTime = circuitBreakerOpenTime.get()
            if (openTime > 0) {
                val timeInOpenState = System.currentTimeMillis() - openTime
                val waitDurationMillis = circuitBreakerWaitDurationSeconds * 1000L
                if (timeInOpenState >= waitDurationMillis) {
                    log.info(
                        "[OllamaMonitoring] Circuit Breaker has been OPEN for ${timeInOpenState / 1000}s " +
                            "(threshold: ${circuitBreakerWaitDurationSeconds}s). " +
                            "Attempting to transition to HALF_OPEN for recovery...",
                    )
                    circuitBreakerStateService.tryTransitionToHalfOpen()
                    // Сбрасываем время после попытки перехода
                    circuitBreakerOpenTime.set(0L)
                } else {
                    val remainingSeconds = (waitDurationMillis - timeInOpenState) / 1000
                    log.debug(
                        "[OllamaMonitoring] Circuit Breaker OPEN for ${timeInOpenState / 1000}s, " +
                            "waiting ${remainingSeconds}s more before recovery attempt",
                    )
                }
            }
        }

        // Если есть активные запросы к LLM, параллельно обрабатываем skipped вакансии
        // (валидация по словам и проверка URL без использования LLM)
        if (activeCount > 0 && skippedValidationEnabled) {
            tryProcessSkippedVacanciesInParallel()
        }
    }

    /**
     * Параллельно обрабатывает skipped вакансии во время работы LLM
     * Выполняет валидацию по словам и проверку URL без использования LLM
     */
    private fun tryProcessSkippedVacanciesInParallel() {
        val now = System.currentTimeMillis()
        val lastValidation = lastSkippedValidationTime.get()
        val timeSinceLastValidation = now - lastValidation

        // Проверяем, прошло ли достаточно времени с последнего запуска
        if (timeSinceLastValidation < skippedValidationIntervalSeconds * 1000L) {
            return
        }

        // Проверяем, не обрабатываются ли уже skipped вакансии
        if (isProcessingSkipped.get() > 0) {
            return
        }

        // Проверяем, есть ли skipped вакансии
        val skippedCount = vacancyRepository.countSkippedVacancies()
        if (skippedCount == 0L) {
            return
        }

        // Обновляем время последнего запуска
        if (!lastSkippedValidationTime.compareAndSet(lastValidation, now)) {
            // Другой поток уже запустил обработку
            return
        }

        // Запускаем обработку skipped вакансий асинхронно
        monitoringScope.launch {
            try {
                isProcessingSkipped.incrementAndGet()
                processSkippedVacanciesBatch()
            } catch (e: Exception) {
                log.error("[OllamaMonitoring] Error processing skipped vacancies: ${e.message}", e)
            } finally {
                isProcessingSkipped.decrementAndGet()
            }
        }
    }

    /**
     * Обрабатывает батч skipped вакансий: валидация по словам и проверка URL
     */
    private suspend fun processSkippedVacanciesBatch() {
        val skippedVacancies = vacancyService.getSkippedVacanciesForRetry(
            limit = skippedValidationBatchSize,
            retryWindowHours = 48,
        )

        if (skippedVacancies.isEmpty()) {
            return
        }

        log.info(
            "[OllamaMonitoring] Processing ${skippedVacancies.size} skipped vacancies in parallel " +
                "(validation + URL check, no LLM)",
        )

        var validatedCount = 0
        var deletedByValidationCount = 0
        var deletedByUrlCount = 0
        var resetToNewCount = 0

        skippedVacancies.forEach { vacancy ->
            try {
                // Шаг 1: Валидация по словам
                val validationResult = vacancyContentValidator.validate(vacancy)
                if (!validationResult.isValid) {
                    log.warn(
                        "[OllamaMonitoring] Skipped vacancy ${vacancy.id} ('${vacancy.name}') " +
                            "failed validation: ${validationResult.rejectionReason}, deleting",
                    )
                    try {
                        deleteVacancyAndSkills(vacancy.id)
                        deletedByValidationCount++
                    } catch (e: Exception) {
                        log.error(
                            "[OllamaMonitoring] Failed to delete vacancy ${vacancy.id}: ${e.message}",
                            e,
                        )
                    }
                    return@forEach
                }

                // Шаг 2: Проверка URL
                val urlCheckResult = withContext(Dispatchers.IO) {
                    try {
                        hhVacancyClient.getVacancyDetails(vacancy.id)
                        // Вакансия существует и доступна
                        true
                    } catch (e: HHAPIException.NotFoundException) {
                        // Вакансия не найдена (404) - URL неактуален
                        log.warn(
                            "[OllamaMonitoring] Skipped vacancy ${vacancy.id} ('${vacancy.name}') " +
                                "not found on HH.ru (404), deleting",
                        )
                        false
                    } catch (e: HHAPIException.RateLimitException) {
                        // Rate limit - пропускаем эту вакансию, обработаем позже
                        log.debug(
                            "[OllamaMonitoring] Rate limit while checking skipped vacancy ${vacancy.id}, " +
                                "skipping for now",
                        )
                        true // Считаем URL валидным, чтобы не удалять из-за rate limit
                    } catch (e: Exception) {
                        // Другие ошибки - логируем, но считаем URL валидным
                        log.warn(
                            "[OllamaMonitoring] Error checking skipped vacancy ${vacancy.id} URL: ${e.message}, " +
                                "assuming URL is valid",
                        )
                        true
                    }
                }

                // Если URL неактуален (404) - удаляем вакансию
                if (!urlCheckResult) {
                    try {
                        deleteVacancyAndSkills(vacancy.id)
                        deletedByUrlCount++
                    } catch (e: Exception) {
                        log.error(
                            "[OllamaMonitoring] Failed to delete vacancy ${vacancy.id}: ${e.message}",
                            e,
                        )
                    }
                    return@forEach
                }

                // Если валидация и URL проверка прошли - вакансия валидна
                // Можно перевести в NEW для повторного анализа через LLM
                val oldStatus = vacancy.status
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                log.debug(
                    "[OllamaMonitoring] Skipped vacancy ${vacancy.id} passed validation and URL check, " +
                        "reset from $oldStatus to NEW",
                )
                resetToNewCount++
                validatedCount++
            } catch (e: Exception) {
                log.error(
                    "[OllamaMonitoring] Error processing skipped vacancy ${vacancy.id}: ${e.message}",
                    e,
                )
            }
        }

        log.info(
            "[OllamaMonitoring] Skipped vacancies processing completed: " +
                "validated=$validatedCount, resetToNew=$resetToNewCount, " +
                "deletedByValidation=$deletedByValidationCount, deletedByUrl=$deletedByUrlCount",
        )
    }

    /**
     * Удаляет вакансию и все связанные данные (навыки, анализы)
     */
    private fun deleteVacancyAndSkills(vacancyId: String) {
        try {
            // Удаляем связи вакансия-навык
            vacancySkillRepository.deleteByVacancyId(vacancyId)

            // Удаляем анализы вакансии
            vacancyAnalysisRepository.findByVacancyId(vacancyId)?.let { analysis ->
                vacancyAnalysisRepository.delete(analysis)
                log.debug("🗑️ [OllamaMonitoring] Deleted VacancyAnalysis for vacancy $vacancyId")
            }

            // Удаляем саму вакансию
            vacancyRepository.deleteById(vacancyId)

            log.info("✅ [OllamaMonitoring] Deleted vacancy $vacancyId and all related data")
        } catch (e: Exception) {
            log.error("❌ [OllamaMonitoring] Failed to delete vacancy $vacancyId: ${e.message}", e)
        }
    }

    /**
     * Корректное завершение работы при остановке приложения
     */
    @PreDestroy
    fun shutdown() {
        log.info("[OllamaMonitoring] Shutting down monitoring service...")
        monitoringJob?.cancel()
        supervisorJob.cancel()
    }
}
