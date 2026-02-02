package com.hhassistant.client.hh.dto

import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus

/**
 * Проверяет, требует ли вакансия более 6 лет опыта работы.
 * Использует различные варианты написания для поддержки разных языков и форматов.
 *
 * @return true, если вакансия требует более 6 лет опыта, false в противном случае
 */
fun VacancyDto.requiresMoreThan6YearsExperience(): Boolean {
    val experienceStr = this.getExperienceYearsString() ?: ""
    return experienceStr.contains("morethan6", ignoreCase = true) ||
        experienceStr.contains("более 6", ignoreCase = true) ||
        experienceStr.contains("свыше 6", ignoreCase = true) ||
        experienceStr.contains("more than 6", ignoreCase = true)
}

fun VacancyDto.toEntity(formattingConfig: FormattingConfig): Vacancy {
    // Используем alternate_url (браузерная ссылка) если есть и не пустая, иначе url (API ссылка)
    // Если alternate_url нет, пытаемся преобразовать API URL в браузерную ссылку
    val browserUrl = if (!this.alternateUrl.isNullOrBlank()) {
        // Используем alternate_url если он есть и не пустой
        this.alternateUrl
    } else if (!this.url.isNullOrBlank()) {
        // Преобразуем API URL в браузерную ссылку
        // https://api.hh.ru/vacancies/123?host=hh.ru -> https://hh.ru/vacancy/123
        // https://api.hh.ru/vacancies/123 -> https://hh.ru/vacancy/123
        when {
            this.url.contains("/vacancies/") -> {
                // Извлекаем ID вакансии, убирая query параметры
                val pathWithQuery = this.url.substringAfter("/vacancies/")
                val vacancyId = pathWithQuery.substringBefore("?") // Убираем query параметры
                "https://hh.ru/vacancy/$vacancyId"
            }
            this.url.contains("/vacancy/") -> {
                // Уже браузерный URL, но может быть с query параметрами
                val pathWithQuery = this.url.substringAfter("/vacancy/")
                val vacancyId = pathWithQuery.substringBefore("?")
                // Если уже правильный формат, оставляем как есть, иначе формируем заново
                if (this.url.startsWith("https://") && this.url.contains("hh.ru")) {
                    this.url.substringBefore("?") // Убираем только query параметры
                } else {
                    "https://hh.ru/vacancy/$vacancyId"
                }
            }
            else -> {
                // Если не похоже на известный формат, используем ID из DTO
                val fallbackUrl = "https://hh.ru/vacancy/${this.id}"
                fallbackUrl
            }
        }
    } else {
        // Если и url, и alternateUrl отсутствуют, используем ID для формирования URL
        val fallbackUrl = "https://hh.ru/vacancy/${this.id}"
        fallbackUrl
    }

    return Vacancy(
        id = this.id,
        name = this.name,
        employer = this.employer?.name ?: formattingConfig.areaNotSpecified,
        salary = this.toSalaryString(formattingConfig.defaultCurrency),
        area = this.toAreaString(formattingConfig.areaNotSpecified),
        url = browserUrl,
        description = this.description ?: this.snippet?.let {
            "${it.requirement ?: ""}\n${it.responsibility ?: ""}".trim()
        },
        experience = this.toExperienceString(),
        publishedAt = this.toPublishedAt(),
        status = VacancyStatus.NEW,
    )
}
