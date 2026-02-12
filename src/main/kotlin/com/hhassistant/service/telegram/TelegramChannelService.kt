package com.hhassistant.service.telegram

import com.hhassistant.client.telegram.TelegramChannelClient
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
        
        // Проверяем, что канал существует и доступен
        val chatInfo = telegramChannelClient.getChatInfo(normalizedUsername)
            ?: throw TelegramException.ChannelNotFoundException("Channel $normalizedUsername not found or bot doesn't have access")
        
        // Создаем запись о канале
        val channel = TelegramChannel(
            channelUsername = normalizedUsername,
            channelId = chatInfo.id,
            displayName = chatInfo.displayName,
            channelType = chatInfo.channelType,
            isActive = true,
            isMonitored = false, // Не мониторим по умолчанию
            addedBy = addedBy,
        )
        
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
