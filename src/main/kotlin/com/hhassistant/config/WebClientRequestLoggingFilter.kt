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
            val hhUserAgentHeader = request.headers().getFirst("HH-User-Agent")
            val userAgentHeader = request.headers().getFirst(HttpHeaders.USER_AGENT)
            log.debug("🌐 [WebClient] Request to ${request.url()}: HH-User-Agent='$hhUserAgentHeader', User-Agent='$userAgentHeader'")
            
            val authHeader = request.headers().getFirst(HttpHeaders.AUTHORIZATION)
            if (authHeader != null) {
                val tokenPrefix = if (authHeader.length > 25) {
                    authHeader.substring(0, 25) + "..."
                } else {
                    "***"
                }
                val tokenType = when {
                    authHeader.contains("APP") -> "Application token"
                    authHeader.contains("USER") -> "User token"
                    else -> "Unknown token type"
                }
                log.info("🔑 [WebClient] Request to ${request.url()}: Authorization header present")
                log.info("   Header length: ${authHeader.length} chars")
                log.info("   Token type: $tokenType")
                log.info("   Header prefix: $tokenPrefix")
                // Проверяем формат токена
                if (!authHeader.startsWith("Bearer ")) {
                    log.error("❌ [WebClient] Authorization header does not start with 'Bearer '! Format: ${authHeader.take(15)}...")
                } else {
                    log.debug("✅ [WebClient] Authorization header format is correct (Bearer ...)")
                }
            } else {
                log.error("❌ [WebClient] Request to ${request.url()}: NO Authorization header! This will cause 403 Forbidden!")
                log.error("   Check if HH_ACCESS_TOKEN is set in .env file")
            }
            reactor.core.publisher.Mono.just(request)
        }
    }
}

