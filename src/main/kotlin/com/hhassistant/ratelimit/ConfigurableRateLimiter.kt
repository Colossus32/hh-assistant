package com.hhassistant.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.time.Duration

/**
 * Универсальный rate limiter на базе Token Bucket (Bucket4j).
 * Используется для Telegram, Ollama и других внешних API.
 */
class ConfigurableRateLimiter(
    private val requestsPerSecond: Int,
    private val burstCapacity: Int,
    private val waitOnLimitSeconds: Long,
    private val name: String,
) {
    private val log = KotlinLogging.logger {}
    private val bucket: Bucket = createBucket()

    private fun createBucket(): Bucket {
        val bandwidth = Bandwidth.builder()
            .capacity(burstCapacity.toLong())
            .refillIntervally(requestsPerSecond.toLong(), Duration.ofSeconds(1))
            .build()
        return Bucket.builder()
            .addLimit(bandwidth)
            .build()
    }

    /**
     * Резервирует токен перед запросом. При превышении лимита ждёт.
     */
    suspend fun tryConsume() {
        if (bucket.tryConsume(1)) {
            log.trace("[$name] Rate limit token consumed")
            return
        }
        log.debug("[$name] Rate limit exceeded, waiting ${waitOnLimitSeconds}s")
        delay(waitOnLimitSeconds * 1000)
        for (i in 0 until 20) {
            if (bucket.tryConsume(1)) return
            delay(500)
        }
        log.warn("[$name] Rate limit: prolonged wait, retrying")
        while (!bucket.tryConsume(1)) {
            delay(200)
        }
    }
}
