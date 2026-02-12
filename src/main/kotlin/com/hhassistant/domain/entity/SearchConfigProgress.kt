package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Прогресс пагинации для конфигурации поиска.
 * Хранит последнюю обработанную страницу для каждой уникальной конфигурации поиска.
 * Позволяет восстанавливать прогресс после перезапуска приложения.
 */
@Entity
@Table(
    name = "search_config_progress",
    indexes = [
        Index(name = "idx_search_config_progress_config_key", columnList = "config_key", unique = true),
    ],
)
data class SearchConfigProgress(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Уникальный ключ конфигурации (keywords + area + minSalary)
     * Используется для идентификации конфигурации поиска
     */
    @Column(name = "config_key", length = 500, nullable = false, unique = true)
    val configKey: String,

    /**
     * Последняя обработанная страница
     */
    @Column(name = "last_processed_page", nullable = false)
    val lastProcessedPage: Int = 0,

    /**
     * Дата и время последнего обновления прогресса
     */
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
