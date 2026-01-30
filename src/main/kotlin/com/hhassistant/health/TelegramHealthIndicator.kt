package com.hhassistant.health

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.io.File

/**
 * Health indicator для проверки доступности Telegram Bot API.
 */
@Component
class TelegramHealthIndicator(
    @Qualifier("telegramWebClient") private val webClient: WebClient,
    @Value("\${telegram.bot-token:}") private val botToken: String,
    @Value("\${telegram.enabled:true}") private val enabled: Boolean,
) : HealthIndicator {
    private val log = KotlinLogging.logger {}

    override fun health(): Health {
        if (!enabled || botToken.isBlank()) {
            val systemPropPresent = !System.getProperty("TELEGRAM_BOT_TOKEN").isNullOrBlank()
            val envPresent = !System.getenv("TELEGRAM_BOT_TOKEN").isNullOrBlank()
            val dotEnvExists = File(System.getProperty("user.dir"), ".env").exists()

            log.info {
                "Telegram health UNKNOWN: enabled=$enabled, botTokenPresent=${botToken.isNotBlank()}, " +
                    "systemPropertyPresent=$systemPropPresent, envVarPresent=$envPresent, dotEnvFileExists=$dotEnvExists"
            }

            return Health.unknown()
                .withDetail("status", "disabled")
                .withDetail("reason", "Telegram is disabled or token not configured")
                .withDetail("enabled", enabled)
                .withDetail("botTokenPresent", botToken.isNotBlank())
                .withDetail("systemPropertyPresent", systemPropPresent)
                .withDetail("envVarPresent", envPresent)
                .withDetail("dotEnvFileExists", dotEnvExists)
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
            log.warn(e) { "Telegram health DOWN: ${e.message ?: "Unknown error"}" }
            Health.down()
                .withDetail("status", "unavailable")
                .withDetail("error", e.message ?: "Unknown error")
                .withException(e)
                .build()
        }
    }
}
