package com.hhassistant.client.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ResumeDto(
    val id: String,
    val title: String,
    @JsonProperty("first_name")
    val firstName: String?,
    @JsonProperty("last_name")
    val lastName: String?,
    val skills: List<SkillDto>?,
    val experience: List<ExperienceItemDto>?,
    val education: List<EducationDto>?,
    val totalExperience: TotalExperienceDto?,
    val url: String?,
)

data class SkillDto(
    val name: String,
)

data class ExperienceItemDto(
    val position: String?,
    val company: String?,
    val description: String?,
    val start: String?,
    val end: String?,
)

data class EducationDto(
    val name: String?,
    val year: Int?,
)

data class TotalExperienceDto(
    val months: Int?,
)


