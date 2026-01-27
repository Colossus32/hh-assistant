package com.hhassistant.client.hh.dto

import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus

fun VacancyDto.toEntity(formattingConfig: FormattingConfig): Vacancy {
    return Vacancy(
        id = this.id,
        name = this.name,
        employer = this.employer?.name ?: formattingConfig.areaNotSpecified,
        salary = this.toSalaryString(formattingConfig.defaultCurrency),
        area = this.toAreaString(formattingConfig.areaNotSpecified),
        url = this.url,
        description = this.description ?: this.snippet?.let {
            "${it.requirement ?: ""}\n${it.responsibility ?: ""}".trim()
        },
        experience = this.toExperienceString(),
        publishedAt = this.toPublishedAt(),
        status = VacancyStatus.NEW,
    )
}
