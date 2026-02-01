package com.hhassistant.exception

/**
 * Базовое исключение для ошибок, связанных с HH.ru API.
 */
sealed class HHAPIException(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /**
     * Ошибка подключения к HH.ru API.
     */
    class ConnectionException(message: String, cause: Throwable? = null) : HHAPIException(message, cause)

    /**
     * Превышен лимит запросов (Rate Limit).
     */
    class RateLimitException(message: String, cause: Throwable? = null) : HHAPIException(message, cause)

    /**
     * Вакансия или ресурс не найден.
     */
    class NotFoundException(message: String, cause: Throwable? = null) : HHAPIException(message, cause)

    /**
     * Ошибка авторизации (неверный токен).
     */
    class UnauthorizedException(message: String, cause: Throwable? = null) : HHAPIException(message, cause)

    /**
     * Общая ошибка API.
     */
    class APIException(message: String, cause: Throwable? = null) : HHAPIException(message, cause)
}






