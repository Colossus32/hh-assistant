package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Vacancy

/**
 * Порт для обновления статуса вакансии.
 * Изолирует VacancyAnalysisService от VacancyStatusService (PROJECT_REVIEW 3.2).
 * В тестах — мок вместо VacancyStatusService + VacancyRepository.
 */
interface VacancyStatusUpdater {
    fun updateVacancyStatus(updatedVacancy: Vacancy)
}
