package com.hhassistant.advice

import com.hhassistant.dto.ErrorResponse
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.TelegramException
import jakarta.servlet.http.HttpServletRequest
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Глобальный обработчик исключений для всех REST API контроллеров.
 * Централизует обработку ошибок и стандартизирует формат ответов.
 *
 * Обрабатывает:
 * - HHAPIException и его подтипы
 * - OllamaException и его подтипы
 * - TelegramException и его подтипы
 * - Общие исключения
 */
@ControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает ошибки HH.ru API
     */
    @ExceptionHandler(HHAPIException.UnauthorizedException::class)
    fun handleUnauthorizedException(
        ex: HHAPIException.UnauthorizedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] HH.ru API Unauthorized: ${ex.message}", ex)
        val errorResponse = ErrorResponse.unauthorized(
            details = ex.message,
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки "не найдено" от HH.ru API
     */
    @ExceptionHandler(HHAPIException.NotFoundException::class)
    fun handleNotFoundException(
        ex: HHAPIException.NotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("⚠️ [GlobalExceptionHandler] Resource not found: ${ex.message}")
        val errorResponse = ErrorResponse.notFound(
            message = ex.message ?: "Resource not found",
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки превышения лимита запросов к HH.ru API
     */
    @ExceptionHandler(HHAPIException.RateLimitException::class)
    fun handleRateLimitException(
        ex: HHAPIException.RateLimitException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("⏸️ [GlobalExceptionHandler] Rate limit exceeded: ${ex.message}")
        val errorResponse = ErrorResponse.rateLimit(
            details = ex.message,
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки подключения к HH.ru API
     */
    @ExceptionHandler(HHAPIException.ConnectionException::class)
    fun handleConnectionException(
        ex: HHAPIException.ConnectionException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] HH.ru API connection error: ${ex.message}", ex)
        val errorResponse = ErrorResponse.hhApiError(
            message = "Failed to connect to HH.ru API",
            details = ex.message,
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse)
    }

    /**
     * Обрабатывает общие ошибки HH.ru API
     */
    @ExceptionHandler(HHAPIException::class)
    fun handleHHAPIException(
        ex: HHAPIException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] HH.ru API error: ${ex.message}", ex)
        val errorResponse = ErrorResponse.hhApiError(
            message = ex.message ?: "HH.ru API error occurred",
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки Ollama API
     */
    @ExceptionHandler(OllamaException::class)
    fun handleOllamaException(
        ex: OllamaException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] Ollama error: ${ex.message}", ex)
        val errorResponse = ErrorResponse.ollamaError(
            message = ex.message ?: "Ollama service error occurred",
            details = when (ex) {
                is OllamaException.ConnectionException -> "Failed to connect to Ollama service"
                is OllamaException.ParsingException -> "Failed to parse response from Ollama"
                is OllamaException.AnalysisException -> "Failed to analyze vacancy"
                is OllamaException.CoverLetterGenerationException -> "Failed to generate cover letter"
            },
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки Telegram API
     */
    @ExceptionHandler(TelegramException::class)
    fun handleTelegramException(
        ex: TelegramException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] Telegram error: ${ex.message}", ex)
        val errorResponse = ErrorResponse.telegramError(
            message = ex.message ?: "Telegram API error occurred",
            details = when (ex) {
                is TelegramException.ConnectionException -> "Failed to connect to Telegram API"
                is TelegramException.RateLimitException -> "Telegram API rate limit exceeded"
                is TelegramException.InvalidChatException -> "Invalid chat ID"
                is TelegramException.APIException -> "Telegram API error"
            },
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse)
    }

    /**
     * Обрабатывает ошибки валидации (IllegalArgumentException)
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("⚠️ [GlobalExceptionHandler] Validation error: ${ex.message}")
        val errorResponse = ErrorResponse.badRequest(
            message = ex.message ?: "Invalid request parameters",
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    /**
     * Обрабатывает все остальные необработанные исключения
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("❌ [GlobalExceptionHandler] Unexpected error: ${ex.message}", ex)
        val errorResponse = ErrorResponse.internalError(
            message = "An unexpected error occurred",
            details = if (log.isDebugEnabled) ex.message else null, // Показываем детали только в debug режиме
            path = request.requestURI,
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }
}
