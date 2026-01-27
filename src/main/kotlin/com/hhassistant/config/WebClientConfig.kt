package com.hhassistant.config

import com.hhassistant.client.ProxyManager
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
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
        @Value("\${hh.api.access-token}") accessToken: String,
        @Value("\${hh.api.user-agent}") userAgent: String,
        @Value("\${hh.api.auth-prefix}") authPrefix: String,
        @Value("\${hh.api.accept-header}") acceptHeader: String,
    ): WebClient {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(proxyManager.getConnector())
            .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "$authPrefix $accessToken")
            .defaultHeader(HttpHeaders.ACCEPT, acceptHeader)
            .filter(retryFilter())
            .filter(errorLoggingFilter())
            .build()
    }

    @Bean
    @Qualifier("ollamaWebClient")
    fun ollamaWebClient(
        @Value("\${ollama.base-url}") baseUrl: String,
        @Value("\${ollama.timeout-seconds}") timeoutSeconds: Long,
        @Value("\${ollama.max-in-memory-size-mb}") maxInMemorySizeMb: Int,
    ): WebClient {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .filter(timeoutFilter(Duration.ofSeconds(timeoutSeconds)))
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

    private fun timeoutFilter(@Suppress("UNUSED_PARAMETER") timeout: Duration): ExchangeFilterFunction {
        // Timeout is handled by ReactorClientHttpConnector
        // This is a placeholder for future timeout logic
        return ExchangeFilterFunction.ofRequestProcessor { Mono.just(it) }
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
