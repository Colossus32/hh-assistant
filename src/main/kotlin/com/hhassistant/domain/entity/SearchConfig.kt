package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "search_configs")
data class SearchConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "keywords", length = 500, nullable = false)
    val keywords: String,

    @Column(name = "min_salary")
    val minSalary: Int?,

    @Column(name = "max_salary")
    val maxSalary: Int?,

    @Column(name = "area", length = 255)
    val area: String?,

    @Column(name = "experience", length = 100)
    val experience: String?,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
)
