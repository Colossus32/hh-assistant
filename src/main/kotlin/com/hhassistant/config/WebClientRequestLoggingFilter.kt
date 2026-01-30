package com.hhassistant.config

import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction

/**
 * Фильтр для логирования запросов к HH.ru API (для диагностики)
 */
object WebClientRequestLoggingFilter {
    private val log = KotlinLogging.logger {}

    fun create(): ExchangeFilterFunction {
        return ExchangeFilterFunction.ofRequestProcessor { request ->
            // Логируем информацию о запросе (без токена)
            val authHeader = request.headers().getFirst(HttpHeaders.AUTHORIZATION)
            if (authHeader != null) {
                val tokenPrefix = if (authHeader.length > 20) {
                    authHeader.substring(0, 20) + "..."
                } else {
                    "***"
                }
                log.debug("🔑 [WebClient] Request to ${request.url()}: Authorization header present (${authHeader.length} chars, prefix: $tokenPrefix)")
            } else {
                log.warn("⚠️ [WebClient] Request to ${request.url()}: NO Authorization header!")
            }
            reactor.core.publisher.Mono.just(request)
        }
    }
}

