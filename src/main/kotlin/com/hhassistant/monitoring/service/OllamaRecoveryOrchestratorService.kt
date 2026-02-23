package com.hhassistant.monitoring.service

import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.vacancy.repository.VacancyRepository
import com.hhassistant.vacancy.service.VacancyProcessingQueueService
import com.hhassistant.vacancy.service.VacancyRecoveryService
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
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

/**
 * Сервис для оркестрации восстановления вакансий на основе состояния Ollama.
 * Отделен от OllamaMonitoringService для устранения циклических зависимостей.
 *
 * Этот сервис:
 * - Мониторит состояние Ollama через OllamaMonitoringService
 * - Запускает восстановление вакансий через VacancyRecoveryService
 * - Обрабатывает NEW вакансии, когда Ollama свободна
 */
@Service
class OllamaRecoveryOrchestratorService(
    private val ollamaMonitoringService: OllamaMonitoringService,
    private val circuitBreakerStateService: CircuitBreakerStateService,
    private val vacancyRecoveryService: VacancyRecoveryService,
    private val vacancyProcessingQueueService: VacancyProcessingQueueService,
    private val vacancyRepository: VacancyRepository,
    @Value("\${app.ollama-recovery.enabled:true}") private val enabled: Boolean,
    @Value("\${app.ollama-recovery.interval-seconds:10}") private val intervalSeconds: Int,
    @Value("\${app.ollama-recovery.pause-when-empty-minutes:30}") private val pauseWhenEmptyMinutes: Int,
    @Value("\${resilience.circuit-breaker.wait-duration-in-open-state-seconds:60}") private val circuitBreakerWaitDurationSeconds: Long,
) {
    private val log = KotlinLogging.logger {}

    // Scope для корутин оркестрации
    private val supervisorJob = SupervisorJob()
    private val orchestratorScope = CoroutineScope(
        Dispatchers.Default + supervisorJob,
    )

    private var orchestratorJob: Job? = null

    // Время последнего запуска восстановления (для ограничения частоты)
    private val lastRecoveryTime = AtomicLong(0)

    // Время последней паузы восстановления (когда нечего восстанавливать и нет NEW)
    private val lastRecoveryPauseTime = AtomicLong(0)

    /**
     * Запускает оркестрацию восстановления после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun startOrchestration() {
        if (!enabled) {
            log.debug("🔄 [OllamaRecoveryOrchestrator] Orchestration is disabled, skipping")
            return
        }

        log.info("🔄 [OllamaRecoveryOrchestrator] Starting Ollama recovery orchestration (interval: ${intervalSeconds}s)")

        orchestratorJob = orchestratorScope.launch {
            while (true) {
                try {
                    checkAndOrchestrateRecovery()
                    delay(intervalSeconds * 1000L)
                } catch (e: Exception) {
                    log.error("🔄 [OllamaRecoveryOrchestrator] Error in orchestration loop: ${e.message}", e)
                    delay(intervalSeconds * 1000L)
                }
            }
        }
    }

    /**
     * Проверяет состояние Ollama и запускает восстановление при необходимости
     */
    private fun checkAndOrchestrateRecovery() {
        val activeCount = ollamaMonitoringService.getActiveRequestsCount()
        val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()

        // Если нет активных запросов и статус IDLE, запускаем восстановление failed/skipped вакансий
        // и обработку NEW вакансий
        if (activeCount == 0 && circuitBreakerState != "OPEN") {
            tryRecoveryFailedAndSkippedVacancies()
            tryProcessNewVacancies()
        }
    }

    /**
     * Пытается восстановить failed и skipped вакансии, если прошло достаточно времени с последнего запуска
     */
    private fun tryRecoveryFailedAndSkippedVacancies() {
        val now = System.currentTimeMillis()
        val lastRecovery = lastRecoveryTime.get()
        val timeSinceLastRecovery = now - lastRecovery

        // Проверяем, прошло ли достаточно времени с последнего запуска
        if (timeSinceLastRecovery < intervalSeconds * 1000L) {
            return
        }

        // Проверяем состояние Circuit Breaker
        val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
        if (circuitBreakerState == "OPEN") {
            return
        }

        // Проверяем, есть ли вакансии для восстановления
        val hasVacanciesToRecover = vacancyRecoveryService.hasVacanciesToRecover()

        // Если нечего восстанавливать - выходим
        if (!hasVacanciesToRecover) {
            return
        }

        // Обновляем время последнего запуска
        if (!lastRecoveryTime.compareAndSet(lastRecovery, now)) {
            // Другой поток уже запустил восстановление
            return
        }

        // Запускаем восстановление через фасад-сервис
        vacancyRecoveryService.recoverFailedAndSkippedVacancies { recoveredCount, _ ->
            log.info(
                "🔄 [OllamaRecoveryOrchestrator] Recovery completed - Reset $recoveredCount vacancies to NEW",
            )
        }
    }

    /**
     * Пытается обработать NEW вакансии, добавляя их в очередь обработки
     */
    private fun tryProcessNewVacancies() {
        // Проверяем состояние Circuit Breaker
        val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
        if (circuitBreakerState == "OPEN") {
            return
        }

        // Проверяем, есть ли NEW вакансии
        val newVacanciesCount = vacancyRepository.countPendingVacancies()
        if (newVacanciesCount == 0L) {
            return
        }

        // Запускаем обработку NEW вакансий асинхронно
        orchestratorScope.launch {
            try {
                // Получаем NEW вакансии и добавляем их в очередь
                val newVacancies = vacancyRepository.findByStatus(VacancyStatus.NEW)
                    .take(50) // Берем до 50 вакансий за раз

                if (newVacancies.isNotEmpty()) {
                    val vacancyIds = newVacancies.map { it.id }
                    val enqueuedCount = vacancyProcessingQueueService.enqueueBatch(vacancyIds)

                    log.info(
                        "🔄 [OllamaRecoveryOrchestrator] Added $enqueuedCount NEW vacancies to processing queue " +
                            "(out of ${newVacancies.size} found)",
                    )
                }
            } catch (e: Exception) {
                log.error("🔄 [OllamaRecoveryOrchestrator] Error processing NEW vacancies: ${e.message}", e)
            }
        }
    }

    /**
     * Корректное завершение работы при остановке приложения
     */
    @PreDestroy
    fun shutdown() {
        log.info("🔄 [OllamaRecoveryOrchestrator] Shutting down orchestration service...")
        orchestratorJob?.cancel()
        supervisorJob.cancel()
    }
}
