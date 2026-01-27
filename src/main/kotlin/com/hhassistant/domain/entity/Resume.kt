package com.hhassistant.domain.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "resumes")
data class Resume(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "file_name", length = 255, nullable = false)
    val fileName: String,
    
    @Column(name = "raw_text", columnDefinition = "TEXT", nullable = false)
    val rawText: String,         // Extracted from PDF
    
    @Column(name = "structured_data", columnDefinition = "TEXT")
    val structuredData: String?, // JSON with skills, experience, etc.
    
    @Column(name = "uploaded_at", nullable = false)
    val uploadedAt: LocalDateTime = LocalDateTime.now(),
    
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    val source: ResumeSource,
    
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true
)

enum class ResumeSource {
    HH_API,
    MANUAL_UPLOAD
}

