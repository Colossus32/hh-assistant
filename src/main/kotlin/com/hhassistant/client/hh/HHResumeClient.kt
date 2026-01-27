package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.ResumeDto
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class HHResumeClient(
    @Qualifier("hhWebClient") private val webClient: WebClient
) {
    
    suspend fun getMyResumes(): List<ResumeDto> {
        return webClient.get()
            .uri("/resumes/mine")
            .retrieve()
            .bodyToMono<List<ResumeDto>>()
            .awaitSingle()
    }
    
    suspend fun getResumeDetails(id: String): ResumeDto {
        return webClient.get()
            .uri("/resumes/$id")
            .retrieve()
            .bodyToMono<ResumeDto>()
            .awaitSingle()
    }
}

