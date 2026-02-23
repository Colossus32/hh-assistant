package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Vacancy

/**
 * Порт для валидации контента вакансии (исключения, ban-слова).
 * Изолирует VacancyAnalysisService от VacancyContentValidator (PROJECT_REVIEW 3.2).
 */
interface ContentValidatorPort {
    data class ValidationResult(
        val isValid: Boolean,
        val rejectionReason: String?,
    )

    suspend fun validate(vacancy: Vacancy): ValidationResult
}
