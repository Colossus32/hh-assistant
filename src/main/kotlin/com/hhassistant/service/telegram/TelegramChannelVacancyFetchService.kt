package com.hhassistant.service.telegram

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.telegram.TelegramChannelClient
import com.hhassistant.client.telegram.dto.ChannelMessage
import com.hhassistant.domain.entity.TelegramChannel
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.repository.TelegramChannelRepository
import com.hhassistant.repository.VacancyRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TelegramChannelVacancyFetchService(
    private val telegramChannelClient: TelegramChannelClient,
    private val telegramChannelRepository: TelegramChannelRepository,
    private val vacancyRepository: VacancyRepository,
    private val telegramVacancyParser: TelegramVacancyParser,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает вакансии из всех активных и монитируемых каналов
     */
    @Loggable
    suspend fun fetchVacanciesFromChannels(): List<Vacancy> {
        val channels = telegramChannelRepository.findByIsActiveTrueAndIsMonitoredTrue()
        
        if (channels.isEmpty()) {
            log.debug("[TelegramChannels] No active monitored channels found")
            return emptyList()
        }
        
        log.info("[TelegramChannels] Fetching vacancies from ${channels.size} channels")
        
        val allVacancies = mutableListOf<Vacancy>()
        
        for (channel in channels) {
            try {
                val vacancies = fetchVacanciesFromChannel(channel)
                allVacancies.addAll(vacancies)
            } catch (e: Exception) {
                log.error("[TelegramChannels] Error fetching from channel ${channel.channelUsername}: ${e.message}", e)
            }
        }
        
        log.info("[TelegramChannels] Fetched ${allVacancies.size} vacancies from Telegram channels")
        return allVacancies
    }

    /**
     * Получает вакансии из одного канала
     */
    @Loggable
    private suspend fun fetchVacanciesFromChannel(channel: TelegramChannel): List<Vacancy> {
        log.debug("[TelegramChannels] Fetching from channel: ${channel.channelUsername}")
        
        // Получаем сообщения из канала
        val messages = telegramChannelClient.getChannelMessages(
            channelUsername = channel.channelUsername,
            limit = 100
        )
        
        if (messages.isEmpty()) {
            log.debug("[TelegramChannels] No messages found in channel ${channel.channelUsername}")
            return emptyList()
        }
        
        log.debug("[TelegramChannels] Found ${messages.size} messages in channel ${channel.channelUsername}")
        
        // Фильтруем только новые сообщения (после последнего обработанного)
        val newMessages = channel.lastMessageId?.let { lastId ->
            messages.filter { it.messageId > lastId }
        } ?: messages
        
        log.debug("[TelegramChannels] ${newMessages.size} new messages to process")
        
        // Парсим вакансии из сообщений
        val vacancies = newMessages.mapNotNull { message ->
            telegramVacancyParser.parseVacancy(message, channel.channelUsername)
        }
        
        if (vacancies.isNotEmpty()) {
            log.debug("[TelegramChannels] Parsed ${vacancies.size} vacancies from channel ${channel.channelUsername}")
        }
        
        return vacancies
    }

    /**
     * Сохраняет вакансии и обновляет канал
     */
    @Loggable
    @Transactional
    fun saveVacanciesAndUpdateChannel(vacancies: List<Vacancy>, channel: TelegramChannel) {
        val savedVacancies = mutableListOf<Vacancy>()
        
        for (vacancy in vacancies) {
            try {
                // Проверяем, существует ли уже вакансия (по messageId + channelUsername)
                val existingVacancy = vacancyRepository.findByMessageIdAndChannelUsername(
                    vacancy.messageId!!, 
                    vacancy.channelUsername!!
                )
                
                if (existingVacancy == null) {
                    val saved = vacancyRepository.save(vacancy)
                    savedVacancies.add(saved)
                    log.debug("[TelegramChannels] Saved vacancy: ${saved.id}")
                }
            } catch (e: Exception) {
                log.error("[TelegramChannels] Error saving vacancy: ${e.message}", e)
            }
        }
        
        // Обновляем lastMessageId канала
        if (vacancies.isNotEmpty()) {
            val maxMessageId = vacancies.maxOfOrNull { it.messageId?.toLongOrNull() ?: 0L }
            if (maxMessageId != null && maxMessageId > (channel.lastMessageId ?: 0)) {
                val updatedChannel = channel.copy(
                    lastMessageId = maxMessageId,
                    lastMessageDate = java.time.LocalDateTime.now()
                )
                telegramChannelRepository.save(updatedChannel)
                log.debug("[TelegramChannels] Updated channel ${channel.channelUsername} lastMessageId: $maxMessageId")
            }
        }
        
        log.info("[TelegramChannels] Saved ${savedVacancies.size} new vacancies from channel ${channel.channelUsername}")
    }
}
