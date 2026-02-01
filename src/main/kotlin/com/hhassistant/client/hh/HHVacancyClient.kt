package com.hhassistant.client.hh

import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.client.hh.dto.VacancySearchResponse
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import com.hhassistant.ratelimit.RateLimitService
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class HHVacancyClient(
    @Qualifier("hhWebClient") private val webClient: WebClient,
    @Value("\${hh.api.search.per-page}") private val perPage: Int,
    @Value("\${hh.api.search.default-page}") private val defaultPage: Int,
    private val rateLimitService: RateLimitService,
    @Qualifier("vacancyDetailsCache") private val vacancyDetailsCache: Cache<String, VacancyDto>,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Максимальная глубина результатов согласно документации HH.ru API
     * per_page * page не может быть больше 2000
     */
    private val maxVacanciesDepth = 2000

    suspend fun searchVacancies(config: SearchConfig): List<VacancyDto> {
        log.debug("[HH.ru API] Searching vacancies: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}, experience=${config.experience}")

        // Check rate limit before first request
        rateLimitService.tryConsume()

        // First, fetch first page to get total pages count
        val firstPageResponse = fetchVacanciesPage(config, defaultPage)
        val totalFound = firstPageResponse.found
        val totalPages = firstPageResponse.pages
        val allVacancies = firstPageResponse.items.toMutableList()

        log.info("[HH.ru API] First page: ${firstPageResponse.items.size} vacancies, total found: $totalFound, total pages: $totalPages")

        // Calculate max pages considering HH.ru API limit (2000 vacancies)
        val maxPage = minOf(
            totalPages - 1, // 0-based indexing
            (maxVacanciesDepth / perPage) - 1 // HH.ru API limit
        )

        // Fetch remaining pages
        if (maxPage > defaultPage) {
            log.debug("[HH.ru API] Fetching additional pages: ${defaultPage + 1}..$maxPage")
            
            for (page in (defaultPage + 1)..maxPage) {
                try {
                    rateLimitService.tryConsume()
                    
                    val pageResponse = fetchVacanciesPage(config, page)
                    allVacancies.addAll(pageResponse.items)
                    
                    log.trace("[HH.ru API] Page $page: ${pageResponse.items.size} vacancies (total so far: ${allVacancies.size})")
                    
                    // Small delay between requests to avoid rate limit
                    kotlinx.coroutines.delay(100)
                } catch (e: HHAPIException.RateLimitException) {
                    log.warn("[HH.ru API] Rate limit exceeded while fetching page $page, stopping pagination")
                    break
                } catch (e: Exception) {
                    log.warn("[HH.ru API] Error fetching page $page: ${e.message}, continuing with next page")
                }
            }
        }

        log.info("[HH.ru API] Total fetched: ${allVacancies.size} vacancies from ${minOf(maxPage + 1, totalPages)} pages (total available: $totalFound)")
        
        return allVacancies
    }

    /**
     * Запрашивает одну страницу вакансий
     */
    private suspend fun fetchVacanciesPage(config: SearchConfig, page: Int): VacancySearchResponse {
        return try {
            val requestSpec = webClient.get()
                .uri { builder ->
                    builder.path("/vacancies")
                        .queryParam("text", config.keywords)
                        .apply {
                            config.area?.let { queryParam("area", it) }
                            config.minSalary?.let { queryParam("salary", it) }
                            config.experience?.let { queryParam("experience", it) }
                            queryParam("per_page", perPage)
                            queryParam("page", page)
                        }
                        .build()
                }

            val response = requestSpec
                .retrieve()
                .bodyToMono<VacancySearchResponse>()
                .awaitSingle()

            response
        } catch (e: WebClientResponseException) {
            log.error("❌ [HH.ru API] Error searching vacancies on page $page: ${e.message}", e)
            val exception = mapToHHAPIException(e, "Failed to search vacancies on page $page")

            // Если это ошибка авторизации, логируем детально
            if (exception is HHAPIException.UnauthorizedException) {
                log.error("🚨 [HH.ru API] UNAUTHORIZED: Access token expired or invalid!")
                log.error("🚨 [HH.ru API] Status code: ${e.statusCode}, Response: ${e.responseBodyAsString}")
            }

            throw exception
        } catch (e: Exception) {
            log.error("Unexpected error searching vacancies on page $page: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    suspend fun getVacancyDetails(id: String): VacancyDto {
        // Check cache before API request
        @Suppress("UNCHECKED_CAST")
        (vacancyDetailsCache.getIfPresent(id) as VacancyDto?)?.let { cached ->
            log.trace("[HH.ru API] Using cached vacancy details for ID: $id")
            return cached
        }

        rateLimitService.tryConsume()

        log.debug("[HH.ru API] Fetching vacancy details for ID: $id (cache miss)")

        return try {
            val vacancy = webClient.get()
                .uri("/vacancies/$id")
                .retrieve()
                .bodyToMono<VacancyDto>()
                .awaitSingle()

            vacancyDetailsCache.put(id, vacancy)
            log.debug("[HH.ru API] Fetched and cached vacancy: ${vacancy.name} (ID: $id)")

            vacancy
        } catch (e: WebClientResponseException) {
            log.error("[HH.ru API] Error getting vacancy details: ${e.message}", e)
            throw mapToHHAPIException(e, "Failed to get vacancy details for id: $id")
        } catch (e: Exception) {
            log.error("[HH.ru API] Unexpected error getting vacancy details: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    private fun mapToHHAPIException(e: WebClientResponseException, defaultMessage: String): HHAPIException {
        return when (e.statusCode) {
            HttpStatus.UNAUTHORIZED -> HHAPIException.UnauthorizedException(
                "Unauthorized access to HH.ru API. Check your access token.",
                e,
            )
            HttpStatus.FORBIDDEN -> HHAPIException.UnauthorizedException(
                "Forbidden (403): Access token may be invalid, expired, or lacks required permissions. " +
                    "Response: ${e.responseBodyAsString}",
                e,
            )
            HttpStatus.NOT_FOUND -> HHAPIException.NotFoundException(
                "Resource not found in HH.ru API: ${e.message}",
                e,
            )
            HttpStatus.TOO_MANY_REQUESTS -> HHAPIException.RateLimitException(
                "Rate limit exceeded for HH.ru API. Please wait before retrying.",
                e,
            )
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE,
            -> HHAPIException.ConnectionException(
                "Server error from HH.ru API: ${e.statusCode}",
                e,
            )
            else -> HHAPIException.APIException(
                "$defaultMessage: ${e.statusCode} - ${e.message}",
                e,
            )
        }
    }
}
