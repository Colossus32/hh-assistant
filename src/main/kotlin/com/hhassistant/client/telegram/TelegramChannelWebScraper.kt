package com.hhassistant.client.telegram

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.telegram.dto.ChannelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Веб-скрапер для парсинга публичных Telegram каналов через t.me/s/channel_name
 * Не требует прав администратора канала
 */
@Component
class TelegramChannelWebScraper(
    @Value("\${telegram.web-scraping.enabled:true}") private val webScrapingEnabled: Boolean,
    @Value("\${telegram.web-scraping.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36}") private val userAgent: String,
    @Value("\${telegram.web-scraping.timeout:10000}") private val timeout: Int = 10000,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает сообщения из публичного канала через веб-скрапинг
     * @param channelUsername Имя канала без @ (например, "devjobs_ua")
     * @param limit Максимальное количество сообщений для получения
     * @return Список сообщений из канала
     */
    @Loggable
    suspend fun scrapeChannelMessages(
        channelUsername: String,
        limit: Int = 100
    ): List<ChannelMessage> {
        if (!webScrapingEnabled) {
            log.debug("[WebScraper] Web scraping is disabled")
            return emptyList()
        }

        return try {
            val url = "https://t.me/s/$channelUsername"
            log.debug("[WebScraper] Scraping channel: $url")

            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeout)
                    .followRedirects(true)
                    .get()
            }

            val messages = parseMessages(document, limit)
            log.info("[WebScraper] Scraped ${messages.size} messages from channel $channelUsername")
            messages
        } catch (e: Exception) {
            log.error("[WebScraper] Error scraping channel $channelUsername: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Парсит сообщения из HTML документа
     */
    private fun parseMessages(document: Document, limit: Int): List<ChannelMessage> {
        val messages = mutableListOf<ChannelMessage>()

        // Ищем все сообщения в канале
        // Telegram использует класс tgme_widget_message для сообщений
        val messageElements = document.select("div.tgme_widget_message")
            .take(limit)

        for (element in messageElements) {
            try {
                val message = parseMessage(element)
                if (message != null) {
                    messages.add(message)
                }
            } catch (e: Exception) {
                log.warn("[WebScraper] Error parsing message element: ${e.message}", e)
            }
        }

        return messages.reversed() // Возвращаем в хронологическом порядке (старые первыми)
    }

    /**
     * Парсит одно сообщение из HTML элемента
     */
    private fun parseMessage(element: Element): ChannelMessage? {
        try {
            // Извлекаем ID сообщения из data-post атрибута
            val dataPost = element.attr("data-post")
            val messageId = extractMessageId(dataPost) ?: return null

            // Извлекаем дату сообщения
            val dateElement = element.select("time.tgme_widget_message_date").first()
            val date = if (dateElement != null) {
                val datetime = dateElement.attr("datetime")
                parseDate(datetime)
            } else {
                System.currentTimeMillis() / 1000 // Fallback к текущему времени
            }

            // Извлекаем текст сообщения
            val textElement = element.select("div.tgme_widget_message_text").first()
            val text = textElement?.text()?.trim()

            // Извлекаем подпись автора (если есть)
            val authorElement = element.select("a.tgme_widget_message_owner_name").first()
            val authorSignature = authorElement?.text()?.trim()

            // Извлекаем caption (для сообщений с медиа)
            val captionElement = element.select("div.tgme_widget_message_text.js-message_text").first()
            val caption = captionElement?.text()?.trim()

            return ChannelMessage(
                messageId = messageId,
                date = date,
                text = text,
                caption = caption,
                entities = null, // Веб-версия не предоставляет entities
                authorSignature = authorSignature,
            )
        } catch (e: Exception) {
            log.warn("[WebScraper] Error parsing message: ${e.message}", e)
            return null
        }
    }

    /**
     * Извлекает ID сообщения из data-post атрибута
     * Формат: "channel_name/123" -> 123
     */
    private fun extractMessageId(dataPost: String): Long? {
        return try {
            val parts = dataPost.split("/")
            if (parts.size >= 2) {
                parts.last().toLongOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("[WebScraper] Error extracting message ID from '$dataPost': ${e.message}")
            null
        }
    }

    /**
     * Парсит дату из ISO 8601 формата
     */
    private fun parseDate(dateString: String): Long {
        return try {
            val zonedDateTime = ZonedDateTime.parse(dateString)
            zonedDateTime.toEpochSecond()
        } catch (e: Exception) {
            log.warn("[WebScraper] Error parsing date '$dateString': ${e.message}")
            System.currentTimeMillis() / 1000
        }
    }

    /**
     * Проверяет, доступен ли канал для веб-скрапинга (публичный)
     */
    @Loggable
    suspend fun isChannelAccessible(channelUsername: String): Boolean {
        return try {
            val url = "https://t.me/s/$channelUsername"
            val document = withContext(Dispatchers.IO) {
                Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeout)
                    .followRedirects(true)
                    .get()
            }

            // Проверяем наличие сообщений или информации о канале
            val hasMessages = document.select("div.tgme_widget_message").isNotEmpty()
            val hasChannelInfo = document.select("div.tgme_channel_info").isNotEmpty()

            hasMessages || hasChannelInfo
        } catch (e: Exception) {
            log.debug("[WebScraper] Channel $channelUsername is not accessible via web scraping: ${e.message}")
            false
        }
    }
}
