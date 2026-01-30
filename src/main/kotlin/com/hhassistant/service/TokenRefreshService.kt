package com.hhassistant.service

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
     */
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000) // 12 hours
    fun checkAndRefreshToken() {
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
     */
    fun refreshTokenManually(): Boolean {
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
            } catch (e: Exception) {
                log.error("❌ [TokenRefresh] Failed to refresh token: ${e.message}", e)
                false
            }
        }
    }
}

