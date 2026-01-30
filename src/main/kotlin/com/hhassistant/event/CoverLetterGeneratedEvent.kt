package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import org.springframework.context.ApplicationEvent

/**
 * Событие: сопроводительное письмо успешно сгенерировано
 */
class CoverLetterGeneratedEvent(
    source: Any,
    val vacancy: Vacancy,
    val analysis: VacancyAnalysis,
) : ApplicationEvent(source)

