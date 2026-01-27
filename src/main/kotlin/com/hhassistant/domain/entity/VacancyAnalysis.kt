package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
)