package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * Entity for storing exclusion rules (keywords and phrases) for vacancy filtering
 */
@Entity
@Table(
    name = "exclusion_rules",
    uniqueConstraints = [UniqueConstraint(columnNames = ["text", "type"])]
)
data class ExclusionRule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "text", nullable = false, length = 500)
    val text: String,

    @Column(name = "type", nullable = false, length = 20)
    val type: ExclusionRuleType,

    @Column(name = "case_sensitive", nullable = false)
    val caseSensitive: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    enum class ExclusionRuleType {
        KEYWORD,  // Single word to search for
        PHRASE    // Phrase to search for as whole
    }
}

