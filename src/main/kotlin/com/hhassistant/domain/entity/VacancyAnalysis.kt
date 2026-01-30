package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "vacancy_analyses")
data class VacancyAnalysis(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "vacancy_id", length = 50, nullable = false)
    val vacancyId: String,

    @Column(name = "analyzed_at", nullable = false)
    val analyzedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "is_relevant", nullable = false)
    val isRelevant: Boolean,

    @Column(name = "relevance_score", nullable = false)
    val relevanceScore: Double, // 0.0 - 1.0

    @Column(name = "reasoning", columnDefinition = "TEXT", nullable = false)
    val reasoning: String, // Почему релевантна/не релевантна

    @Column(name = "matched_skills", columnDefinition = "TEXT")
    val matchedSkills: String?, // JSON array или comma-separated

    @Column(name = "suggested_cover_letter", columnDefinition = "TEXT")
    val suggestedCoverLetter: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "cover_letter_generation_status", length = 20, nullable = false)
    val coverLetterGenerationStatus: CoverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,

    @Column(name = "cover_letter_attempts", nullable = false)
    val coverLetterAttempts: Int = 0,

    @Column(name = "cover_letter_last_attempt_at")
    val coverLetterLastAttemptAt: LocalDateTime? = null,
) {
    /**
     * Rich Domain Model: бизнес-логика внутри entity
     * Проверяет, успешно ли сгенерировано сопроводительное письмо
     */
    fun hasCoverLetter(): Boolean = suggestedCoverLetter != null &&
        coverLetterGenerationStatus == CoverLetterGenerationStatus.SUCCESS

    /**
     * Проверяет, можно ли повторить генерацию письма
     */
    fun canRetryCoverLetter(): Boolean =
        coverLetterGenerationStatus == CoverLetterGenerationStatus.RETRY_QUEUED ||
            coverLetterGenerationStatus == CoverLetterGenerationStatus.FAILED

    /**
     * Проверяет, была ли генерация письма завершена (успешно или неудачно)
     */
    fun isCoverLetterGenerationComplete(): Boolean =
        coverLetterGenerationStatus == CoverLetterGenerationStatus.SUCCESS ||
            coverLetterGenerationStatus == CoverLetterGenerationStatus.FAILED

    /**
     * Создает копию анализа с обновленным письмом
     */
    fun withCoverLetter(coverLetter: String): VacancyAnalysis = copy(
        suggestedCoverLetter = coverLetter,
        coverLetterGenerationStatus = CoverLetterGenerationStatus.SUCCESS,
        coverLetterAttempts = 0,
        coverLetterLastAttemptAt = null,
    )

    /**
     * Создает копию анализа с обновленным статусом генерации письма
     */
    fun withCoverLetterStatus(
        status: CoverLetterGenerationStatus,
        attempts: Int = coverLetterAttempts,
        lastAttemptAt: LocalDateTime? = LocalDateTime.now(),
    ): VacancyAnalysis = copy(
        coverLetterGenerationStatus = status,
        coverLetterAttempts = attempts,
        coverLetterLastAttemptAt = lastAttemptAt,
    )
}

enum class CoverLetterGenerationStatus {
    NOT_ATTEMPTED, // Письмо еще не пытались генерировать
    IN_PROGRESS, // Генерация в процессе
    SUCCESS, // Успешно сгенерировано
    FAILED, // Все попытки неудачны
    RETRY_QUEUED, // В очереди на повторную попытку
}
