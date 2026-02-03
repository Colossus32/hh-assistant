package com.hhassistant.service.telegram

import com.hhassistant.client.telegram.dto.User
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Сервис для проверки авторизации пользователей Telegram бота.
 * Проверяет, имеет ли пользователь право использовать команды бота.
 */
@Service
class TelegramAuthorizationService(
    @Value("\${telegram.authorization.enabled:true}") private val authorizationEnabled: Boolean,
    @Value("\${telegram.authorization.allowed-user-ids:}") private val allowedUserIdsStr: String,
    @Value("\${telegram.authorization.allowed-usernames:}") private val allowedUsernamesStr: String,
) {
    private val log = KotlinLogging.logger {}

    private val allowedUserIds: Set<Long> by lazy {
        if (allowedUserIdsStr.isBlank()) {
            emptySet()
        } else {
            allowedUserIdsStr.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
                .also { log.info("📋 [TelegramAuth] Loaded ${it.size} allowed user IDs: $it") }
        }
    }

    private val allowedUsernames: Set<String> by lazy {
        if (allowedUsernamesStr.isBlank()) {
            emptySet()
        } else {
            allowedUsernamesStr.split(",")
                .map { it.trim().lowercase() }
                .map { if (!it.startsWith("@")) "@$it" else it }
                .toSet()
                .also { log.info("📋 [TelegramAuth] Loaded ${it.size} allowed usernames: $it") }
        }
    }

    init {
        if (authorizationEnabled) {
            log.info("🔒 [TelegramAuth] Authorization enabled")
            log.info("📋 [TelegramAuth] Allowed user IDs: ${allowedUserIds.size}")
            log.info("📋 [TelegramAuth] Allowed usernames: ${allowedUsernames.size}")

            if (allowedUserIds.isEmpty() && allowedUsernames.isEmpty()) {
                log.warn("⚠️ [TelegramAuth] No allowed users configured! All commands will be denied.")
            }
        } else {
            log.info("🔓 [TelegramAuth] Authorization disabled - all users allowed")
        }
    }

    /**
     * Проверяет, имеет ли пользователь право использовать команды бота.
     *
     * @param user Пользователь Telegram (может быть null для анонимных сообщений)
     * @return true, если пользователь авторизован, false в противном случае
     */
    fun isAuthorized(user: User?): Boolean {
        if (!authorizationEnabled) {
            return true
        }

        if (user == null) {
            log.warn("⚠️ [TelegramAuth] User is null, denying access")
            return false
        }

        // Проверяем по user ID
        if (allowedUserIds.contains(user.id)) {
            log.debug("✅ [TelegramAuth] User ${user.id} authorized by user ID")
            return true
        }

        // Проверяем по username
        val userUsername = user.username?.lowercase()?.let {
            if (!it.startsWith("@")) "@$it" else it
        }
        if (userUsername != null && allowedUsernames.contains(userUsername)) {
            log.debug("✅ [TelegramAuth] User ${user.id} (@${user.username}) authorized by username")
            return true
        }

        log.warn("❌ [TelegramAuth] Access denied for user ID: ${user.id}, username: ${user.username ?: "N/A"}")
        return false
    }

    /**
     * Получает информацию о пользователе для логирования
     */
    fun getUserInfo(user: User?): String {
        if (user == null) {
            return "Unknown user"
        }
        return "User ID: ${user.id}, Username: ${user.username ?: "N/A"}"
    }
}
