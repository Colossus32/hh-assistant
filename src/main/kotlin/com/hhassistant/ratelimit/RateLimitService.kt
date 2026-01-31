package com.hhassistant.ratelimit

import com.hhassistant.exception.HHAPIException
import com.hhassistant.metrics.MetricsService
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Сервис для управления rate limiting для HH.ru API.
 * Использует Token Bucket алгоритм для контроля частоты запросов.
 */
@Service
class RateLimitService(
    @Value("\${hh.rate-limit.requests-per-minute:20}") private val requestsPerMinute: Int,
    @Value("\${hh.rate-limit.burst-capacity:30}") private val burstCapacity: Int,
    @Value("\${hh.rate-limit.wait-on-limit-seconds:60}") private val waitOnLimitSeconds: Long,
    private val metricsService: MetricsService,
) {
    private val log = KotlinLogging.logger {}
    private val bucket: Bucket = createBucket()
    private lateinit var rateLimitExceededCounter: io.micrometer.core.instrument.Counter
    private lateinit var rateLimitWaitCounter: io.micrometer.core.instrument.Counter

    init {
        val meterRegistry = metricsService.getMeterRegistry()

        // Инициализируем счетчики
        rateLimitExceededCounter = io.micrometer.core.instrument.Counter.builder("rate_limit.exceeded")
            .description("Total number of times rate limit was exceeded")
            .register(meterRegistry)

        rateLimitWaitCounter = io.micrometer.core.instrument.Counter.builder("rate_limit.waits")
            .description("Total number of times rate limit wait was triggered")
            .register(meterRegistry)

        // Регистрируем gauge метрики для rate limiting
        registerMetrics(meterRegistry)
    }

    /**
     * Создает bucket с настройками rate limiting.
     */
    private fun createBucket(): Bucket {
        val bandwidth = Bandwidth.builder()
            .capacity(burstCapacity.toLong())
            .refillIntervally(requestsPerMinute.toLong(), Duration.ofMinutes(1))
            .build()
        return Bucket.builder()
            .addLimit(bandwidth)
            .build()
    }

    /**
     * Регистрирует метрики для мониторинга rate limiting.
     */
    private fun registerMetrics(meterRegistry: io.micrometer.core.instrument.MeterRegistry) {
        // Gauge для доступных токенов
        io.micrometer.core.instrument.Gauge.builder("rate_limit.available_tokens") { bucket.availableTokens.toDouble() }
            .description("Number of available rate limit tokens")
            .register(meterRegistry)

        // Gauge для использованных токенов
        io.micrometer.core.instrument.Gauge.builder("rate_limit.used_tokens") { getUsedTokens().toDouble() }
            .description("Number of used rate limit tokens")
            .register(meterRegistry)

        // Gauge для процента использования
        io.micrometer.core.instrument.Gauge.builder("rate_limit.usage_percent") {
            (getUsedTokens().toDouble() / burstCapacity.toDouble() * 100.0).coerceAtMost(100.0)
        }
            .description("Rate limit usage percentage")
            .register(meterRegistry)
    }

    /**
     * Проверяет, можно ли выполнить запрос, и резервирует токен.
     * Если токен недоступен, ждет до указанного времени или выбрасывает исключение.
     *
     * @return true, если токен был получен, false если нужно подождать
     * @throws HHAPIException.RateLimitException если лимит превышен и ожидание не помогло
     */
    suspend fun tryConsume(): Boolean {
        if (bucket.tryConsume(1)) {
            log.debug("Rate limit token consumed. Remaining: ${bucket.availableTokens}")
            return true
        }

        val availableTokens = bucket.availableTokens
        log.warn("Rate limit exceeded. Available tokens: $availableTokens. Waiting ${waitOnLimitSeconds}s...")
        
        // Инкрементируем счетчик превышений лимита
        rateLimitExceededCounter.increment()
        rateLimitWaitCounter.increment()

        // Ждем указанное время перед повторной попыткой
        delay(waitOnLimitSeconds * 1000)

        // Повторная попытка после ожидания
        if (bucket.tryConsume(1)) {
            log.info("Rate limit token obtained after waiting")
            return true
        }

        // Если после ожидания все еще нет токена, выбрасываем исключение
        throw HHAPIException.RateLimitException(
            "Rate limit exceeded for HH.ru API. " +
                "Requests per minute: $requestsPerMinute, " +
                "Burst capacity: $burstCapacity. " +
                "Please wait before retrying.",
        )
    }

    /**
     * Получает количество доступных токенов.
     */
    fun getAvailableTokens(): Long {
        return bucket.availableTokens
    }

    /**
     * Получает количество использованных токенов.
     */
    fun getUsedTokens(): Long {
        return burstCapacity.toLong() - bucket.availableTokens
    }

    /**
     * Сбрасывает bucket (для тестирования).
     */
    fun reset() {
        bucket.reset()
    }
}
