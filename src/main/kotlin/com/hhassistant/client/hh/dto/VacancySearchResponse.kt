package com.hhassistant.client.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class VacancySearchResponse(
    val items: List<VacancyDto>,
    val found: Int,
    val pages: Int,
    val perPage: Int,
    val page: Int
)

