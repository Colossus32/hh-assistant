package com.hhassistant.health

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Health indicator для проверки доступности Ollama API.
 */
@Component
class OllamaHealthIndicator(
    @Qualifier("ollamaWebClient") private val webClient: WebClient,
) : HealthIndicator {

    override fun health(): Health {
        return try {
            runBlocking {
                // Проверяем доступность API через запрос списка моделей
                val response = webClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono<Map<String, Any>>()
                    .awaitSingle()

                Health.up()
                    .withDetail("status", "available")
                    .withDetail("models", response["models"] ?: "unknown")
                    .build()
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
