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
 * Health indicator для проверки доступности HH.ru API.
 */
@Component
class HHAPIHealthIndicator(
    @Qualifier("hhWebClient") private val webClient: WebClient,
) : HealthIndicator {

    override fun health(): Health {
        return try {
            runBlocking {
                // Проверяем доступность API через запрос информации о пользователе
                val response = webClient.get()
                    .uri("/me")
                    .retrieve()
                    .bodyToMono<Map<String, Any>>()
                    .awaitSingle()

                Health.up()
                    .withDetail("status", "available")
                    .withDetail("user", response["last_name"] ?: "unknown")
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
