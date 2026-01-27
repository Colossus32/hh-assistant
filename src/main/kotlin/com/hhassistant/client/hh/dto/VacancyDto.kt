package com.hhassistant.client.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

data class VacancyDto(
    val id: String,
    val name: String,
    val employer: EmployerDto?,
    val salary: SalaryDto?,
    val area: AreaDto?,
    val url: String,
    val description: String?,
    val experience: ExperienceDto?,
    @JsonProperty("published_at")
    val publishedAt: String?,
    val snippet: SnippetDto?,
) {
    fun toSalaryString(defaultCurrency: String = "RUR"): String? {
        return salary?.let {
            val currency = it.currency ?: defaultCurrency
            when {
                it.from != null && it.to != null -> "${it.from} - ${it.to} $currency"
                it.from != null -> "от ${it.from} $currency"
                it.to != null -> "до ${it.to} $currency"
                else -> null
            }
        }
    }

    fun toAreaString(areaNotSpecified: String = "Не указан"): String {
        return area?.name ?: areaNotSpecified
    }

    fun toExperienceString(): String? {
        return experience?.name
    }

    fun toPublishedAt(): LocalDateTime? {
        return publishedAt?.let {
            try {
                LocalDateTime.parse(it.replace("Z", ""))
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class EmployerDto(
    val id: String?,
    val name: String,
)

data class SalaryDto(
    val from: Int?,
    val to: Int?,
    val currency: String?,
)

data class AreaDto(
    val id: String?,
    val name: String,
)

data class ExperienceDto(
    val id: String?,
    val name: String,
)

data class SnippetDto(
    val requirement: String?,
    val responsibility: String?,
)
