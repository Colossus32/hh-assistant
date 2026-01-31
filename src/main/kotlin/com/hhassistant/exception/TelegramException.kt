package com.hhassistant.exception

/**
 * Базовое исключение для ошибок, связанных с Telegram API.
 */
sealed class TelegramException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /**
     * Ошибка подключения к Telegram API.
     */
    class ConnectionException(message: String, cause: Throwable? = null) : TelegramException(message, cause)

    /**
     * Неверный chat ID.
     */
    class InvalidChatException(message: String, cause: Throwable? = null) : TelegramException(message, cause)

    /**
     * Превышен лимит запросов (Rate Limit).
     */
    class RateLimitException(message: String, cause: Throwable? = null) : TelegramException(message, cause)

    /**
     * Общая ошибка API.
     */
    class APIException(message: String, cause: Throwable? = null) : TelegramException(message, cause)
}


