package com.hhassistant.client.telegram

import com.hhassistant.client.telegram.dto.AnswerCallbackQueryRequest
import com.hhassistant.client.telegram.dto.AnswerCallbackQueryResponse
import com.hhassistant.client.telegram.dto.FileInfo
import com.hhassistant.client.telegram.dto.GetFileResponse
import com.hhassistant.client.telegram.dto.SendMessageRequest
import com.hhassistant.client.telegram.dto.SendMessageResponse
import com.hhassistant.config.AppConstants
import com.hhassistant.exception.TelegramException
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class TelegramClient(
    @Qualifier("telegramWebClient") private val webClient: WebClient,
    @Value("\${telegram.bot-token}") private val botToken: String,
    @Value("\${telegram.chat-id}") private val chatId: String,
    @Value("\${telegram.enabled:true}") private val enabled: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Отправляет сообщение в Telegram.
     *
     * @param text Текст сообщения для отправки
     * @param replyMarkup Опциональная inline keyboard для сообщения
     * @return true если сообщение успешно отправлено, false если отключено или не настроено
     * @throws TelegramException если произошла ошибка при отправке (rate limit, invalid chat, etc.)
     */
    suspend fun sendMessage(
        text: String,
        replyMarkup: com.hhassistant.client.telegram.dto.InlineKeyboardMarkup? = null,
    ): Boolean {
        if (!enabled) {
            log.debug("📱 [Telegram] Notifications are disabled, skipping message")
            return false
        }

        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("⚠️ [Telegram] Bot token or chat ID is not configured, skipping message")
            return false
        }

        log.info("📱 [Telegram] Sending message to chat ID: $chatId (message length: ${text.length} chars)")

        return try {
            val request = SendMessageRequest(
                chatId = chatId,
                text = text,
                parseMode = "HTML",
                disableWebPagePreview = false,
                replyMarkup = replyMarkup,
            )

            val sendStartTime = System.currentTimeMillis()
            val response = webClient.post()
                .uri("/bot$botToken/sendMessage")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<SendMessageResponse>()
                .awaitSingle()
            val sendDuration = System.currentTimeMillis() - sendStartTime

            if (response.ok) {
                log.info("✅ [Telegram] Message sent successfully to chat $chatId (took ${sendDuration}ms, message_id: ${response.result?.messageId ?: "N/A"})")
                true
            } else {
                val errorMessage = "Failed to send message to Telegram: ${response.description} (code: ${response.errorCode})"
                log.error(errorMessage)

                // Бросаем исключение для критичных ошибок
                when (response.errorCode) {
                    400 -> throw TelegramException.InvalidChatException(
                        "Invalid chat ID or message format: ${response.description}",
                    )
                    429 -> throw TelegramException.RateLimitException(
                        "Rate limit exceeded for Telegram API. Please wait before retrying.",
                    )
                    else -> throw TelegramException.APIException(errorMessage)
                }
            }
        } catch (e: TelegramException) {
            throw e
        } catch (e: WebClientResponseException) {
            // Логируем тело ответа для диагностики 400 ошибок
            val responseBody = try {
                e.responseBodyAsString
            } catch (ex: Exception) {
                "Unable to read response body: ${ex.message}"
            }
            log.error("Error sending message to Telegram API: ${e.statusCode} - ${e.message}")
            log.error("Response body: $responseBody")
            log.error("Request message length: ${text.length} chars")
            log.error("Request message preview: ${text.take(AppConstants.TextLimits.TELEGRAM_MESSAGE_PREVIEW_LENGTH)}...")
            throw mapToTelegramException(e, responseBody)
        } catch (e: Exception) {
            log.error("Unexpected error sending message to Telegram: ${e.message}", e)
            throw TelegramException.ConnectionException("Failed to connect to Telegram API: ${e.message}", e)
        }
    }

    /**
     * Получает информацию о файле по file_id
     */
    suspend fun getFile(fileId: String): FileInfo {
        if (!enabled || botToken.isBlank()) {
            throw TelegramException.APIException("Telegram is not configured")
        }

        return try {
            val response = webClient.get()
                .uri("/bot$botToken/getFile?file_id=$fileId")
                .retrieve()
                .bodyToMono<GetFileResponse>()
                .awaitSingle()

            if (response.ok && response.result != null) {
                response.result
            } else {
                throw TelegramException.APIException(
                    "Failed to get file info: ${response.description} (code: ${response.errorCode})",
                )
            }
        } catch (e: TelegramException) {
            throw e
        } catch (e: Exception) {
            log.error("Error getting file info from Telegram: ${e.message}", e)
            throw TelegramException.ConnectionException("Failed to get file info: ${e.message}", e)
        }
    }

    /**
     * Скачивает файл по file_path
     */
    suspend fun downloadFile(filePath: String): ByteArray {
        if (!enabled || botToken.isBlank()) {
            throw TelegramException.APIException("Telegram is not configured")
        }

        return try {
            webClient.get()
                .uri("https://api.telegram.org/file/bot$botToken/$filePath")
                .retrieve()
                .bodyToMono<ByteArray>()
                .awaitSingle()
        } catch (e: Exception) {
            log.error("Error downloading file from Telegram: ${e.message}", e)
            throw TelegramException.ConnectionException("Failed to download file: ${e.message}", e)
        }
    }

    /**
     * Отвечает на callback_query (обязательно для работы inline кнопок)
     *
     * @param callbackQueryId ID callback query из Update
     * @param text Опциональный текст для отображения пользователю
     * @param showAlert Показывать ли alert вместо toast уведомления
     */
    suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null,
        showAlert: Boolean = false,
    ): Boolean {
        if (!enabled || botToken.isBlank()) {
            log.warn("⚠️ [Telegram] Not configured, skipping answerCallbackQuery")
            return false
        }

        return try {
            val request = com.hhassistant.client.telegram.dto.AnswerCallbackQueryRequest(
                callbackQueryId = callbackQueryId,
                text = text,
                showAlert = showAlert,
            )

            val response = webClient.post()
                .uri("/bot$botToken/answerCallbackQuery")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<com.hhassistant.client.telegram.dto.AnswerCallbackQueryResponse>()
                .awaitSingle()

            if (response.ok) {
                log.debug("✅ [Telegram] Answered callback query $callbackQueryId")
                true
            } else {
                log.warn("⚠️ [Telegram] Failed to answer callback query: ${response.description} (code: ${response.errorCode})")
                false
            }
        } catch (e: Exception) {
            log.error("❌ [Telegram] Error answering callback query: ${e.message}", e)
            false
        }
    }

    private fun mapToTelegramException(e: WebClientResponseException, responseBody: String? = null): TelegramException {
        val errorDetails = responseBody ?: try {
            e.responseBodyAsString
        } catch (ex: Exception) {
            null
        }

        return when (e.statusCode) {
            HttpStatus.BAD_REQUEST -> {
                val errorMsg = buildString {
                    append("Invalid request to Telegram API: ${e.message}")
                    if (errorDetails != null) {
                        append(" | Response: $errorDetails")
                    }
                }
                TelegramException.InvalidChatException(errorMsg, e)
            }
            HttpStatus.UNAUTHORIZED -> TelegramException.APIException(
                "Unauthorized access to Telegram API. Check your bot token.",
                e,
            )
            HttpStatus.TOO_MANY_REQUESTS -> TelegramException.RateLimitException(
                "Rate limit exceeded for Telegram API. Please wait before retrying.",
                e,
            )
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE,
            -> TelegramException.ConnectionException(
                "Server error from Telegram API: ${e.statusCode}",
                e,
            )
            else -> TelegramException.APIException(
                "API error from Telegram: ${e.statusCode} - ${e.message}",
                e,
            )
        }
    }
}
