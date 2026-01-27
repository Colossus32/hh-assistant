package com.hhassistant.health

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Health indicator для проверки доступности Telegram Bot API.
 */
@Component
class TelegramHealthIndicator(
    @Qualifier("telegramWebClient") private val webClient: WebClient,
    @Value("\${telegram.bot-token:}") private val botToken: String,
    @Value("\${telegram.enabled:true}") private val enabled: Boolean,
) : HealthIndicator {

    override fun health(): Health {
        if (!enabled || botToken.isBlank()) {
            return Health.unknown()
                .withDetail("status", "disabled")
                .withDetail("reason", "Telegram is disabled or token not configured")
                .build()
        }

        return try {
            runBlocking {
                // Проверяем доступность API через запрос информации о боте
                val response = webClient.get()
                    .uri("/bot$botToken/getMe")
                    .retrieve()
                    .bodyToMono<Map<String, Any>>()
                    .awaitSingle()

                val ok = response["ok"] as? Boolean ?: false
                if (ok) {
                    val result = response["result"] as? Map<*, *> ?: emptyMap<Any, Any>()
                    Health.up()
                        .withDetail("status", "available")
                        .withDetail("bot_username", result["username"] ?: "unknown")
                        .withDetail("bot_id", result["id"] ?: "unknown")
                        .build()
                } else {
                    Health.down()
                        .withDetail("status", "unavailable")
                        .withDetail("error", "API returned ok=false")
                        .build()
                }
            }
        } catch (e: Exception) {
            Health.down()
                .withDetail("status", "unavailable")
                .withDetail("error", e.message ?: "Unknown error")
                .withException(e)
                .build()
        }
    }
}
