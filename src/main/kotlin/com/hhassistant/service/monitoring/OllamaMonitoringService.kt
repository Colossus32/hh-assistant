package com.hhassistant.service.monitoring

import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.vacancy.VacancyAnalysisService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    @Lazy private val vacancyAnalysisService: VacancyAnalysisService,
    private val vacancyRepository: VacancyRepository,
    @Value("\${app.ollama-monitoring.enabled:true}") private val enabled: Boolean,
    @Value("\${app.ollama-monitoring.interval-seconds:5}") private val intervalSeconds: Int,
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
     */
    fun decrementActiveRequests(taskType: OllamaTaskType) {
        activeRequests.decrementAndGet()
        activeTasksByType[taskType]?.decrementAndGet()
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
        val circuitBreakerState = vacancyAnalysisService.getCircuitBreakerState()
        val tasksByType = getActiveTasksByType()

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
        val failedCount = vacancyRepository.countFailedVacancies()
        val skippedCount = vacancyRepository.countSkippedVacancies()

        log.info(
            "[OllamaMonitoring] Status: $status | Circuit Breaker: $circuitBreakerState | Active requests: $activeCount | Pending: $pendingCount | Failed: $failedCount | Skipped: $skippedCount",
        )
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
