package com.hhassistant.client.telegram

import com.hhassistant.client.telegram.dto.SendMessageRequest
import com.hhassistant.client.telegram.dto.SendMessageResponse
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
     * @return true если сообщение успешно отправлено, false если отключено или не настроено
     * @throws TelegramException если произошла ошибка при отправке (rate limit, invalid chat, etc.)
     */
    suspend fun sendMessage(text: String): Boolean {
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
            log.error("Request message preview: ${text.take(200)}...")
            throw mapToTelegramException(e, responseBody)
        } catch (e: Exception) {
            log.error("Unexpected error sending message to Telegram: ${e.message}", e)
            throw TelegramException.ConnectionException("Failed to connect to Telegram API: ${e.message}", e)
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
