package com.hhassistant.client.telegram

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.telegram.dto.ChatHistoryResponse
import com.hhassistant.client.telegram.dto.ChannelMessage
import com.hhassistant.client.telegram.dto.ChatInfoDto
import com.hhassistant.client.telegram.dto.GetChatRequest
import com.hhassistant.client.telegram.dto.GetChatResponse
import com.hhassistant.client.telegram.dto.JoinChatRequest
import com.hhassistant.client.telegram.dto.LeaveChatRequest
import com.hhassistant.client.telegram.dto.SimpleResponse
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class TelegramChannelClient(
    @Qualifier("telegramWebClient") private val webClient: WebClient,
    @Value("\${telegram.bot-token}") private val botToken: String,
    private val webScraper: TelegramChannelWebScraper,
    @Value("\${telegram.use-web-scraping:true}") private val useWebScraping: Boolean = true,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает сообщения из канала
     * Использует веб-скрапинг для публичных каналов (по умолчанию) или API для приватных
     */
    @Loggable
    suspend fun getChannelMessages(
        channelUsername: String,
        limit: Int = 100,
        offset: Int = 0
    ): List<ChannelMessage> {
        // Пробуем веб-скрапинг сначала (не требует прав администратора)
        if (useWebScraping) {
            try {
                val messages = webScraper.scrapeChannelMessages(channelUsername, limit)
                if (messages.isNotEmpty()) {
                    log.debug("[TelegramChannelClient] Successfully scraped ${messages.size} messages from $channelUsername via web scraping")
                    return messages
                }
            } catch (e: Exception) {
                log.debug("[TelegramChannelClient] Web scraping failed for $channelUsername, trying API: ${e.message}")
            }
        }

        // Fallback к API методу (требует прав администратора)
        return try {
            val response = webClient.get()
                .uri("/bot$botToken/getChatHistory?chat_id=@$channelUsername&limit=$limit&offset=$offset")
                .retrieve()
                .bodyToMono<ChatHistoryResponse>()
                .awaitSingle()
            
            response.result?.filter { message: ChannelMessage -> message.text?.isNotBlank() == true } ?: emptyList()
        } catch (e: Exception) {
            log.error("Error fetching messages from channel $channelUsername via API: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Получает информацию о канале
     */
    @Loggable
    suspend fun getChatInfo(channelUsername: String): com.hhassistant.client.telegram.dto.ChatInfoDto? {
        return try {
            val request = GetChatRequest(chatId = "@$channelUsername")
            val response = webClient.post()
                .uri("/bot$botToken/getChat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<GetChatResponse>()
                .awaitSingle()
            
            if (response.ok) {
                response.result
            } else {
                log.warn("Failed to get chat info for $channelUsername: ${response.description}")
                null
            }
        } catch (e: Exception) {
            log.error("Error getting chat info for $channelUsername: ${e.message}", e)
            null
        }
    }

    /**
     * Присоединяет бота к каналу (для приватных каналов)
     */
    @Loggable
    suspend fun joinChat(channelUsername: String): Boolean {
        return try {
            val request = JoinChatRequest(chatId = "@$channelUsername")
            val response = webClient.post()
                .uri("/bot$botToken/joinChat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<SimpleResponse>()
                .awaitSingle()
            
            if (response.ok) {
                log.info("Successfully joined channel: $channelUsername")
                true
            } else {
                log.warn("Failed to join channel $channelUsername: ${response.description}")
                false
            }
        } catch (e: Exception) {
            log.error("Error joining chat $channelUsername: ${e.message}", e)
            false
        }
    }

    /**
     * Оставляет канал
     */
    @Loggable
    suspend fun leaveChat(channelUsername: String): Boolean {
        return try {
            val request = LeaveChatRequest(chatId = "@$channelUsername")
            val response = webClient.post()
                .uri("/bot$botToken/leaveChat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono<SimpleResponse>()
                .awaitSingle()
            
            if (response.ok) {
                log.info("Successfully left channel: $channelUsername")
                true
            } else {
                log.warn("Failed to leave channel $channelUsername: ${response.description}")
                false
            }
        } catch (e: Exception) {
            log.error("Error leaving chat $channelUsername: ${e.message}", e)
            false
        }
    }
}
