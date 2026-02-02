package com.hhassistant.service.util

import com.hhassistant.client.hh.HHOAuthService
import com.hhassistant.client.hh.dto.OAuthTokenResponse
import com.hhassistant.exception.HHAPIException
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Сервис для автоматического обновления токена HH.ru при истечении
 */
@Service
class TokenRefreshService(
    private val oauthService: HHOAuthService,
    private val envFileService: EnvFileService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Проверяет и обновляет токен, если он истек или скоро истечет
     * Запускается каждые 12 часов (токен обычно живет 14 дней)
     *
     * Примечание: Не обновляет токен при старте, так как токен может быть еще валидным.
     * Обновление происходит только при ошибках 401/403 или по расписанию.
     * Application tokens не обновляются (они имеют неограниченный срок жизни).
     */
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000, initialDelay = 12 * 60 * 60 * 1000) // 12 hours, delay first run
    fun checkAndRefreshToken() {
        // Проверяем тип токена - application tokens не обновляются
        val tokenType = envFileService.readEnvVariable("HH_TOKEN_TYPE") ?: "user"
        if (tokenType == "application") {
            log.debug("ℹ️ [TokenRefresh] Application token detected, skipping refresh (application tokens have unlimited lifetime)")
            return
        }

        // Проверяем наличие refresh token перед попыткой обновления
        val refreshToken = envFileService.readEnvVariable("HH_REFRESH_TOKEN")
        if (refreshToken.isNullOrBlank()) {
            log.debug("ℹ️ [TokenRefresh] No refresh token found, skipping automatic refresh")
            return
        }

        log.info("🔄 [TokenRefresh] Attempting to refresh access token...")

        runBlocking {
            try {
                val tokenResponse: OAuthTokenResponse = oauthService.refreshAccessToken(refreshToken)

                // Сохраняем новый access token
                val accessTokenSaved = envFileService.updateEnvVariable("HH_ACCESS_TOKEN", tokenResponse.accessToken)

                // Сохраняем новый refresh token, если он был обновлен
                val refreshTokenSaved = tokenResponse.refreshToken?.let { newRefreshToken ->
                    envFileService.updateEnvVariable("HH_REFRESH_TOKEN", newRefreshToken)
                } ?: true

                if (accessTokenSaved && refreshTokenSaved) {
                    log.info("✅ [TokenRefresh] Successfully refreshed and saved access token")
                    log.info("✅ [TokenRefresh] Token expires in: ${tokenResponse.expiresIn ?: "unknown"} seconds")
                } else {
                    log.warn("⚠️ [TokenRefresh] Token refreshed but failed to save to .env file")
                }
            } catch (e: HHAPIException.APIException) {
                // Проверяем, не является ли это случаем "token not expired"
                if (e.message?.contains("Token is still valid", ignoreCase = true) == true ||
                    e.message?.contains("not expired", ignoreCase = true) == true
                ) {
                    log.info("ℹ️ [TokenRefresh] Token is still valid, no refresh needed: ${e.message}")
                    // Это не ошибка - токен еще валиден
                } else {
                    log.error("❌ [TokenRefresh] Failed to refresh token: ${e.message}", e)
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                log.error("❌ [TokenRefresh] Refresh token expired or invalid: ${e.message}", e)
                log.error("❌ [TokenRefresh] Please obtain a new token via OAuth flow")
            } catch (e: Exception) {
                log.error("❌ [TokenRefresh] Failed to refresh token: ${e.message}", e)
            }
        }
    }

    /**
     * Вручную обновляет токен (можно вызвать через API или при ошибке 401/403)
     * Не работает для application tokens (они имеют неограниченный срок жизни)
     */
    fun refreshTokenManually(): Boolean {
        // Проверяем тип токена - application tokens не обновляются
        val tokenType = envFileService.readEnvVariable("HH_TOKEN_TYPE") ?: "user"
        if (tokenType == "application") {
            log.info("ℹ️ [TokenRefresh] Application token detected, cannot refresh (application tokens have unlimited lifetime)")
            log.info("ℹ️ [TokenRefresh] If you get 403, the token may be invalid or the application may lack permissions")
            return false
        }

        val refreshToken = envFileService.readEnvVariable("HH_REFRESH_TOKEN")
        if (refreshToken.isNullOrBlank()) {
            log.warn("⚠️ [TokenRefresh] No refresh token found for manual refresh")
            return false
        }

        log.info("🔄 [TokenRefresh] Manual token refresh requested...")

        return runBlocking {
            try {
                val tokenResponse: OAuthTokenResponse = oauthService.refreshAccessToken(refreshToken)

                val accessTokenSaved = envFileService.updateEnvVariable("HH_ACCESS_TOKEN", tokenResponse.accessToken)
                val refreshTokenSaved = tokenResponse.refreshToken?.let { newRefreshToken ->
                    envFileService.updateEnvVariable("HH_REFRESH_TOKEN", newRefreshToken)
                } ?: true

                if (accessTokenSaved && refreshTokenSaved) {
                    log.info("✅ [TokenRefresh] Successfully refreshed and saved access token")
                    true
                } else {
                    log.warn("⚠️ [TokenRefresh] Token refreshed but failed to save to .env file")
                    false
                }
            } catch (e: HHAPIException.APIException) {
                // Проверяем, не является ли это случаем "token not expired"
                if (e.message?.contains("Token is still valid", ignoreCase = true) == true ||
                    e.message?.contains("not expired", ignoreCase = true) == true
                ) {
                    log.info("ℹ️ [TokenRefresh] Token is still valid, no refresh needed: ${e.message}")
                    // Токен валиден, возвращаем true (не ошибка)
                    true
                } else {
                    log.error("❌ [TokenRefresh] Failed to refresh token: ${e.message}", e)
                    false
                }
            } catch (e: Exception) {
                log.error("❌ [TokenRefresh] Failed to refresh token: ${e.message}", e)
                false
            }
        }
    }
}
