package com.hhassistant.client.telegram

import com.hhassistant.client.telegram.dto.SendMessageRequest
import com.hhassistant.client.telegram.dto.SendMessageResponse
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
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
     * @return true если сообщение успешно отправлено, false в противном случае
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
                log.error("Failed to send message to Telegram: ${response.description} (code: ${response.errorCode})")
                false
            }
        } catch (e: Exception) {
            log.error("Error sending message to Telegram: ${e.message}", e)
            false
        }
    }
}
