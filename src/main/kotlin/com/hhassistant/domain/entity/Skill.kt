package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Сущность для хранения навыков, извлеченных из вакансий.
 * 
 * Хранит информацию о навыке и статистику его встречаемости:
 * - Название навыка (оригинальное и нормализованное)
 * - Количество раз, когда навык был найден в вакансиях
 * - Дата последнего обнаружения
 */
@Entity
@Table(name = "skills")
data class Skill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Оригинальное название навыка (как было извлечено из вакансии)
     * Пример: "Kotlin Developer", "Spring Boot", "PostgreSQL"
     */
    @Column(name = "name", nullable = false, length = 255)
    val name: String,

    /**
     * Нормализованное название навыка (для поиска и группировки синонимов)
     * Пример: "kotlin", "spring boot", "postgresql"
     * Используется для объединения одинаковых навыков с разными написаниями
     */
    @Column(name = "normalized_name", unique = true, nullable = false, length = 255)
    val normalizedName: String,

    /**
     * Количество вакансий, в которых был найден этот навык
     */
    @Column(name = "occurrence_count", nullable = false)
    val occurrenceCount: Int = 0,

    /**
     * Дата и время, когда навык был последний раз найден в вакансии
     */
    @Column(name = "last_seen_at")
    val lastSeenAt: LocalDateTime? = null,

    /**
     * Дата создания записи
     */
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    /**
     * Увеличивает счетчик встречаемости навыка
     */
    fun incrementOccurrence(): Skill {
        return copy(
            occurrenceCount = occurrenceCount + 1,
            lastSeenAt = LocalDateTime.now(),
        )
    }

    /**
     * Проверяет, является ли навык популярным (встречается часто)
     */
    fun isPopular(minOccurrences: Int = 10): Boolean {
        return occurrenceCount >= minOccurrences
    }
}





