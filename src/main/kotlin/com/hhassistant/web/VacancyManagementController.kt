package com.hhassistant.web

import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.service.VacancyService
import com.hhassistant.service.VacancyStatusService
import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Контроллер для управления вакансиями: просмотр, отметка как откликнулся, отметка как неинтересная
 */
@RestController
@RequestMapping("/api/vacancies")
class VacancyManagementController(
    private val vacancyService: VacancyService,
    private val vacancyStatusService: VacancyStatusService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает список непросмотренных вакансий (NEW, ANALYZED, SENT_TO_USER)
     * GET /api/vacancies/unviewed
     */
    @GetMapping("/unviewed")
    @Cacheable(value = ["vacancyLists"], key = "'unviewed'", unless = "#result.body.count == 0")
    fun getUnviewedVacancies(): ResponseEntity<Map<String, Any>> {
        log.info("📋 [VacancyManagement] Getting unviewed vacancies...")
        val vacancies = vacancyService.getUnviewedVacancies()
        log.info("✅ [VacancyManagement] Found ${vacancies.size} unviewed vacancies")

        return ResponseEntity.ok(
            mapOf(
                "count" to vacancies.size,
                "vacancies" to vacancies.map { vacancy ->
                    mapOf(
                        "id" to vacancy.id,
                        "name" to vacancy.name,
                        "employer" to vacancy.employer,
                        "salary" to (vacancy.salary ?: ""),
                        "area" to vacancy.area,
                        "url" to vacancy.url,
                        "description" to (vacancy.description?.take(AppConstants.TextLimits.VACANCY_DESCRIPTION_PREVIEW_LENGTH) ?: "Нет описания"),
                        "experience" to (vacancy.experience ?: ""),
                        "status" to vacancy.status.name,
                        "fetchedAt" to vacancy.fetchedAt.toString(),
                    )
                },
            ),
        )
    }

    /**
     * Помечает вакансию как "откликнулся"
     * POST /api/vacancies/{id}/mark-applied
     *
     * Также можно использовать через GET для удобства клика по ссылке:
     * GET /api/vacancies/{id}/mark-applied
     */
    @PostMapping("/{id}/mark-applied")
    @CacheEvict(value = ["vacancyLists"], allEntries = true)
    fun markAsApplied(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        log.info("✅ [VacancyManagement] Marking vacancy $id as APPLIED...")
        return updateVacancyStatus(id, VacancyStatus.APPLIED, "откликнулся")
    }

    /**
     * GET версия для удобства клика по ссылке из Telegram
     */
    @GetMapping("/{id}/mark-applied")
    fun markAsAppliedGet(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        return markAsApplied(id)
    }

    /**
     * Помечает вакансию как "неинтересная"
     * POST /api/vacancies/{id}/mark-not-interested
     *
     * Также можно использовать через GET для удобства клика по ссылке:
     * GET /api/vacancies/{id}/mark-not-interested
     */
    @PostMapping("/{id}/mark-not-interested")
    @CacheEvict(value = ["vacancyLists"], allEntries = true)
    fun markAsNotInterested(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        log.info("❌ [VacancyManagement] Marking vacancy $id as NOT_INTERESTED...")
        return updateVacancyStatus(id, VacancyStatus.NOT_INTERESTED, "неинтересная")
    }

    /**
     * GET версия для удобства клика по ссылке из Telegram
     */
    @GetMapping("/{id}/mark-not-interested")
    fun markAsNotInterestedGet(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        return markAsNotInterested(id)
    }

    /**
     * Получает информацию о вакансии по ID
     * GET /api/vacancies/{id}
     */
    @GetMapping("/{id}")
    @Cacheable(value = ["vacancyLists"], key = "#id")
    fun getVacancy(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        log.info("📋 [VacancyManagement] Getting vacancy $id...")
        val vacancy = vacancyService.getVacancyById(id)

        return if (vacancy != null) {
            ResponseEntity.ok(
                mapOf(
                    "id" to vacancy.id,
                    "name" to vacancy.name,
                    "employer" to vacancy.employer,
                    "salary" to (vacancy.salary ?: ""),
                    "area" to vacancy.area,
                    "url" to vacancy.url,
                    "description" to (vacancy.description ?: ""),
                    "experience" to (vacancy.experience ?: ""),
                    "status" to vacancy.status.name,
                    "fetchedAt" to vacancy.fetchedAt.toString(),
                    "publishedAt" to (vacancy.publishedAt?.toString() ?: "Не указано"),
                ),
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    mapOf(
                        "error" to "NOT_FOUND",
                        "message" to "Vacancy with ID $id not found",
                    ),
                )
        }
    }

    /**
     * Получает список вакансий по статусу
     * GET /api/vacancies?status=APPLIED
     */
    @GetMapping
    @Cacheable(value = ["vacancyLists"], key = "#status ?: 'all'")
    fun getVacanciesByStatus(@RequestParam(required = false) status: String?): ResponseEntity<Map<String, Any>> {
        val vacancies = if (status != null) {
            try {
                val vacancyStatus = VacancyStatus.valueOf(status.uppercase())
                vacancyService.findVacanciesByStatus(vacancyStatus)
            } catch (e: IllegalArgumentException) {
                log.warn("⚠️ [VacancyManagement] Invalid status: $status")
                emptyList<Vacancy>()
            }
        } else {
            vacancyService.findAllVacancies()
        }

        return ResponseEntity.ok(
            mapOf(
                "count" to vacancies.size,
                "status" to (status ?: "ALL"),
                "vacancies" to vacancies.map { vacancy ->
                    mapOf(
                        "id" to vacancy.id,
                        "name" to vacancy.name,
                        "employer" to vacancy.employer,
                        "salary" to (vacancy.salary ?: ""),
                        "area" to vacancy.area,
                        "url" to vacancy.url,
                        "status" to vacancy.status.name,
                    )
                },
            ),
        )
    }

    /**
     * Получает все вакансии с информацией о том, была ли вакансия просмотрена.
     * 
     * GET /api/vacancies/all
     * 
     * @return Список всех вакансий с флагом просмотра
     */
    @GetMapping("/all")
    fun getAllVacanciesWithViewStatus(): ResponseEntity<Map<String, Any>> {
        log.info("📋 [VacancyManagement] Getting all vacancies with view status...")
        
        val allVacancies = vacancyService.findAllVacancies()
        
        // Определяем статусы "просмотренных" вакансий
        val viewedStatuses = listOf(
            VacancyStatus.APPLIED,
            VacancyStatus.NOT_INTERESTED,
        )
        
        val vacanciesWithStatus = allVacancies.map { vacancy ->
            val isViewed = vacancy.status in viewedStatuses
            val wasSentToTelegram = vacancy.isSentToUser()
            
            mapOf(
                "id" to vacancy.id,
                "name" to vacancy.name,
                "employer" to vacancy.employer,
                "salary" to (vacancy.salary ?: ""),
                "area" to vacancy.area,
                "url" to vacancy.url,
                "status" to vacancy.status.name,
                "isViewed" to isViewed,
                "viewed" to if (isViewed) "Да" else "Нет",
                "wasSentToTelegram" to wasSentToTelegram,
                "sentToTelegramAt" to (vacancy.sentToTelegramAt?.toString() ?: ""),
            )
        }

        log.info("✅ [VacancyManagement] Returning ${vacanciesWithStatus.size} vacancies with view status")

        return ResponseEntity.ok(
            mapOf(
                "count" to vacanciesWithStatus.size,
                "vacancies" to vacanciesWithStatus,
            ),
        )
    }

    /**
     * Gets vacancies that were sent to Telegram
     * GET /api/vacancies/sent-to-telegram
     */
    @GetMapping("/sent-to-telegram")
    fun getSentToTelegramVacancies(): ResponseEntity<Map<String, Any>> {
        log.info("[VacancyManagement] Getting vacancies sent to Telegram...")
        val vacancies = vacancyService.getSentToTelegramVacancies()
        log.info("[VacancyManagement] Found ${vacancies.size} vacancies sent to Telegram")

        return ResponseEntity.ok(
            mapOf(
                "count" to vacancies.size,
                "vacancies" to vacancies.map { vacancy ->
                    mapOf(
                        "id" to vacancy.id,
                        "name" to vacancy.name,
                        "employer" to vacancy.employer,
                        "url" to vacancy.url,
                        "sentAt" to (vacancy.sentToTelegramAt?.toString() ?: ""),
                        "status" to vacancy.status.name,
                    )
                },
            ),
        )
    }

    /**
     * Gets vacancies that were analyzed but not sent to Telegram yet
     * GET /api/vacancies/not-sent-to-telegram
     */
    @GetMapping("/not-sent-to-telegram")
    fun getNotSentToTelegramVacancies(): ResponseEntity<Map<String, Any>> {
        log.info("[VacancyManagement] Getting vacancies not sent to Telegram...")
        val vacancies = vacancyService.getNotSentToTelegramVacancies()
        log.info("[VacancyManagement] Found ${vacancies.size} vacancies not sent to Telegram")

        return ResponseEntity.ok(
            mapOf(
                "count" to vacancies.size,
                "vacancies" to vacancies.map { vacancy ->
                    mapOf(
                        "id" to vacancy.id,
                        "name" to vacancy.name,
                        "employer" to vacancy.employer,
                        "url" to vacancy.url,
                        "status" to vacancy.status.name,
                    )
                },
            ),
        )
    }

    /**
     * Checks if vacancy was sent to Telegram
     * GET /api/vacancies/{id}/sent-status
     */
    @GetMapping("/{id}/sent-status")
    fun getVacancySentStatus(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        log.info("[VacancyManagement] Checking sent status for vacancy $id...")
        val vacancy = vacancyService.getVacancyById(id)
        
        if (vacancy == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    mapOf(
                        "error" to "NOT_FOUND",
                        "message" to "Vacancy with id $id not found",
                    ),
                )
        }

        val wasSent = vacancy.isSentToUser()
        return ResponseEntity.ok(
            mapOf(
                "vacancyId" to vacancy.id,
                "wasSentToTelegram" to wasSent,
                "sentAt" to (vacancy.sentToTelegramAt?.toString() ?: ""),
                "status" to vacancy.status.name,
            ),
        )
    }

    private fun updateVacancyStatus(
        vacancyId: String,
        newStatus: VacancyStatus,
        statusDescription: String,
    ): ResponseEntity<Map<String, Any>> {
        val vacancy = vacancyService.getVacancyById(vacancyId)

        return if (vacancy == null) {
            log.warn("⚠️ [VacancyManagement] Vacancy $vacancyId not found")
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                    mapOf(
                        "error" to "NOT_FOUND",
                        "message" to "Vacancy with ID $vacancyId not found",
                    ),
                )
        } else {
            val oldStatus = vacancy.status
            val updatedVacancy = vacancyStatusService.updateVacancyStatusById(vacancyId, newStatus)

            if (updatedVacancy != null) {
                log.info("✅ [VacancyManagement] Successfully marked vacancy $vacancyId as $statusDescription (status: $oldStatus -> $newStatus)")
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to "Vacancy marked as $statusDescription",
                        "vacancy" to mapOf(
                            "id" to updatedVacancy.id,
                            "name" to updatedVacancy.name,
                            "status" to updatedVacancy.status.name,
                            "oldStatus" to oldStatus.name,
                        ),
                    ),
                )
            } else {
                log.error("❌ [VacancyManagement] Failed to update vacancy $vacancyId status")
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(
                        mapOf(
                            "error" to "UPDATE_FAILED",
                            "message" to "Failed to update vacancy status",
                        ),
                    )
            }
        }
    }
}
