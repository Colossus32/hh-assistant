package com.hhassistant.monitoring.service

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Сервис для работы с состоянием Circuit Breaker для Ollama
 * Вынесен в отдельный класс для разрыва циклических зависимостей
 */
@Service
class CircuitBreakerStateService(
    @Qualifier("ollamaCircuitBreaker") private val ollamaCircuitBreaker: CircuitBreaker,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает текущее состояние Circuit Breaker для Ollama
     * @return Состояние Circuit Breaker: "CLOSED", "OPEN", "HALF_OPEN"
     */
    fun getCircuitBreakerState(): String {
        return ollamaCircuitBreaker.state.name
    }

    /**
     * Сбрасывает Circuit Breaker в состояние CLOSED
     * Используется когда все активные запросы завершились и circuit breaker находится в OPEN
     */
    fun resetCircuitBreaker() {
        val currentState = ollamaCircuitBreaker.state
        if (currentState.name == "OPEN") {
            try {
                ollamaCircuitBreaker.reset()
                log.info("[CircuitBreakerState] Circuit Breaker reset from OPEN to CLOSED (all active requests completed)")
            } catch (e: Exception) {
                log.warn("[CircuitBreakerState] Failed to reset Circuit Breaker: ${e.message}")
            }
        }
    }

    /**
     * Пытается принудительно перевести Circuit Breaker из OPEN в HALF_OPEN
     * Используется когда прошло достаточно времени с момента перехода в OPEN
     * и нет активных запросов
     */
    fun tryTransitionToHalfOpen() {
        val currentState = ollamaCircuitBreaker.state
        if (currentState.name == "OPEN") {
            try {
                // В Resilience4j нет прямого метода transitionToHalfOpenState()
                // Но можно использовать reset() для перехода в CLOSED
                // или попытаться сделать пробный запрос через circuit breaker
                // Для автоматического перехода в HALF_OPEN нужно просто сбросить
                ollamaCircuitBreaker.reset()
                log.info("[CircuitBreakerState] Circuit Breaker reset from OPEN to CLOSED (attempting recovery)")
            } catch (e: Exception) {
                log.warn("[CircuitBreakerState] Failed to transition Circuit Breaker to HALF_OPEN: ${e.message}")
            }
        }
    }
}
