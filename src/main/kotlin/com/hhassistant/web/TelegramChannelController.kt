package com.hhassistant.web

import com.hhassistant.domain.entity.TelegramChannel
import com.hhassistant.service.telegram.TelegramChannelService
import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/telegram-channels")
class TelegramChannelController(
    private val telegramChannelService: TelegramChannelService,
) {

    @GetMapping
    fun getAllChannels(
        @RequestParam activeOnly: Boolean = false
    ): List<TelegramChannelDto> {
        val channels = if (activeOnly) {
            telegramChannelService.getActiveChannels()
        } else {
            telegramChannelService.getAllChannels()
        }
        return channels.map { it.toDto() }
    }

    @GetMapping("/{id}")
    fun getChannel(@PathVariable id: Long): TelegramChannelDto {
        return telegramChannelService.getChannelById(id).toDto()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun addChannel(
        @RequestBody @Valid request: AddChannelRequest,
        @RequestHeader("X-Telegram-Chat-Id", required = false) chatId: String?
    ): TelegramChannelDto {
        if (chatId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required header: X-Telegram-Chat-Id")
        }
        val channel = runBlocking {
            telegramChannelService.addChannel(request.channelUsername, chatId)
        }
        return channel.toDto()
    }

    @PostMapping("/{id}/monitor")
    fun startMonitoring(@PathVariable id: Long): TelegramChannelDto {
        return telegramChannelService.startMonitoring(id).toDto()
    }

    @PostMapping("/{id}/stop")
    fun stopMonitoring(@PathVariable id: Long): TelegramChannelDto {
        return telegramChannelService.stopMonitoring(id).toDto()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeChannel(@PathVariable id: Long) {
        telegramChannelService.removeChannel(id)
    }
}

data class AddChannelRequest(
    val channelUsername: String,
)

data class TelegramChannelDto(
    val id: Long?,
    val channelUsername: String,
    val displayName: String?,
    val isActive: Boolean,
    val isMonitored: Boolean,
    val lastMessageDate: String?,
    val addedAt: String?,
)

fun TelegramChannel.toDto() = TelegramChannelDto(
    id = id,
    channelUsername = channelUsername,
    displayName = displayName,
    isActive = isActive,
    isMonitored = isMonitored,
    lastMessageDate = lastMessageDate?.toString(),
    addedAt = addedAt.toString(),
)
