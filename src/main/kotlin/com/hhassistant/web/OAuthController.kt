package com.hhassistant.web

import com.hhassistant.client.hh.HHOAuthService
import com.hhassistant.client.hh.dto.OAuthTokenResponse
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlinx.coroutines.runBlocking

/**
 * Контроллер для OAuth 2.0 авторизации с HH.ru
 */
@RestController
@RequestMapping("/oauth")
class OAuthController(
    private val oauthService: HHOAuthService,
    @Value("\${hh.oauth.authorization-url}") private val authorizationUrl: String,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Генерирует URL для начала OAuth flow
     * GET /oauth/authorize
     */
    @GetMapping("/authorize")
    fun authorize(): ResponseEntity<Map<String, String>> {
        val authUrl = oauthService.getAuthorizationUrl(authorizationUrl)
        log.info { "Generated authorization URL: $authUrl" }
        return ResponseEntity.ok(
            mapOf(
                "authorization_url" to authUrl,
                "message" to "Open this URL in your browser to authorize the application",
            ),
        )
    }

    /**
     * Callback endpoint для получения authorization code от HH.ru
     * GET /oauth/callback?code=XXX
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam("code", required = false) code: String?,
        @RequestParam("error", required = false) error: String?,
        @RequestParam("error_description", required = false) errorDescription: String?,
    ): ResponseEntity<Map<String, Any>> {
        if (error != null) {
            log.error { "OAuth error: $error - $errorDescription" }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    mapOf(
                        "error" to error,
                        "error_description" to (errorDescription ?: "Unknown error"),
                        "message" to "Authorization failed. Please try again.",
                    ),
                )
        }

        if (code == null) {
            log.error { "No authorization code received" }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                    mapOf(
                        "error" to "missing_code",
                        "message" to "Authorization code is missing. Please try again.",
                    ),
                )
        }

        log.info { "Received authorization code, exchanging for access token..." }

        return try {
            val tokenResponse: OAuthTokenResponse = runBlocking {
                oauthService.exchangeCodeForToken(code)
            }

            log.info { "Successfully obtained access token" }

            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "access_token" to tokenResponse.accessToken,
                    "token_type" to tokenResponse.tokenType,
                    "expires_in" to (tokenResponse.expiresIn ?: "N/A"),
                    "message" to "Access token obtained successfully. Add it to your .env file as HH_ACCESS_TOKEN",
                    "instructions" to mapOf(
                        "1" to "Copy the access_token value above",
                        "2" to "Add it to your .env file: HH_ACCESS_TOKEN=<access_token>",
                        "3" to "Restart the application",
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to exchange code for token: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    mapOf(
                        "error" to "token_exchange_failed",
                        "message" to "Failed to exchange authorization code for access token: ${e.message}",
                    ),
                )
        }
    }
}

