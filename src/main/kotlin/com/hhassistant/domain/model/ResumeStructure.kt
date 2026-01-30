package com.hhassistant.domain.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ResumeStructure(
    val skills: List<String> = emptyList(),
    val experience: List<Experience> = emptyList(),
    val education: List<Education> = emptyList(),
    @JsonProperty("desired_position")
    val desiredPosition: String? = null,
    @JsonProperty("desired_salary")
    val desiredSalary: Int? = null,
    val summary: String? = null,
)

data class Experience(
    val position: String,
    val company: String,
    val duration: String,
    val description: String? = null,
)

data class Education(
    val institution: String,
    val degree: String? = null,
    val field: String? = null,
    val year: Int? = null,
)


