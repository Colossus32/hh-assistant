package com.hhassistant.web

import com.hhassistant.client.hh.HHOAuthService
import com.hhassistant.client.hh.dto.OAuthTokenResponse
import com.hhassistant.service.EnvFileService
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
    private val envFileService: EnvFileService,
    @Value("\${hh.oauth.authorization-url}") private val authorizationUrl: String,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Генерирует URL для начала OAuth flow (токен пользователя)
     * GET /oauth/authorize
     */
    @GetMapping("/authorize")
    fun authorize(): ResponseEntity<Map<String, Any>> {
        val authUrl = oauthService.getAuthorizationUrl(authorizationUrl)
        log.info { "Generated authorization URL for user token: $authUrl" }
        return ResponseEntity.ok(
            mapOf(
                "authorization_url" to authUrl,
                "token_type" to "user",
                "message" to "Open this URL in your browser to authorize the application (user token)",
                "instructions" to listOf(
                    "1. Click on the authorization_url above or copy it to your browser",
                    "2. Authorize the application on HH.ru",
                    "3. You will be redirected back and the token will be automatically saved",
                    "4. Restart the application to use the new token",
                ),
                "auto_save" to "Token will be automatically saved to .env file after authorization",
                "note" to "For public API access, consider using application token instead: GET /oauth/application-token",
            ),
        )
    }

    /**
     * Получает токен приложения (application token)
     * GET /oauth/application-token
     * 
     * Токен приложения имеет неограниченный срок жизни и используется для доступа к публичному API вакансий.
     * Не требует авторизации пользователя.
     */
    @GetMapping("/application-token")
    fun getApplicationToken(
        @Value("\${hh.api.user-agent}") userAgent: String,
    ): ResponseEntity<Map<String, Any>> {
        log.info { "Requesting application token" }

        return try {
            val tokenResponse: OAuthTokenResponse = runBlocking {
                oauthService.getApplicationToken(userAgent)
            }

            log.info { "Successfully obtained application token" }

            // Автоматически сохраняем токен в .env файл
            val envUpdateSuccess = envFileService.updateEnvVariable("HH_ACCESS_TOKEN", tokenResponse.accessToken)
            
            // Также сохраняем тип токена
            envFileService.updateEnvVariable("HH_TOKEN_TYPE", "application")

            val responseBody = mutableMapOf<String, Any>(
                "success" to true,
                "token_type" to "application",
                "access_token" to tokenResponse.accessToken,
                "token_type_response" to tokenResponse.tokenType,
                "expires_in" to (tokenResponse.expiresIn ?: "unlimited"),
            )

            if (envUpdateSuccess) {
                responseBody["message"] = "✅ Application token obtained and automatically saved to .env file!"
                responseBody["auto_saved"] = true
                responseBody["next_steps"] = listOf(
                    "Application token has been saved to .env file",
                    "Application token has UNLIMITED lifetime (no expiration)",
                    "Restart the application to use the new token",
                )
                log.info("✅ [OAuth] Application token automatically saved to .env file")
            } else {
                responseBody["message"] = "⚠️ Application token obtained, but failed to save to .env file automatically"
                responseBody["auto_saved"] = false
                responseBody["manual_steps"] = listOf(
                    "Copy the access_token value above",
                    "Add it to your .env file: HH_ACCESS_TOKEN=<access_token>",
                    "Add: HH_TOKEN_TYPE=application",
                    "Restart the application",
                )
                log.warn("⚠️ [OAuth] Failed to automatically save application token to .env file")
            }

            responseBody["note"] = "Application token has unlimited lifetime and is suitable for public API access (vacancies search)"
            
            if (refreshTokenSaved) {
                responseBody["refresh_token_saved"] = true
                log.info("✅ [OAuth] Refresh token also saved to .env file")
            } else {
                responseBody["refresh_token_saved"] = false
                responseBody["refresh_token_note"] = "Application tokens typically don't have refresh tokens"
            }

            ResponseEntity.ok(responseBody)
        } catch (e: Exception) {
            log.error("Failed to get application token: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    mapOf(
                        "error" to "application_token_failed",
                        "message" to "Failed to get application token: ${e.message}",
                        "hint" to "Check your Client ID and Client Secret in .env file (HH_CLIENT_ID, HH_CLIENT_SECRET)",
                    ),
                )
        }
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

            // Автоматически сохраняем токен в .env файл
            val envUpdateSuccess = envFileService.updateEnvVariable("HH_ACCESS_TOKEN", tokenResponse.accessToken)
            
            // Если есть refresh token, сохраняем его тоже
            val refreshTokenSaved = tokenResponse.refreshToken?.let { refreshToken ->
                envFileService.updateEnvVariable("HH_REFRESH_TOKEN", refreshToken)
            } ?: false

            val responseBody = mutableMapOf<String, Any>(
                "success" to true,
                "access_token" to tokenResponse.accessToken,
                "token_type" to tokenResponse.tokenType,
                "expires_in" to (tokenResponse.expiresIn ?: "N/A"),
            )

            if (envUpdateSuccess) {
                responseBody["message"] = "✅ Access token obtained and automatically saved to .env file!"
                responseBody["auto_saved"] = true
                responseBody["next_steps"] = listOf(
                    "Token has been saved to .env file",
                    "Restart the application to use the new token",
                )
                log.info("✅ [OAuth] Access token automatically saved to .env file")
            } else {
                responseBody["message"] = "⚠️ Access token obtained, but failed to save to .env file automatically"
                responseBody["auto_saved"] = false
                responseBody["manual_steps"] = listOf(
                    "Copy the access_token value above",
                    "Add it to your .env file: HH_ACCESS_TOKEN=${tokenResponse.accessToken}",
                    "Restart the application",
                )
                log.warn("⚠️ [OAuth] Failed to automatically save token to .env file")
            }

            if (refreshTokenSaved) {
                responseBody["refresh_token_saved"] = true
                log.info("✅ [OAuth] Refresh token also saved to .env file")
            }

            ResponseEntity.ok(responseBody)
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

