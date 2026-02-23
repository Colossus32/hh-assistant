package com.hhassistant.integration.hh

import com.hhassistant.integration.hh.dto.OAuthTokenResponse
import com.hhassistant.exception.HHAPIException
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class HHOAuthService(
    @Value("\${hh.oauth.client-id}") private val clientId: String,
    @Value("\${hh.oauth.client-secret}") private val clientSecret: String,
    @Value("\${hh.oauth.redirect-uri}") private val redirectUri: String,
    @Value("\${hh.oauth.token-url}") private val tokenUrl: String,
    @Value("\${hh.oauth.scope:}") private val scope: String?,
) {
    private val log = KotlinLogging.logger {}

    // WebClient для OAuth операций (токен пользователя)
    private val oauthWebClient = WebClient.builder()
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        .build()

    // WebClient для получения токена приложения (использует другой endpoint: api.hh.ru)
    private val apiWebClient = WebClient.builder()
        .baseUrl("https://api.hh.ru")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        .build()

    /**
     * Обменивает authorization code на access token
     */
    suspend fun exchangeCodeForToken(authorizationCode: String): OAuthTokenResponse {
        log.info { "Exchanging authorization code for access token" }

        val formData: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("code", authorizationCode)
            add("redirect_uri", redirectUri)
        }

        return try {
            val response = oauthWebClient.post()
                .uri(tokenUrl)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(OAuthTokenResponse::class.java)
                .awaitSingle()

            log.info { "Successfully obtained access token (expires in: ${response.expiresIn}s)" }
            response
        } catch (e: WebClientResponseException) {
            log.error("Failed to exchange code for token: ${e.statusCode} - ${e.responseBodyAsString}", e)
            throw when (e.statusCode.value()) {
                400 -> HHAPIException.APIException(
                    "Invalid authorization code or request parameters: ${e.responseBodyAsString}",
                    e,
                )
                401 -> HHAPIException.UnauthorizedException(
                    "Invalid client credentials: ${e.responseBodyAsString}",
                    e,
                )
                else -> HHAPIException.APIException(
                    "Failed to exchange authorization code: ${e.statusCode} - ${e.responseBodyAsString}",
                    e,
                )
            }
        } catch (e: Exception) {
            log.error("Unexpected error exchanging code for token: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru OAuth: ${e.message}", e)
        }
    }

    /**
     * Обновляет access token используя refresh token
     */
    suspend fun refreshAccessToken(refreshToken: String): OAuthTokenResponse {
        log.info { "Refreshing access token using refresh token" }

        val formData: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "refresh_token")
            add("client_id", clientId)
            add("client_secret", clientSecret)
            add("refresh_token", refreshToken)
        }

        return try {
            val response = oauthWebClient.post()
                .uri(tokenUrl)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(OAuthTokenResponse::class.java)
                .awaitSingle()

            log.info { "Successfully refreshed access token (expires in: ${response.expiresIn}s)" }
            response
        } catch (e: WebClientResponseException) {
            val responseBody = e.responseBodyAsString
            log.error("Failed to refresh token: ${e.statusCode} - $responseBody", e)

            // Парсим error_description из ответа для более точной обработки
            val errorDescription = extractErrorDescription(responseBody)

            throw when (e.statusCode.value()) {
                400 -> {
                    // Специальная обработка для разных типов ошибок 400
                    when {
                        errorDescription.contains("token not expired", ignoreCase = true) -> {
                            // Токен еще валиден - это не ошибка, но мы не можем обновить неистекший токен
                            log.info("ℹ️ [OAuth] Token is still valid (not expired), no need to refresh")
                            HHAPIException.APIException(
                                "Token is still valid and not expired. No refresh needed.",
                                e,
                            )
                        }
                        errorDescription.contains("token is empty", ignoreCase = true) -> {
                            HHAPIException.APIException(
                                "Refresh token is empty. Please provide a valid refresh token.",
                                e,
                            )
                        }
                        errorDescription.contains("token not found", ignoreCase = true) -> {
                            HHAPIException.UnauthorizedException(
                                "Refresh token not found or invalid. Please obtain a new token via OAuth flow.",
                                e,
                            )
                        }
                        errorDescription.contains("invalid_grant", ignoreCase = true) -> {
                            HHAPIException.UnauthorizedException(
                                "Invalid grant (refresh token): $errorDescription",
                                e,
                            )
                        }
                        else -> HHAPIException.APIException(
                            "Invalid refresh token or request parameters: $responseBody",
                            e,
                        )
                    }
                }
                401 -> HHAPIException.UnauthorizedException(
                    "Invalid client credentials or refresh token expired: $responseBody",
                    e,
                )
                else -> HHAPIException.APIException(
                    "Failed to refresh access token: ${e.statusCode} - $responseBody",
                    e,
                )
            }
        } catch (e: Exception) {
            log.error("Unexpected error refreshing token: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru OAuth: ${e.message}", e)
        }
    }

    /**
     * Получает токен приложения (application token)
     * Токен приложения имеет неограниченный срок жизни и используется для доступа к публичному API
     *
     * Согласно документации: https://api.hh.ru/openapi/redoc#tag/Avtorizaciya-prilozheniya/operation/authorize
     * Токен приложения получается через POST https://api.hh.ru/token с grant_type=client_credentials
     *
     * @param userAgent User-Agent header (HH-User-Agent) - название приложения и контактная почта
     * @param host Доменное имя сайта (по умолчанию "hh.ru")
     * @param locale Идентификатор локали (по умолчанию "RU")
     * @return OAuthTokenResponse с access_token приложения
     */
    suspend fun getApplicationToken(
        userAgent: String,
        host: String = "hh.ru",
        locale: String = "RU",
    ): OAuthTokenResponse {
        log.info { "Requesting application token (grant_type=client_credentials)" }
        log.info { "Using API endpoint: https://api.hh.ru/token (not OAuth endpoint)" }

        val formData: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_secret", clientSecret)
        }

        // Для токена приложения используется другой endpoint: https://api.hh.ru/token
        // (не https://hh.ru/oauth/token)
        return try {
            val response = apiWebClient.post()
                .uri { builder ->
                    builder.path("/token")
                        .queryParam("host", host)
                        .queryParam("locale", locale)
                        .build()
                }
                .header("HH-User-Agent", userAgent)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(OAuthTokenResponse::class.java)
                .awaitSingle()

            log.info { "Successfully obtained application token (expires in: ${response.expiresIn ?: "unlimited"}s)" }
            log.info { "Application token has unlimited lifetime and can be used for public API access" }
            response
        } catch (e: WebClientResponseException) {
            log.error("Failed to get application token: ${e.statusCode} - ${e.responseBodyAsString}", e)
            throw when (e.statusCode.value()) {
                400 -> HHAPIException.APIException(
                    "Invalid request parameters for application token: ${e.responseBodyAsString}",
                    e,
                )
                403 -> HHAPIException.UnauthorizedException(
                    "Invalid client credentials or insufficient permissions: ${e.responseBodyAsString}",
                    e,
                )
                else -> HHAPIException.APIException(
                    "Failed to get application token: ${e.statusCode} - ${e.responseBodyAsString}",
                    e,
                )
            }
        } catch (e: Exception) {
            log.error("Unexpected error getting application token: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    /**
     * Извлекает error_description из JSON ответа об ошибке
     */
    private fun extractErrorDescription(responseBody: String): String {
        return try {
            // Пытаемся найти error_description в JSON
            val errorDescMatch = Regex("\"error_description\"\\s*:\\s*\"([^\"]+)\"").find(responseBody)
            errorDescMatch?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Генерирует URL для авторизации пользователя (OAuth flow)
     */
    fun getAuthorizationUrl(authorizationUrl: String): String {
        val redirectUriEncoded = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        val baseUrl = "$authorizationUrl?response_type=code&client_id=$clientId&redirect_uri=$redirectUriEncoded"
        // Добавляем scope, если он указан
        return if (!scope.isNullOrBlank()) {
            val scopeEncoded = URLEncoder.encode(scope, StandardCharsets.UTF_8)
            "$baseUrl&scope=$scopeEncoded"
        } else {
            baseUrl
        }
    }
}
