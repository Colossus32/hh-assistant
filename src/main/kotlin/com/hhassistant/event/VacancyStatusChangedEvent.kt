package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import org.springframework.context.ApplicationEvent

/**
 * Событие: статус вакансии изменен
 */
class VacancyStatusChangedEvent(
    source: Any,
    val vacancy: Vacancy,
    val oldStatus: VacancyStatus?,
    val newStatus: VacancyStatus,
) : ApplicationEvent(source)

