package com.hhassistant.integration.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class VacancySearchResponse(
    val items: List<VacancyDto>,
    val found: Int,
    val pages: Int,
    @JsonProperty("per_page")
    val perPage: Int,
    val page: Int,
    // Опциональные поля, которые могут быть в ответе
    val clusters: Any? = null,
    val arguments: Any? = null,
    val fixes: Any? = null,
    val suggests: Any? = null,
    @JsonProperty("alternate_url")
    val alternateUrl: String? = null,
)
