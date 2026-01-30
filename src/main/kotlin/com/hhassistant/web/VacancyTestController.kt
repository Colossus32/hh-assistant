package com.hhassistant.web

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Контроллер для тестирования получения вакансий с HH.ru API
 * Используется для проверки работы с реальным токеном
 */
@RestController
@RequestMapping("/api/vacancies/test")
class VacancyTestController(
    private val hhVacancyClient: HHVacancyClient,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Тестовый endpoint для поиска вакансий
     * GET /api/vacancies/test/search?keywords=Kotlin&area=Москва&minSalary=150000
     */
    @GetMapping("/search")
    fun searchVacancies(
        @RequestParam("keywords", required = false, defaultValue = "Kotlin Developer") keywords: String,
        @RequestParam("area", required = false) area: String?,
        @RequestParam("minSalary", required = false) minSalary: Int?,
        @RequestParam("experience", required = false) experience: String?,
    ): ResponseEntity<Any> {
        log.info { "Test search: keywords=$keywords, area=$area, minSalary=$minSalary, experience=$experience" }

        val config = SearchConfig(
            keywords = keywords,
            area = area,
            minSalary = minSalary,
            maxSalary = null,
            experience = experience,
            isActive = true,
        )

        return try {
            val vacancies = runBlocking {
                hhVacancyClient.searchVacancies(config)
            }

            log.info { "Found ${vacancies.size} vacancies" }

            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "count" to vacancies.size,
                    "vacancies" to vacancies.map { vacancy ->
                        mapOf(
                            "id" to vacancy.id,
                            "name" to vacancy.name,
                            "employer" to vacancy.employer?.name,
                            "salary" to vacancy.salary?.let { "${it.from}-${it.to} ${it.currency}" },
                            "area" to vacancy.area?.name,
                            "url" to vacancy.url,
                            "publishedAt" to vacancy.publishedAt,
                        )
                    },
                ),
            )
        } catch (e: HHAPIException.UnauthorizedException) {
            log.error("Unauthorized: ${e.message}", e)
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "UNAUTHORIZED",
                        "message" to "Invalid or missing access token. Check HH_ACCESS_TOKEN in .env file.",
                        "details" to e.message,
                    ),
                )
        } catch (e: HHAPIException) {
            log.error("HH.ru API error: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "HH_API_ERROR",
                        "message" to e.message,
                    ),
                )
        } catch (e: Exception) {
            log.error("Unexpected error: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "INTERNAL_ERROR",
                        "message" to e.message,
                    ),
                )
        }
    }

    /**
     * Получение деталей вакансии по ID с HH.ru API (для тестирования)
     * GET /api/vacancies/test/{id}
     */
    @GetMapping("/{id}")
    fun getVacancyDetails(@org.springframework.web.bind.annotation.PathVariable id: String): ResponseEntity<Any> {
        log.info { "Getting vacancy details for id: $id" }

        return try {
            val vacancy = runBlocking {
                hhVacancyClient.getVacancyDetails(id)
            }

            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "vacancy" to mapOf(
                        "id" to vacancy.id,
                        "name" to vacancy.name,
                        "employer" to vacancy.employer?.name,
                        "salary" to vacancy.salary?.let { "${it.from}-${it.to} ${it.currency}" },
                        "area" to vacancy.area?.name,
                        "description" to vacancy.description,
                        "url" to vacancy.url,
                        "publishedAt" to vacancy.publishedAt,
                    ),
                ),
            )
        } catch (e: HHAPIException.NotFoundException) {
            log.error("Vacancy not found: ${e.message}", e)
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "NOT_FOUND",
                        "message" to "Vacancy with id $id not found",
                    ),
                )
        } catch (e: HHAPIException) {
            log.error("HH.ru API error: ${e.message}", e)
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "HH_API_ERROR",
                        "message" to e.message,
                    ),
                )
        } catch (e: Exception) {
            log.error("Unexpected error: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    mapOf(
                        "success" to false,
                        "error" to "INTERNAL_ERROR",
                        "message" to e.message,
                    ),
                )
        }
    }
}

