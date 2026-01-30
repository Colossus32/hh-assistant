package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.client.hh.dto.VacancySearchResponse
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import com.hhassistant.ratelimit.RateLimitService
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
) {
    private val log = KotlinLogging.logger {}
    
    // Флаг для отслеживания, было ли уже отправлено уведомление об истечении токена
    // чтобы не спамить в Telegram при каждой ошибке
    @Volatile
    private var tokenExpiredAlertSent = false

    suspend fun searchVacancies(config: SearchConfig): List<VacancyDto> {
        // Проверяем rate limit перед запросом
        rateLimitService.tryConsume()

        log.info("🔍 [HH.ru API] Searching vacancies with config: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}, experience=${config.experience}")

        return try {
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

            log.info("✅ [HH.ru API] Received ${response.items.size} vacancies (found: ${response.found}, pages: ${response.pages})")
            if (response.items.isNotEmpty()) {
                log.debug("📋 [HH.ru API] First vacancy: ${response.items.first().name} (ID: ${response.items.first().id})")
            }

            response.items
        } catch (e: WebClientResponseException) {
            log.error("❌ [HH.ru API] Error searching vacancies: ${e.message}", e)
            val exception = mapToHHAPIException(e, "Failed to search vacancies")
            
            // Если это ошибка авторизации, логируем детально
            if (exception is HHAPIException.UnauthorizedException) {
                log.error("🚨 [HH.ru API] UNAUTHORIZED: Access token expired or invalid!")
                log.error("🚨 [HH.ru API] Status code: ${e.statusCode}, Response: ${e.responseBodyAsString}")
            }
            
            throw exception
        } catch (e: Exception) {
            log.error("Unexpected error searching vacancies: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    suspend fun getVacancyDetails(id: String): VacancyDto {
        // Проверяем rate limit перед запросом
        rateLimitService.tryConsume()

        log.info("🔍 [HH.ru API] Fetching vacancy details for ID: $id")

        return try {
            val vacancy = webClient.get()
                .uri("/vacancies/$id")
                .retrieve()
                .bodyToMono<VacancyDto>()
                .awaitSingle()

            log.info("✅ [HH.ru API] Fetched vacancy: ${vacancy.name} (ID: $id)")

            vacancy
        } catch (e: WebClientResponseException) {
            log.error("Error getting vacancy details from HH.ru API: ${e.message}", e)
            throw mapToHHAPIException(e, "Failed to get vacancy details for id: $id")
        } catch (e: Exception) {
            log.error("Unexpected error getting vacancy details: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    private fun mapToHHAPIException(e: WebClientResponseException, defaultMessage: String): HHAPIException {
        return when (e.statusCode) {
            HttpStatus.UNAUTHORIZED -> HHAPIException.UnauthorizedException(
                "Unauthorized access to HH.ru API. Check your access token.",
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
