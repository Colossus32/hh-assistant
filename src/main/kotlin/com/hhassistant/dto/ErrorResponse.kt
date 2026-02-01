package com.hhassistant.dto

import java.time.LocalDateTime

/**
 * Стандартизированный формат ответа об ошибке для REST API.
 * Используется для единообразной обработки ошибок во всех контроллерах.
 *
 * @param timestamp Время возникновения ошибки
 * @param status HTTP статус код
 * @param error Тип ошибки (код)
 * @param message Сообщение об ошибке для пользователя
 * @param details Дополнительные детали об ошибке (опционально)
 * @param path Путь к endpoint, где произошла ошибка
 */
data class ErrorResponse(
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val status: Int,
    val error: String,
    val message: String,
    val details: String? = null,
    val path: String? = null,
) {
    companion object {
        /**
         * Создает ErrorResponse для ошибки HH.ru API
         */
        fun hhApiError(
            message: String,
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 502,
                error = "HH_API_ERROR",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки авторизации
         */
        fun unauthorized(
            message: String = "Invalid or missing access token. Check HH_ACCESS_TOKEN in .env file.",
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 401,
                error = "UNAUTHORIZED",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки "не найдено"
         */
        fun notFound(
            message: String,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 404,
                error = "NOT_FOUND",
                message = message,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки Ollama API
         */
        fun ollamaError(
            message: String,
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 502,
                error = "OLLAMA_ERROR",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки Telegram API
         */
        fun telegramError(
            message: String,
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 502,
                error = "TELEGRAM_ERROR",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки превышения лимита запросов
         */
        fun rateLimit(
            message: String = "Rate limit exceeded. Please try again later.",
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 429,
                error = "RATE_LIMIT_EXCEEDED",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для ошибки валидации
         */
        fun badRequest(
            message: String,
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 400,
                error = "BAD_REQUEST",
                message = message,
                details = details,
                path = path,
            )
        }

        /**
         * Создает ErrorResponse для внутренней ошибки сервера
         */
        fun internalError(
            message: String = "An unexpected error occurred",
            details: String? = null,
            path: String? = null,
        ): ErrorResponse {
            return ErrorResponse(
                status = 500,
                error = "INTERNAL_ERROR",
                message = message,
                details = details,
                path = path,
            )
        }
    }
}






