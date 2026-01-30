package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.OAuthTokenResponse
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

@Service
class HHOAuthService(
    @Value("\${hh.oauth.client-id}") private val clientId: String,
    @Value("\${hh.oauth.client-secret}") private val clientSecret: String,
    @Value("\${hh.oauth.redirect-uri}") private val redirectUri: String,
    @Value("\${hh.oauth.token-url}") private val tokenUrl: String,
) {
    private val log = KotlinLogging.logger {}
    private val webClient = WebClient.builder()
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
            val response = webClient.post()
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
     * Генерирует URL для авторизации
     */
    fun getAuthorizationUrl(authorizationUrl: String): String {
        return "$authorizationUrl?response_type=code&client_id=$clientId&redirect_uri=$redirectUri"
    }
}

