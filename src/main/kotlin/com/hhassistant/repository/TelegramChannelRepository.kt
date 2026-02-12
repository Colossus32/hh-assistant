package com.hhassistant.repository

import com.hhassistant.domain.entity.TelegramChannel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TelegramChannelRepository : JpaRepository<TelegramChannel, Long> {
    
    fun findByChannelUsername(channelUsername: String): TelegramChannel?
    
    fun findByIsActiveTrueAndIsMonitoredTrue(): List<TelegramChannel>
    
    fun findByIsActiveTrueOrderByAddedAtDesc(): List<TelegramChannel>
    
    fun findByAddedBy(addedBy: String): List<TelegramChannel>
    
    fun existsByChannelUsername(channelUsername: String): Boolean
}
