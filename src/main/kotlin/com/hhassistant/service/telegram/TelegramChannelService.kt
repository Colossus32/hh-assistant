package com.hhassistant.service.telegram

import com.hhassistant.client.telegram.TelegramChannelClient
import com.hhassistant.client.telegram.TelegramChannelWebScraper
import com.hhassistant.client.telegram.dto.toEntity
import com.hhassistant.domain.entity.TelegramChannel
import com.hhassistant.exception.TelegramException
import com.hhassistant.repository.TelegramChannelRepository
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class TelegramChannelService(
    private val telegramChannelRepository: TelegramChannelRepository,
    private val telegramChannelClient: TelegramChannelClient,
    private val webScraper: TelegramChannelWebScraper,
) {
    private val log = KotlinLogging.logger {}
    
    fun getAllChannels(): List<TelegramChannel> {
        return telegramChannelRepository.findByIsActiveTrueOrderByAddedAtDesc()
    }
    
    fun getActiveChannels(): List<TelegramChannel> {
        return telegramChannelRepository.findByIsActiveTrueAndIsMonitoredTrue()
    }
    
    fun getChannelById(id: Long): TelegramChannel {
        return telegramChannelRepository.findById(id)
            .orElseThrow { TelegramException.ChannelNotFoundException("Channel with ID $id not found") }
    }
    
    suspend fun addChannel(channelUsername: String, addedBy: String): TelegramChannel {
        // Проверяем, существует ли канал
        val normalizedUsername = channelUsername.removePrefix("@")
        
        if (telegramChannelRepository.existsByChannelUsername(normalizedUsername)) {
            throw TelegramException.ChannelAlreadyExistsException("Channel $normalizedUsername already exists")
        }
        
        // Сначала проверяем доступность через веб-скрапинг (не требует прав администратора)
        val isAccessibleViaWeb = webScraper.isChannelAccessible(normalizedUsername)
        
        if (!isAccessibleViaWeb) {
            // Если веб-скрапинг не работает, пробуем через API (требует прав администратора)
            val chatInfo = telegramChannelClient.getChatInfo(normalizedUsername)
            if (chatInfo == null) {
                throw TelegramException.ChannelNotFoundException(
                    "Channel $normalizedUsername not found or not accessible. " +
                        "For public channels, ensure the channel is accessible at https://t.me/s/$normalizedUsername. " +
                        "For private channels, the bot must be added as administrator."
                )
            }
            
            // Создаем запись о канале используя toEntity() и обновляем поля
            val channel = chatInfo.toEntity().copy(
                channelUsername = normalizedUsername,
                isActive = true,
                isMonitored = false, // Не мониторим по умолчанию
                addedBy = addedBy,
            )
            
            return telegramChannelRepository.save(channel)
        }
        
        // Канал доступен через веб-скрапинг, создаем запись без API информации
        val channel = TelegramChannel(
            channelUsername = normalizedUsername,
            channelId = null, // Не знаем ID без API
            displayName = null, // Можно попробовать извлечь из веб-страницы позже
            channelType = com.hhassistant.domain.entity.ChannelType.PUBLIC,
            isActive = true,
            isMonitored = false, // Не мониторим по умолчанию
            addedBy = addedBy,
        )
        
        log.info("[TelegramChannelService] Added channel $normalizedUsername via web scraping (no admin rights required)")
        return telegramChannelRepository.save(channel)
    }
    
    fun startMonitoring(id: Long): TelegramChannel {
        val channel = getChannelById(id)
        val updated = channel.copy(isMonitored = true)
        return telegramChannelRepository.save(updated)
    }
    
    fun stopMonitoring(id: Long): TelegramChannel {
        val channel = getChannelById(id)
        val updated = channel.copy(isMonitored = false)
        return telegramChannelRepository.save(updated)
    }
    
    fun removeChannel(id: Long) {
        val channel = getChannelById(id)
        val updated = channel.copy(isActive = false)
        telegramChannelRepository.save(updated)
        
        // Оставляем канал в Telegram
        runBlocking {
            telegramChannelClient.leaveChat(channel.channelUsername)
        }
    }
}
