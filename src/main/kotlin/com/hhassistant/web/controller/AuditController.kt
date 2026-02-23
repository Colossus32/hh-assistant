package com.hhassistant.web.controller

import com.hhassistant.service.audit.AuditCategory
import com.hhassistant.service.audit.FetchAuditStatistics
import com.hhassistant.service.audit.VacancyFetchAuditService
import mu.KotlinLogging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * DTO для ответа со статистикой аудита
 */
data class AuditStatisticsResponse(
    val totalReceived: Int,
    val totalValid: Int,
    val totalInvalid: Int,
    val totalDuplicates: Int,
    val totalSaved: Int,
    val successRate: String,
    val invalidRate: String,
    val configs: List<ConfigAuditSummaryResponse>,
    val timestamp: String,
)

/**
 * DTO для сводки по конфигурации
 */
data class ConfigAuditSummaryResponse(
    val configKey: String,
    val keywords: String,
    val totalReceived: Int,
    val valid: Int,
    val invalid: Int,
    val duplicates: Int,
    val saved: Int,
    val invalidDetails: List<InvalidVacancyDetail>,
)

/**
 * DTO для деталей невалидной вакансии
 */
data class InvalidVacancyDetail(
    val id: String,
    val name: String,
    val employer: String?,
    val reason: String,
    val category: AuditCategory,
)

/**
 * Контроллер для получения статистики аудита получения вакансий
 */
@RestController
@RequestMapping("/api/audit")
class AuditController(
    private val vacancyFetchAuditService: VacancyFetchAuditService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает последнюю статистику аудита получения вакансий
     *
     * @return FetchAuditStatistics с детальной информацией о полученных вакансиях
     */
    @GetMapping("/fetch-statistics")
    fun getFetchStatistics(): AuditStatisticsResponse? {
        val stats = vacancyFetchAuditService.getLastAuditStatistics()

        if (stats == null) {
            log.warn("[AuditController] No audit statistics available")
            return null
        }

        val successRate = if (stats.totalReceived > 0) {
            String.format("%.1f%%", stats.totalValid * 100.0 / stats.totalReceived)
        } else {
            "N/A"
        }

        val invalidRate = if (stats.totalReceived > 0) {
            String.format("%.1f%%", stats.totalInvalid * 100.0 / stats.totalReceived)
        } else {
            "N/A"
        }

        return AuditStatisticsResponse(
            totalReceived = stats.totalReceived,
            totalValid = stats.totalValid,
            totalInvalid = stats.totalInvalid,
            totalDuplicates = stats.totalDuplicates,
            totalSaved = stats.totalSaved,
            successRate = successRate,
            invalidRate = invalidRate,
            configs = stats.configs.map { config ->
                ConfigAuditSummaryResponse(
                    configKey = config.configKey,
                    keywords = config.keywords,
                    totalReceived = config.totalReceived,
                    valid = config.valid,
                    invalid = config.invalid,
                    duplicates = config.duplicates,
                    saved = config.saved,
                    invalidDetails = config.details.map { entry ->
                        InvalidVacancyDetail(
                            id = entry.id,
                            name = entry.name,
                            employer = entry.employer,
                            reason = entry.reason,
                            category = entry.category,
                        )
                    },
                )
            },
            timestamp = stats.timestamp.toString(),
        )
    }

    /**
     * Получает детальный отчет по невалидным вакансиям, сгруппированный по категориям
     *
     * @return Map<AuditCategory, List<VacancyAuditEntry>>
     */
    @GetMapping("/invalid-vacancies")
    fun getInvalidVacanciesReport(): Map<AuditCategory, List<InvalidVacancyDetail>>? {
        val stats = vacancyFetchAuditService.getLastAuditStatistics()

        if (stats == null) {
            log.warn("[AuditController] No audit statistics available")
            return null
        }

        return stats.configs
            .flatMap { it.details }
            .groupBy { it.category }
            .mapValues { (_, entries) ->
                entries.map { entry ->
                    InvalidVacancyDetail(
                        id = entry.id,
                        name = entry.name,
                        employer = entry.employer,
                        reason = entry.reason,
                        category = entry.category,
                    )
                }
            }
    }

    /**
     * Получает отчет только по дубликатам
     *
     * @return List<InvalidVacancyDetail> с дубликатами
     */
    @GetMapping("/duplicates")
    fun getDuplicatesReport(): List<InvalidVacancyDetail>? {
        val report = getInvalidVacanciesReport() ?: return null
        return report[AuditCategory.DUPLICATE] ?: emptyList()
    }

    /**
     * Получает отчет только по невалидным вакансиям
     *
     * @param limit Ограничение количества вакансий в ответе (по умолчанию 50)
     * @return List<InvalidVacancyDetail> с невалидными вакансиями
     */
    @GetMapping("/invalid")
    fun getInvalidReport(@RequestParam(defaultValue = "50") limit: Int): List<InvalidVacancyDetail>? {
        val report = getInvalidVacanciesReport() ?: return null
        return report[AuditCategory.INVALID]?.take(limit) ?: emptyList()
    }
}

