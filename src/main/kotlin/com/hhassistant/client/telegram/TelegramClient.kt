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
            log.debug("Telegram notifications are disabled, skipping message")
            return false
        }

        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("Telegram bot token or chat ID is not configured, skipping message")
            return false
        }

        return try {
            val request = SendMessageRequest(
                chatId = chatId,
                text = text,
                parseMode = "HTML",
                disableWebPagePreview = false,
            )

            val response = webClient.post()
                .uri("/bot$botToken/sendMessage")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<SendMessageResponse>()
                .awaitSingle()

            if (response.ok) {
                log.debug("Message sent successfully to Telegram chat $chatId")
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
            log.error("Error sending message to Telegram API: ${e.message}", e)
            throw mapToTelegramException(e)
        } catch (e: Exception) {
            log.error("Unexpected error sending message to Telegram: ${e.message}", e)
            throw TelegramException.ConnectionException("Failed to connect to Telegram API: ${e.message}", e)
        }
    }

    private fun mapToTelegramException(e: WebClientResponseException): TelegramException {
        return when (e.statusCode) {
            HttpStatus.BAD_REQUEST -> TelegramException.InvalidChatException(
                "Invalid request to Telegram API: ${e.message}",
                e,
            )
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
