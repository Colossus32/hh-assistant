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
    ): WebClient {
        val builder = WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(proxyManager.getConnector())
            .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
            .defaultHeader(HttpHeaders.ACCEPT, acceptHeader)
            .filter(retryFilter())
            .filter(errorLoggingFilter())

        // Добавляем Authorization header только если токен указан
        if (accessToken.isNotBlank()) {
            val authHeader = "$authPrefix $accessToken"
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
            log.debug("✅ [WebClient] HH.ru WebClient configured with Authorization header (token length: ${accessToken.length}, prefix: $authPrefix)")
        } else {
            log.warn("⚠️ [WebClient] HH.ru WebClient configured WITHOUT Authorization header (accessToken is blank)")
        }

        return builder.build()
    }

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(
        @Value("\${ollama.base-url}") baseUrl: String,
        @Value("\${ollama.timeout-seconds}") timeoutSeconds: Long,
        @Value("\${ollama.max-in-memory-size-mb}") maxInMemorySizeMb: Int,
    ): WebClient {
        val timeout = Duration.ofSeconds(timeoutSeconds)
        val httpClient = HttpClient.create()
            .responseTimeout(timeout)
            .doOnConnected { conn ->
                conn.addHandlerLast(
                    io.netty.handler.timeout.ReadTimeoutHandler(timeoutSeconds.toInt()),
                )
            }

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .build()
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
