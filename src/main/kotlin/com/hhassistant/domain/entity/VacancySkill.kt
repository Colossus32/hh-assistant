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
 * Связь между вакансией и навыком (many-to-many).
 *
 * Хранит информацию о том, какие навыки были извлечены из конкретной вакансии.
 * Позволяет:
 * - Отслеживать, из какой вакансии был извлечен навык
 * - Хранить дату извлечения для анализа трендов
 * - Делать запросы типа "найти все вакансии с навыком X"
 */
@Entity
@Table(
    name = "vacancy_skills",
    indexes = [
        Index(name = "idx_vacancy_skills_vacancy_id", columnList = "vacancy_id"),
        Index(name = "idx_vacancy_skills_skill_id", columnList = "skill_id"),
        Index(name = "idx_vacancy_skills_extracted_at", columnList = "extracted_at"),
    ],
)
data class VacancySkill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * ID вакансии, из которой был извлечен навык
     */
    @Column(name = "vacancy_id", nullable = false, length = 50)
    val vacancyId: String,

    /**
     * ID навыка из таблицы skills
     */
    @Column(name = "skill_id", nullable = false)
    val skillId: Long,

    /**
     * Дата и время извлечения навыка из вакансии
     */
    @Column(name = "extracted_at", nullable = false)
    val extractedAt: LocalDateTime = LocalDateTime.now(),
) {
    /**
     * Проверяет, был ли навык извлечен недавно (за последние N дней)
     */
    fun isRecent(days: Int = 7): Boolean {
        val cutoffDate = LocalDateTime.now().minusDays(days.toLong())
        return extractedAt.isAfter(cutoffDate)
    }
}
