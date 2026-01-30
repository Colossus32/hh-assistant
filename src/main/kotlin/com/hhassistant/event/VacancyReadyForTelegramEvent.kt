package com.hhassistant.event

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import org.springframework.context.ApplicationEvent

/**
 * Событие: вакансия готова к отправке в Telegram
 */
class VacancyReadyForTelegramEvent(
    source: Any,
    val vacancy: Vacancy,
    val analysis: VacancyAnalysis,
) : ApplicationEvent(source)
