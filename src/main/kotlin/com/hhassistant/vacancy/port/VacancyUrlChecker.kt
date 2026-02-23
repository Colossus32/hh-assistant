package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Vacancy

/**
 * Порт для проверки доступности вакансии по URL (HH.ru API).
 * Изолирует VacancyAnalysisService от VacancyUrlValidationService/HHVacancyClient (PROJECT_REVIEW 3.2).
 */
interface VacancyUrlChecker {
    suspend fun checkVacancyUrl(vacancyId: String): Boolean
    suspend fun checkVacancyUrlsBatch(vacancies: List<Vacancy>, batchSize: Int): Map<String, Boolean>
}
