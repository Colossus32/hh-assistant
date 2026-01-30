package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import org.springframework.context.ApplicationEvent

/**
 * Событие: генерация сопроводительного письма не удалась после всех попыток
 */
class CoverLetterGenerationFailedEvent(
    source: Any,
    val vacancy: Vacancy,
    val analysis: VacancyAnalysis,
    val attempts: Int,
) : ApplicationEvent(source)

