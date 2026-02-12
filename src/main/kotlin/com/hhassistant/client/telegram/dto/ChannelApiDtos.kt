package com.hhassistant.client.telegram.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.hhassistant.domain.entity.TelegramChannel

// DTO для работы с каналами
data class GetChatRequest(
    val chatId: String
)

data class GetChatResponse(
    val ok: Boolean,
    val result: ChatInfoDto?,
    val description: String?
)

data class ChatInfoDto(
    val id: Long,
    val type: String,
    val title: String?,
    val username: String?,
    @JsonProperty("first_name")
    val firstName: String?,
    @JsonProperty("last_name")
    val lastName: String?,
    val description: String?,
)

fun ChatInfoDto.toEntity() = TelegramChannel(
    channelId = id,
    channelUsername = username ?: "",
    displayName = title,
    channelType = when (type) {
        "channel" -> com.hhassistant.domain.entity.ChannelType.PUBLIC
        "supergroup" -> com.hhassistant.domain.entity.ChannelType.GROUP
        else -> com.hhassistant.domain.entity.ChannelType.PUBLIC
    },
    isActive = true,
    isMonitored = false,
)
)

data class JoinChatRequest(
    val chatId: String
)

data class LeaveChatRequest(
    val chatId: String
)

data class SimpleResponse(
    val ok: Boolean,
    val description: String?
)

data class ChannelMessage(
    val messageId: Long,
    val date: Long,
    val text: String?,
    val caption: String?,
    val entities: List<MessageEntity>?,
    val authorSignature: String?,
)

data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String?,
)

data class ChatHistoryResponse(
    val ok: Boolean,
    val result: List<ChannelMessage>?,
    val description: String?
)
