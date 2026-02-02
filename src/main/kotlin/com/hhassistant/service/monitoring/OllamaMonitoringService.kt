package com.hhassistant.service.monitoring

import com.hhassistant.service.vacancy.VacancyAnalysisService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PreDestroy

/**
 * Сервис для мониторинга статуса Ollama
 * Отслеживает активные запросы и логирует статус каждые 5 секунд
 */
@Service
class OllamaMonitoringService(
    @Lazy private val vacancyAnalysisService: VacancyAnalysisService,
    @Value("\${app.ollama-monitoring.enabled:true}") private val enabled: Boolean,
    @Value("\${app.ollama-monitoring.interval-seconds:5}") private val intervalSeconds: Int,
) {
    private val log = KotlinLogging.logger {}
    
    // Счетчик активных запросов к Ollama
    private val activeRequests = AtomicInteger(0)
    
    // Scope для корутин мониторинга
    private val supervisorJob = SupervisorJob()
    private val monitoringScope = CoroutineScope(
        Dispatchers.Default + supervisorJob
    )
    
    private var monitoringJob: Job? = null

    /**
     * Увеличивает счетчик активных запросов
     */
    fun incrementActiveRequests() {
        activeRequests.incrementAndGet()
    }

    /**
     * Уменьшает счетчик активных запросов
     */
    fun decrementActiveRequests() {
        activeRequests.decrementAndGet()
    }

    /**
     * Получает количество активных запросов
     */
    fun getActiveRequestsCount(): Int {
        return activeRequests.get()
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
        
        val status = when {
            activeCount > 0 -> "ACTIVE ($activeCount request(s) in progress)"
            circuitBreakerState == "OPEN" -> "UNAVAILABLE (Circuit Breaker OPEN)"
            else -> "IDLE (no active requests)"
        }
        
        log.info("[OllamaMonitoring] Status: $status | Circuit Breaker: $circuitBreakerState | Active requests: $activeCount")
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

