package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import org.springframework.context.ApplicationEvent

/**
 * Событие: новые вакансии получены от HH.ru
 */
class VacancyFetchedEvent(
    source: Any,
    val vacancies: List<Vacancy>,
    val searchKeywords: String,
) : ApplicationEvent(source)
