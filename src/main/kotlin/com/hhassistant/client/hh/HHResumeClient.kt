package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.ResumeDto
import com.hhassistant.ratelimit.RateLimitService
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class HHResumeClient(
    @Qualifier("hhWebClient") private val webClient: WebClient,
    private val rateLimitService: RateLimitService,
) {
    private val log = KotlinLogging.logger {}

    suspend fun getMyResumes(): List<ResumeDto> {
        // Проверяем rate limit перед запросом
        rateLimitService.tryConsume()

        log.debug("📄 [HH.ru API] Fetching user resumes")

        return webClient.get()
            .uri("/resumes/mine")
            .retrieve()
            .bodyToMono<List<ResumeDto>>()
            .awaitSingle()
    }

    suspend fun getResumeDetails(id: String): ResumeDto {
        // Проверяем rate limit перед запросом
        rateLimitService.tryConsume()

        log.debug("📄 [HH.ru API] Fetching resume details for ID: $id")

        return webClient.get()
            .uri("/resumes/$id")
            .retrieve()
            .bodyToMono<ResumeDto>()
            .awaitSingle()
    }
}
