package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import org.springframework.context.ApplicationEvent

/**
 * Событие: вакансия проанализирована на релевантность
 */
class VacancyAnalyzedEvent(
    source: Any,
    val vacancy: Vacancy,
    val analysis: VacancyAnalysis,
) : ApplicationEvent(source)

