package com.hhassistant.config

import com.hhassistant.ratelimit.ConfigurableRateLimiter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RateLimitConfig {

    @Bean("telegramRateLimiter")
    fun telegramRateLimiter(
        @Value("\${telegram.rate-limit.requests-per-second:10}") requestsPerSecond: Int,
        @Value("\${telegram.rate-limit.burst-capacity:20}") burstCapacity: Int,
        @Value("\${telegram.rate-limit.wait-on-limit-seconds:1}") waitOnLimitSeconds: Long,
    ): ConfigurableRateLimiter = ConfigurableRateLimiter(
        requestsPerSecond = requestsPerSecond,
        burstCapacity = burstCapacity,
        waitOnLimitSeconds = waitOnLimitSeconds,
        name = "Telegram",
    )

    @Bean("ollamaRateLimiter")
    fun ollamaRateLimiter(
        @Value("\${ollama.rate-limit.requests-per-second:2}") requestsPerSecond: Int,
        @Value("\${ollama.rate-limit.burst-capacity:4}") burstCapacity: Int,
        @Value("\${ollama.rate-limit.wait-on-limit-seconds:1}") waitOnLimitSeconds: Long,
    ): ConfigurableRateLimiter = ConfigurableRateLimiter(
        requestsPerSecond = requestsPerSecond,
        burstCapacity = burstCapacity,
        waitOnLimitSeconds = waitOnLimitSeconds,
        name = "Ollama",
    )
}
