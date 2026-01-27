package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.client.hh.dto.VacancySearchResponse
import com.hhassistant.domain.entity.SearchConfig
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class HHVacancyClient(
    @Qualifier("hhWebClient") private val webClient: WebClient,
    @Value("\${hh.api.search.per-page}") private val perPage: Int,
    @Value("\${hh.api.search.default-page}") private val defaultPage: Int,
) {

    suspend fun searchVacancies(config: SearchConfig): List<VacancyDto> {
        val response = webClient.get()
            .uri { builder ->
                builder.path("/vacancies")
                    .queryParam("text", config.keywords)
                    .apply {
                        config.area?.let { queryParam("area", it) }
                        config.minSalary?.let { queryParam("salary", it) }
                        config.experience?.let { queryParam("experience", it) }
                        queryParam("per_page", perPage)
                        queryParam("page", defaultPage)
                    }
                    .build()
            }
            .retrieve()
            .bodyToMono<VacancySearchResponse>()
            .awaitSingle()

        return response.items
    }

    suspend fun getVacancyDetails(id: String): VacancyDto {
        return webClient.get()
            .uri("/vacancies/$id")
            .retrieve()
            .bodyToMono<VacancyDto>()
            .awaitSingle()
    }
}
