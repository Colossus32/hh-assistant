package com.hhassistant.client.hh.dto

data class VacancySearchResponse(
    val items: List<VacancyDto>,
    val found: Int,
    val pages: Int,
    val perPage: Int,
    val page: Int,
)

