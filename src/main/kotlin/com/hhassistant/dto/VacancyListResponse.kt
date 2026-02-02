package com.hhassistant.dto

/**
 * DTO для ответа API со списком вакансий
 */
data class VacancyListResponse(
    val count: Int,
    val vacancies: List<VacancyListItemDto>,
)

/**
 * DTO для элемента списка вакансий
 */
data class VacancyListItemDto(
    val id: String,
    val name: String,
    val employer: String,
    val salary: String,
    val url: String,
    val isViewed: Boolean? = null,
)
