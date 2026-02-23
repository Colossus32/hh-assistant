package com.hhassistant.service.validation

import com.hhassistant.integration.hh.dto.VacancyDto

/**
 * Результат валидации вакансии
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reasons: List<String>) : ValidationResult()
}

/**
 * Валидатор вакансий от HH.ru API
 * Проверяет корректность данных, полученных от API
 */
object VacancyValidator {

    /**
     * Валидирует вакансию и возвращает результат проверки
     *
     * @param vacancy DTO вакансии от HH.ru API
     * @return ValidationResult с указанием причин невалидности, если они есть
     */
    fun validate(vacancy: VacancyDto): ValidationResult {
        val errors = mutableListOf<String>()

        // Обязательные поля
        if (vacancy.id.isBlank()) {
            errors.add("ID вакансии пустой")
        }
        if (vacancy.name.isBlank()) {
            errors.add("Название вакансии пустое")
        }

        // Проверка корректности ID
        if (!vacancy.id.matches(Regex("^\\d+$"))) {
            errors.add("ID вакансии '${vacancy.id}' не является числом")
        }

        // Проверка employer
        if (vacancy.employer?.name.isNullOrBlank()) {
            errors.add("Название работодателя пустое")
        }

        // Проверка URL
        if (vacancy.url.isNullOrBlank() && vacancy.alternateUrl.isNullOrBlank()) {
            errors.add("Отсутствуют URL вакансии (url и alternate_url пустые)")
        }

        // Проверка description (может быть null для поиска вакансий)
        // Но если есть, то не должен быть пустым
        if (!vacancy.description.isNullOrBlank() && vacancy.description.length < 10) {
            errors.add("Описание вакансии слишком короткое (< 10 символов)")
        }

        // Проверка published_at
        if (!vacancy.publishedAt.isNullOrBlank()) {
            try {
                java.time.Instant.parse(vacancy.publishedAt)
            } catch (e: Exception) {
                errors.add("Некорректный формат published_at: '${vacancy.publishedAt}'")
            }
        }

        // Проверка salary
        vacancy.salary?.let { salary ->
            if (salary.from != null && salary.from < 0) {
                errors.add("Зарплата from отрицательна: ${salary.from}")
            }
            if (salary.to != null && salary.to < 0) {
                errors.add("Зарплата to отрицательна: ${salary.to}")
            }
            if (salary.from != null && salary.to != null && salary.from > salary.to) {
                errors.add("Зарплата from > to: ${salary.from} > ${salary.to}")
            }
        }

        // Проверка area
        vacancy.area?.let { area ->
            if (area.name.isNullOrBlank() && area.id.isNullOrBlank()) {
                errors.add("Регион указан, но без имени и ID")
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /**
     * Быстрая проверка валидности (true/false)
     *
     * @param vacancy DTO вакансии
     * @return true если валидна, false иначе
     */
    fun isValid(vacancy: VacancyDto): Boolean {
        return validate(vacancy) is ValidationResult.Valid
    }

    /**
     * Получает текстовое описание причин невалидности
     *
     * @param vacancy DTO вакансии
     * @return Строка с описанием ошибок или null, если валидна
     */
    fun getValidationErrors(vacancy: VacancyDto): String? {
        val result = validate(vacancy)
        return if (result is ValidationResult.Invalid) {
            result.reasons.joinToString("; ")
        } else {
            null
        }
    }
}


