package com.hhassistant.domain.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "telegram_channels", uniqueConstraints = [
    UniqueConstraint(name = "uk_channel_username", columnNames = ["channel_username"])
])
data class TelegramChannel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "channel_username", length = 255, nullable = false, unique = true)
    val channelUsername: String,  // @channel_name без @

    @Column(name = "channel_id")
    val channelId: Long? = null,  // Telegram ID канала

    @Column(name = "display_name", length = 255)
    val displayName: String? = null,  // Отображаемое имя канала

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", length = 20)
    val channelType: ChannelType = ChannelType.PUBLIC,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @Column(name = "is_monitored", nullable = false)
    val isMonitored: Boolean = false,  // Отслеживается ли канал

    @Column(name = "last_message_id")
    val lastMessageId: Long? = null,  // ID последнего обработанного сообщения

    @Column(name = "last_message_date")
    val lastMessageDate: LocalDateTime? = null,

    @Column(name = "added_at", nullable = false)
    val addedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "added_by")
    val addedBy: String? = null,  // Telegram chat ID пользователя, добавившего канал

    @Column(name = "notes", columnDefinition = "TEXT")
    val notes: String? = null,

    @Column(name = "min_relevance_score")
    val minRelevanceScore: Double = 0.7,  // Минимальный score для канала

    @Version
    val version: Long = 0,
)

enum class ChannelType {
    PUBLIC,    // Публичный канал
    PRIVATE,   // Приватный канал (только по ссылке/инвайт)
    GROUP      // Группа (если мониторим группы)
}
