package com.hhassistant.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Конфигурация для Circuit Breaker и Retry механизмов.
 * Используется для защиты от каскадных сбоев и автоматических повторов при ошибках.
 */
@Configuration
class CircuitBreakerConfig {
    private val log = KotlinLogging.logger {}

    @Bean
    fun circuitBreakerRegistry(
        @Value("\${resilience.circuit-breaker.failure-rate-threshold:50}") failureRateThreshold: Float,
        @Value("\${resilience.circuit-breaker.wait-duration-in-open-state-seconds:60}") waitDurationInOpenStateSeconds: Long,
        @Value("\${resilience.circuit-breaker.sliding-window-size:10}") slidingWindowSize: Int,
        @Value("\${resilience.circuit-breaker.minimum-number-of-calls:5}") minimumNumberOfCalls: Int,
    ): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(
                Exception::class.java,
            )
            .build()

        log.info("🔧 [Resilience] Circuit Breaker configured: failureRateThreshold=$failureRateThreshold%, waitDuration=${waitDurationInOpenStateSeconds}s, slidingWindow=$slidingWindowSize")

        return CircuitBreakerRegistry.of(config)
    }

    @Bean
    fun retryRegistry(
        @Value("\${resilience.retry.max-attempts:3}") maxAttempts: Int,
        @Value("\${resilience.retry.wait-duration-millis:1000}") waitDurationMillis: Long,
    ): RetryRegistry {
        val config = RetryConfig.custom<Any>()
            .maxAttempts(maxAttempts)
            .waitDuration(Duration.ofMillis(waitDurationMillis))
            .retryExceptions(Exception::class.java)
            .build()

        log.info("🔧 [Resilience] Retry configured: maxAttempts=$maxAttempts, waitDuration=${waitDurationMillis}ms")

        return RetryRegistry.of(config)
    }

    /**
     * Circuit Breaker для Ollama API
     */
    @Bean("ollamaCircuitBreaker")
    fun ollamaCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        return registry.circuitBreaker("ollama")
    }

    /**
     * Retry для Ollama API
     */
    @Bean("ollamaRetry")
    fun ollamaRetry(registry: RetryRegistry): Retry {
        return registry.retry("ollama")
    }

    /**
     * Circuit Breaker для HH.ru API
     */
    @Bean("hhApiCircuitBreaker")
    fun hhApiCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        return registry.circuitBreaker("hh-api")
    }

    /**
     * Retry для HH.ru API
     */
    @Bean("hhApiRetry")
    fun hhApiRetry(registry: RetryRegistry): Retry {
        return registry.retry("hh-api")
    }
}


