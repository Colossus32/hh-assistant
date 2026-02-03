package com.hhassistant.config

import com.hhassistant.client.ProxyManager
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.util.retry.Retry
import java.time.Duration

@Configuration
class WebClientConfig(
    private val proxyManager: ProxyManager,
    @Value("\${hh.retry.max-attempts}") private val retryMaxAttempts: Long,
    @Value("\${hh.retry.initial-delay-seconds}") private val retryInitialDelaySeconds: Long,
    @Value("\${hh.retry.max-backoff-seconds}") private val retryMaxBackoffSeconds: Long,
    @Value("\${hh.retry.rate-limit-status-code}") private val retryRateLimitStatusCode: Int,
) {
    private val log = KotlinLogging.logger {}

    @Bean
    @Qualifier("hhWebClient")
    fun hhWebClient(
        @Value("\${hh.api.base-url}") baseUrl: String,
        @Value("\${hh.api.access-token:}") accessToken: String,
        @Value("\${hh.api.user-agent}") userAgent: String,
        @Value("\${hh.api.auth-prefix}") authPrefix: String,
        @Value("\${hh.api.accept-header}") acceptHeader: String,
        @Value("\${hh.api.max-in-memory-size-mb:10}") maxInMemorySizeMb: Int,
    ): WebClient {
        // HH.ru API требует обязательный заголовок HH-User-Agent (не User-Agent!)
        // Формат: "AppName/Version (contact@email.com)"
        // Согласно документации: https://api.hh.ru/openapi/redoc#tag/Vakansii/operation/get-vacancies
        // HH-User-Agent является required header parameter
        var builder = WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(proxyManager.getConnector())

        if (userAgent.isNotBlank() && !userAgent.contains("example.com", ignoreCase = true)) {
            // Добавляем HH-User-Agent только если указан реальный (не example.com)
            builder = builder.defaultHeader("HH-User-Agent", userAgent)
            log.info("✅ [WebClient] HH.ru WebClient configured with HH-User-Agent: $userAgent")
        } else {
            // Если не указан или содержит example.com, используем минимальный формат
            // ВАЖНО: HH-User-Agent обязателен, поэтому используем минимальный формат
            val defaultUserAgent = "HH-Assistant/1.0"
            builder = builder.defaultHeader("HH-User-Agent", defaultUserAgent)
            log.warn(
                "⚠️ [WebClient] HH-User-Agent не указан или содержит example.com - используем минимальный формат: $defaultUserAgent",
            )
            log.warn(
                "⚠️ [WebClient] Для production установите HH_USER_AGENT в .env: 'HH-Assistant/1.0 (your@email.com)'",
            )
        }

        builder = builder
            .defaultHeader(HttpHeaders.ACCEPT, acceptHeader)
            .codecs { it.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .filter(WebClientRequestLoggingFilter.create())
            .filter(retryFilter())
            .filter(errorLoggingFilter())

        // Добавляем Authorization header только если токен указан
        if (accessToken.isNotBlank()) {
            val authHeader = "$authPrefix $accessToken"
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
            log.info("✅ [WebClient] HH.ru WebClient configured with Authorization header")
            log.info("   Token length: ${accessToken.length} chars")
            log.info("   Token prefix: ${accessToken.take(15)}...")
            log.info(
                "   Token type: ${if (accessToken.startsWith(
                        "APP",
                    )
                ) {
                    "Application token"
                } else if (accessToken.startsWith("USER")) "User token" else "Unknown"}",
            )
            log.info("   Auth prefix: $authPrefix")
            log.info("   Full header will be: $authPrefix ${accessToken.take(15)}...")
        } else {
            log.error("❌ [WebClient] HH.ru WebClient configured WITHOUT Authorization header (accessToken is blank)")
            log.error("   This will cause 403 Forbidden errors!")
            log.error("   Please set HH_ACCESS_TOKEN in .env file or get token via /oauth/application-token")
        }

        log.info("✅ [WebClient] HH.ru WebClient configured with maxInMemorySize: ${maxInMemorySizeMb}MB")

        return builder.build()
    }

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(
        @Value("\${ollama.base-url}") baseUrl: String,
        @Value("\${ollama.timeout-seconds}") timeoutSeconds: Long,
        @Value("\${ollama.max-in-memory-size-mb}") maxInMemorySizeMb: Int,
    ): WebClient {
        log.info("🔧 [WebClient] Configuring Ollama WebClient")
        log.info("   Base URL: $baseUrl")
        log.info("   Timeout: ${timeoutSeconds}s")
        log.info("   Max in-memory size: ${maxInMemorySizeMb}MB")

        // Предупреждение если используется localhost в Docker
        if (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")) {
            log.warn("⚠️ [WebClient] Ollama URL contains localhost/127.0.0.1")
            log.warn("   If running in Docker, this will NOT work!")
            log.warn("   Use 'host.docker.internal:11434' instead of 'localhost:11434'")
            log.warn("   Set OLLAMA_BASE_URL=http://host.docker.internal:11434 in docker-compose.yml or .env file")
        }

        val timeout = Duration.ofSeconds(timeoutSeconds)
        val httpClient = HttpClient.create()
            .responseTimeout(timeout)
            .doOnConnected { conn ->
                conn.addHandlerLast(
                    io.netty.handler.timeout.ReadTimeoutHandler(timeoutSeconds.toInt()),
                )
            }

        val webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .build()

        log.info("✅ [WebClient] Ollama WebClient configured successfully")

        return webClient
    }

    @Bean
    @Qualifier("telegramWebClient")
    fun telegramWebClient(
        @Value("\${telegram.api.base-url}") baseUrl: String,
        @Value("\${telegram.content-type}") contentType: String,
    ): WebClient {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, contentType)
            .filter(retryFilter())
            .build()
    }

    @Bean
    fun restTemplate(): org.springframework.web.client.RestTemplate {
        return org.springframework.web.client.RestTemplate()
    }

    @Bean
    @Qualifier("internalApiWebClient")
    fun internalApiWebClient(
        @Value("\${server.port:8080}") serverPort: Int,
    ): WebClient {
        // Для внутренних запросов всегда используем localhost с портом сервера
        // Это работает как локально, так и в Docker (внутри контейнера)
        val baseUrl = "http://localhost:$serverPort"
        log.info("🔧 [WebClient] Configuring Internal API WebClient with base URL: $baseUrl")
        return WebClient.builder()
            .baseUrl(baseUrl)
            .build()
    }

    private fun retryFilter(): ExchangeFilterFunction {
        return ExchangeFilterFunction.ofResponseProcessor { response ->
            if (response.statusCode().is5xxServerError ||
                response.statusCode().value() == retryRateLimitStatusCode
            ) {
                response.bodyToMono(String::class.java)
                    .flatMap { body ->
                        Mono.error<org.springframework.web.reactive.function.client.ClientResponse>(
                            RuntimeException("Server error: ${response.statusCode()} - $body"),
                        )
                    }
                    .retryWhen(
                        Retry.backoff(retryMaxAttempts, Duration.ofSeconds(retryInitialDelaySeconds))
                            .maxBackoff(Duration.ofSeconds(retryMaxBackoffSeconds)),
                    )
                    .cast(org.springframework.web.reactive.function.client.ClientResponse::class.java)
            } else {
                Mono.just(response)
            }
        }
    }

    private fun errorLoggingFilter(): ExchangeFilterFunction {
        return ExchangeFilterFunction.ofResponseProcessor { response ->
            if (response.statusCode().isError) {
                response.bodyToMono(String::class.java)
                    .doOnNext { body ->
                        log.error { "Error response: ${response.statusCode()} - $body" }
                    }
                    .then(Mono.just(response))
            } else {
                Mono.just(response)
            }
        }
    }
}
