package com.hhassistant.service.telegram

import com.hhassistant.domain.entity.TelegramChannel
import com.hhassistant.domain.entity.ChannelType
import com.hhassistant.exception.TeleException
import com.hhassistant.repository.TelegramChannelRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class TelegramChannelServiceTest {
    private lateinit var telegramChannelRepository: TelegramChannelRepository
    private lateinit var telegramChannelClient: com.hhassistant.client.telegram.TelegramChannelClient
    private lateinit var telegramChannelService: TelegramChannelService

    @BeforeEach
    fun setUp() {
        telegramChannelRepository = mockk()
        telegramChannelClient = mockk()
        telegramChannelService = TelegramChannelService(telegramChannelRepository, telegramChannelClient)
    }

    @Test
    fun `should get all channels`() {
        // Given
        val channels = listOf(
            TelegramChannel(
                id = 1L,
                channelUsername = "test1",
                isActive = true,
                isMonitored = false,
                addedAt = java.time.LocalDateTime.now()
            ),
            TelegramChannel(
                id = 2L,
                channelUsername = "test2",
                isActive = true,
                isMonitored = true,
                addedAt = java.time.LocalDateTime.now()
            )
        )
        every { telegramChannelRepository.findByIsActiveTrueOrderByAddedAtDesc() } returns channels

        // When
        val result = telegramChannelService.getAllChannels()

        // Then
        assertEquals(2, result.size)
        assertEquals("test1", result[0].channelUsername)
        assertEquals("test2", result[1].channelUsername)
        verify(exactly = 1) { telegramChannelRepository.findByIsActiveTrueOrderByAddedAtDesc() }
    }

    @Test
    fun `should get only active monitored channels`() {
        // Given
        val channels = listOf(
            TelegramChannel(
                id = 1L,
                channelUsername = "test1",
                isActive = true,
                isMonitored = true,
                addedAt = java.time.LocalDateTime.now()
            ),
            TelegramChannel(
                id = 2L,
                channelUsername = "test2",
                isActive = true,
                isMonitored = false,
                addedAt = java.time.LocalDateTime.now()
            )
        )
        every { telegramChannelRepository.findByIsActiveTrueAndIsMonitoredTrue() } returns channels

        // When
        val result = telegramChannelService.getActiveChannels()

        // Then
        assertEquals(1, result.size)
        assertEquals("test1", result[0].channelUsername)
        verify(exactly = 1) { telegramChannelRepository.findByIsActiveTrueAndIsMonitoredTrue() }
    }

    @Test
    fun `should add new channel successfully`() = runBlocking {
        // Given
        val channelUsername = "new_channel"
        val addedBy = "user123"
        
        every { telegramChannelRepository.existsByChannelUsername(channelUsername) } returns false
        coEvery { 
            telegramChannelClient.getChatInfo(channelUsername) 
        } returns com.hhassistant.client.telegram.dto.ChatInfoDto(
            id = 12345L,
            type = "channel",
            title = "Test Channel",
            username = channelUsername,
            description = "Test Description"
        )
        every { telegramChannelRepository.save(any()) } returnsArgument {
            it.channelUsername == channelUsername && 
            it.channelId == 12345L &&
            it.displayName == "Test Channel" &&
            it.channelType == ChannelType.PUBLIC &&
            it.isActive == true &&
            it.isMonitored == false &&
            it.addedBy == addedBy
        }

        // When
        val result = telegramChannelService.addChannel(channelUsername, addedBy)

        // Then
        assertEquals("new_channel", result.channelUsername)
        assertEquals("Test Channel", result.displayName)
        assertEquals(false, result.isMonitored)
        verify(exactly = 1) { telegramChannelRepository.existsByChannelUsername(channelUsername) }
        coVerify(exactly = 1) { telegramChannelClient.getChatInfo(channelUsername) }
        verify(exactly = 1) { telegramChannelRepository.save(any()) }
    }

    @Test
    fun `should throw exception when adding existing channel`() = runBlocking {
        // Given
        val channelUsername = "existing_channel"
        val addedBy = "user123"
        
        every { telegramChannelRepository.existsByChannelUsername(channelUsername) } returns true

        // When & Then
        val exception = assertThrows<TelegramException.ChannelAlreadyExistsException> {
            telegramChannelService.addChannel(channelUsername, addedBy)
        }

        assertEquals("Channel existing_channel already exists", exception.message)
        verify(exactly = 1) { telegramChannelRepository.existsByChannelUsername(channelUsername) }
        coVerify(exactly = 0) { telegramChannelClient.getChatInfo(any()) }
    }

    @Test
    fun `should throw exception when channel not found`() = runBlocking {
        // Given
        val channelUsername = "nonexistent_channel"
        val addedBy = "user123"
        
        every { telegramChannelRepository.existsByChannelUsername(channelUsername) } returns false
        coEvery { 
            telegramChannelClient.getChatInfo(channelUsername) 
        } returns null

        // When & Then
        val exception = assertThrows<TelegramException.ChannelNotFoundException> {
            telegramChannelService.addChannel(channelUsername, addedBy)
        }

        assertEquals("Channel nonexistent_channel not found or bot doesn't have access", exception.message)
        verify(exactly = 1) { telegramChannelRepository.existsByChannelUsername(channelUsername) }
        coVerify(exactly = 1) { telegramChannelClient.getChatInfo(channelUsername) }
        verify(exactly = 0) { telegramChannelRepository.save(any()) }
    }

    @Test
    fun `should start monitoring`() {
        // Given
        val channelId = 1L
        val channel = TelegramChannel(
            id = channelId,
            channelUsername = "test_channel",
            isActive = true,
            isMonitored = false,
            addedAt = java.time.LocalDateTime.now()
        )
        every { telegramChannelRepository.findById(channelId) } returns Optional.of(channel)
        every { telegramChannelRepository.save(any()) } returnsArgument {
            it.isMonitored == true
        }

        // When
        val result = telegramChannelService.startMonitoring(channelId)

        // Then
        assertTrue(result.isMonitored)
        verify(exactly = 1) { telegramChannelRepository.findById(channelId) }
        verify(exactly = 1) { telegramChannelRepository.save(any()) }
    }

    @Test
    fun `should throw exception when starting monitoring for non-existent channel`() {
        // Given
        val channelId = 999L
        every { telegramChannelRepository.findById(channelId) } returns Optional.empty()

        // When & Then
        val exception = assertThrows<TelegramException.ChannelNotFoundException> {
            telegramChannelService.startMonitoring(channelId)
        }

        assertEquals("Channel with ID 999 not found", exception.message)
        verify(exactly = 1) { telegramChannelRepository.findById(channelId) }
        verify(exactly = 0) { telegramChannelRepository.save(any()) }
    }
}
