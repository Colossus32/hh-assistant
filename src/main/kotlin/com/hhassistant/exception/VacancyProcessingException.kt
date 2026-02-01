package com.hhassistant.exception

/**
 * Исключение для ошибок обработки вакансий.
 */
class VacancyProcessingException(
    message: String,
    val vacancyId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)






