package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "vacancies")
data class Vacancy(
    @Id
    @Column(name = "id", length = 50)
    val id: String,

    @Column(name = "name", length = 500, nullable = false)
    val name: String,

    @Column(name = "employer", length = 255, nullable = false)
    val employer: String,

    @Column(name = "salary", length = 100)
    val salary: String?,

    @Column(name = "area", length = 255)
    val area: String,

    @Column(name = "url", length = 1000, nullable = false)
    val url: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "experience", length = 100)
    val experience: String?,

    @Column(name = "published_at")
    val publishedAt: LocalDateTime?,

    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: LocalDateTime = LocalDateTime.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    val status: VacancyStatus = VacancyStatus.NEW,
)

enum class VacancyStatus {
    NEW, // Новая вакансия
    ANALYZED, // Проанализирована LLM
    SENT_TO_USER, // Отправлена в Telegram
    SKIPPED, // Не релевантна
}
