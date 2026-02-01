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
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("null"))
    val suggestedCoverLetter: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "cover_letter_generation_status", length = 20, nullable = false)
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("CoverLetterGenerationStatus.NOT_ATTEMPTED"))
    val coverLetterGenerationStatus: CoverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,

    @Column(name = "cover_letter_attempts", nullable = false)
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("0"))
    val coverLetterAttempts: Int = 0,

    @Column(name = "cover_letter_last_attempt_at")
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("null"))
    val coverLetterLastAttemptAt: LocalDateTime? = null,
) {
    /**
     * Rich Domain Model: бизнес-логика внутри entity
     * @deprecated Cover letter generation is no longer used
     */
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("false"))
    fun hasCoverLetter(): Boolean = false

    /**
     * @deprecated Cover letter generation is no longer used
     */
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("false"))
    fun canRetryCoverLetter(): Boolean = false

    /**
     * @deprecated Cover letter generation is no longer used
     */
    @Deprecated("Cover letter generation is no longer used", ReplaceWith("true"))
    fun isCoverLetterGenerationComplete(): Boolean = true

    /**
     * @deprecated Cover letter generation is no longer used
     */
    @Deprecated("Cover letter generation is no longer used")
    @Suppress("UNUSED_PARAMETER")
    fun withCoverLetter(coverLetter: String): VacancyAnalysis = this

    /**
     * @deprecated Cover letter generation is no longer used
     */
    @Deprecated("Cover letter generation is no longer used")
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    fun withCoverLetterStatus(
        status: CoverLetterGenerationStatus,
        attempts: Int = coverLetterAttempts,
        lastAttemptAt: LocalDateTime? = LocalDateTime.now(),
    ): VacancyAnalysis = this
}

enum class CoverLetterGenerationStatus {
    NOT_ATTEMPTED, // Письмо еще не пытались генерировать
    IN_PROGRESS, // Генерация в процессе
    SUCCESS, // Успешно сгенерировано
    FAILED, // Все попытки неудачны
    RETRY_QUEUED, // В очереди на повторную попытку
}
