package com.hhassistant.client.hh.dto

import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

fun VacancyDto.toEntity(formattingConfig: FormattingConfig): Vacancy {
    // Используем alternate_url (браузерная ссылка) если есть и не пустая, иначе url (API ссылка)
    // Если alternate_url нет, пытаемся преобразовать API URL в браузерную ссылку
    val browserUrl = if (!this.alternateUrl.isNullOrBlank()) {
        // Используем alternate_url если он есть и не пустой
        log.debug("🔗 [VacancyDto] Using alternateUrl for vacancy ${this.id}: ${this.alternateUrl}")
        this.alternateUrl
    } else if (!this.url.isNullOrBlank()) {
        log.debug("🔗 [VacancyDto] alternateUrl is null/empty for vacancy ${this.id}, converting API URL: ${this.url}")
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
                log.debug("🔗 [VacancyDto] Using fallback URL for vacancy ${this.id}: $fallbackUrl")
                fallbackUrl
            }
        }
    } else {
        // Если и url, и alternateUrl отсутствуют, используем ID для формирования URL
        val fallbackUrl = "https://hh.ru/vacancy/${this.id}"
        log.debug("🔗 [VacancyDto] Both url and alternateUrl are null/empty for vacancy ${this.id}, using fallback URL: $fallbackUrl")
        fallbackUrl
    }
    
    log.debug("🔗 [VacancyDto] Final browser URL for vacancy ${this.id}: $browserUrl")

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
