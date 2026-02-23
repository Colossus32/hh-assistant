package com.hhassistant.service.audit

import com.hhassistant.integration.hh.dto.VacancyDto
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.vacancy.repository.VacancyRepository
import com.hhassistant.service.validation.VacancyValidator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Результат аудита одной вакансии
 */
data class VacancyAuditEntry(
    val id: String,
    val name: String,
    val employer: String?,
    val reason: String, // Причина, почему вакансия не была сохранена
    val category: AuditCategory, // Категория причины
    val configKey: String?, // Ключ конфигурации поиска
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

/**
 * Категория причины, почему вакансия не была сохранена
 */
enum class AuditCategory {
    DUPLICATE, // Уже существует в БД
    INVALID, // Невалидные данные от API
    SKIP_REASON, // Пропущена по другим причинам
}

/**
 * Сводный результат аудита для одной конфигурации поиска
 */
data class ConfigAuditSummary(
    val configKey: String,
    val keywords: String,
    val totalReceived: Int, // Всего получено от API
    val valid: Int, // Валидных вакансий
    val invalid: Int, // Невалидных вакансий
    val duplicates: Int, // Дубликатов
    val saved: Int, // Успешно сохранено
    val details: List<VacancyAuditEntry>, // Детали по невалидным и дубликатам
)

/**
 * Общая статистика аудита по всем конфигурациям
 */
data class FetchAuditStatistics(
    val totalReceived: Int,
    val totalValid: Int,
    val totalInvalid: Int,
    val totalDuplicates: Int,
    val totalSaved: Int,
    val configs: List<ConfigAuditSummary>,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

/**
 * Сервис аудита получения вакансий от HH.ru API
 * Отслеживает все полученные вакансии и причины, почему они не сохраняются
 */
@Service
class VacancyFetchAuditService(
    private val vacancyRepository: VacancyRepository,
) {
    private val log = KotlinLogging.logger {}

    // Храним последний аудит в памяти (для REST API)
    private var lastAuditStatistics: FetchAuditStatistics? = null

    /**
     * Выполняет аудит вакансий для одной конфигурации поиска
     *
     * @param vacancies DTO вакансий от API
     * @param config Конфигурация поиска
     * @param existingIds Существующие ID вакансий в БД
     * @return ConfigAuditSummary с результатами аудита
     */
    fun auditVacanciesForConfig(
        vacancies: List<VacancyDto>,
        config: SearchConfig,
        existingIds: Set<String>,
    ): ConfigAuditSummary {
        val configKey = getConfigKey(config)
        val details = mutableListOf<VacancyAuditEntry>()

        var validCount = 0
        var invalidCount = 0
        var duplicateCount = 0
        var savedCount = 0

        for (vacancy in vacancies) {
            // Проверка на дубликат
            if (existingIds.contains(vacancy.id)) {
                duplicateCount++
                details.add(
                    VacancyAuditEntry(
                        id = vacancy.id,
                        name = vacancy.name,
                        employer = vacancy.employer?.name,
                        reason = "Вакансия уже существует в базе данных",
                        category = AuditCategory.DUPLICATE,
                        configKey = configKey,
                    ),
                )
                continue
            }

            // Валидация данных
            val validationResult = VacancyValidator.validate(vacancy)
            if (validationResult is com.hhassistant.service.validation.ValidationResult.Invalid) {
                invalidCount++
                details.add(
                    VacancyAuditEntry(
                        id = vacancy.id,
                        name = vacancy.name,
                        employer = vacancy.employer?.name,
                        reason = "Невалидные данные: ${validationResult.reasons.joinToString(", ")}",
                        category = AuditCategory.INVALID,
                        configKey = configKey,
                    ),
                )
                continue
            }

            // Валидная вакансия
            validCount++

            // Проверяем, была ли она сохранена (если бы она была в newVacancies)
            // Для аудита мы просто считаем её кандидатом на сохранение
            savedCount++
        }

        val summary = ConfigAuditSummary(
            configKey = configKey,
            keywords = config.keywords,
            totalReceived = vacancies.size,
            valid = validCount,
            invalid = invalidCount,
            duplicates = duplicateCount,
            saved = savedCount,
            details = details,
        )

        // Логируем сводку по конфигурации
        log.info(
            """
            |[AUDIT] Config: $configKey ('${config.keywords}')
            |  Received: ${vacancies.size}
            |  Valid: $validCount
            |  Invalid: $invalidCount
            |  Duplicates: $duplicateCount
            |  To save: $savedCount
            """.trimMargin(),
        )

        // Логируем детали по невалидным вакансиям (если есть)
        if (invalidCount > 0) {
            log.warn(
                "[AUDIT] Invalid vacancies for config $configKey (${invalidCount} total): " +
                    details.filter { it.category == AuditCategory.INVALID }
                        .take(5)
                        .joinToString("; ") { "${it.id}: ${it.reason}" },
            )
        }

        return summary
    }

    /**
     * Сохраняет общую статистику аудита
     *
     * @param summaries Сводки по всем конфигурациям
     */
    fun saveAuditStatistics(summaries: List<ConfigAuditSummary>) {
        val totalReceived = summaries.sumOf { it.totalReceived }
        val totalValid = summaries.sumOf { it.valid }
        val totalInvalid = summaries.sumOf { it.invalid }
        val totalDuplicates = summaries.sumOf { it.duplicates }
        val totalSaved = summaries.sumOf { it.saved }

        lastAuditStatistics = FetchAuditStatistics(
            totalReceived = totalReceived,
            totalValid = totalValid,
            totalInvalid = totalInvalid,
            totalDuplicates = totalDuplicates,
            totalSaved = totalSaved,
            configs = summaries,
        )

        // Логируем общую сводку
        log.info(
            """
            |[AUDIT] Total fetch results:
            |  Total received: $totalReceived
            |  Valid: $totalValid
            |  Invalid: $totalInvalid
            |  Duplicates: $totalDuplicates
            |  To save: $totalSaved
            |  Success rate: ${if (totalReceived > 0) String.format("%.1f%%", totalValid * 100.0 / totalReceived) else "N/A"}
            """.trimMargin(),
        )

        // Предупреждение, если много невалидных
        if (totalInvalid > 0) {
            val invalidRate = totalInvalid * 100.0 / totalReceived
            if (invalidRate > 10.0) {
                log.warn(
                    "[AUDIT] WARNING: High invalid vacancy rate: ${String.format("%.1f%%", invalidRate)} ($totalInvalid out of $totalReceived)",
                )
                log.warn("[AUDIT] This may indicate API changes or data quality issues")
            }
        }
    }

    /**
     * Получает последнюю статистику аудита
     *
     * @return Последняя FetchAuditStatistics или null, если аудита еще не было
     */
    fun getLastAuditStatistics(): FetchAuditStatistics? = lastAuditStatistics

    /**
     * Формирует уникальный ключ для конфигурации поиска
     */
    private fun getConfigKey(config: SearchConfig): String {
        return "${config.keywords}_${config.area}_${config.minSalary}"
    }

    /**
     * Получает детальный отчет по невалидным вакансиям
     *
     * @return Map<Category, List<VacancyAuditEntry>>
     */
    fun getInvalidVacanciesReport(): Map<AuditCategory, List<VacancyAuditEntry>> {
        val stats = lastAuditStatistics ?: return emptyMap()
        return stats.configs
            .flatMap { it.details }
            .groupBy { it.category }
    }
}


