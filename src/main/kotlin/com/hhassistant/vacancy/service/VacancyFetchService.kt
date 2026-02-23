package com.hhassistant.vacancy.service

import com.hhassistant.aspect.Loggable
import com.hhassistant.integration.hh.HHVacancyClient
import com.hhassistant.integration.hh.dto.requiresMoreThan6YearsExperience
import com.hhassistant.integration.hh.dto.toEntity
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.exception.HHAPIException
import com.hhassistant.service.exclusion.ExclusionKeywordService
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.util.TokenRefreshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Сервис для получения вакансий от HH.ru API
 * Использует прямые вызовы вместо событий
 */
@Service
class VacancyFetchService(
    private val hhVacancyClient: HHVacancyClient,
    private val formattingConfig: com.hhassistant.config.FormattingConfig,
    private val metricsService: com.hhassistant.monitoring.metrics.MetricsService,
    private val vacancyProcessingQueueService: VacancyProcessingQueueService,
    private val exclusionKeywordService: ExclusionKeywordService,
    private val vacancyPersistenceService: VacancyPersistenceService,
    private val searchConfigProviderService: SearchConfigProviderService,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Результат загрузки вакансий
     */
    data class FetchResult(
        val vacancies: List<Vacancy>,
        val searchKeywords: List<String>,
    )

    /**
     * Загружает новые вакансии из HH.ru и сохраняет их в БД.
     *
     * @return Результат загрузки с вакансиями и ключевыми словами
     */
    @Loggable
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        val startTime = System.currentTimeMillis()
        val hhVacancies = fetchVacanciesFromHH()

        if (hhVacancies.vacancies.isNotEmpty()) {
            val savedVacancies = withContext(Dispatchers.IO) {
                vacancyPersistenceService.saveVacanciesInBatches(hhVacancies.vacancies)
            }
            vacancyPersistenceService.updateVacancyIdsCacheIncrementally(savedVacancies.map { it.id })
            metricsService.incrementVacanciesFetched(hhVacancies.vacancies.size)
            val vacancyIds = savedVacancies.map { it.id }
            val enqueuedCount = vacancyProcessingQueueService.enqueueBatch(vacancyIds)
            val duration = System.currentTimeMillis() - startTime
            log.info("📥 [Fetch] ${hhVacancies.vacancies.size} vacancies (${hhVacancies.searchKeywords.joinToString(", ")}), enqueued $enqueuedCount, ${duration}ms")
        } else {
            log.debug("📥 [Fetch] No new vacancies (${hhVacancies.searchKeywords.joinToString(", ")})")
        }
        metricsService.recordVacancyFetchTime(System.currentTimeMillis() - startTime)
        return hhVacancies
    }
    
    /**
     * Получает вакансии из HH.ru (выделено из основного метода для удобства)
     */
    @Loggable
    private suspend fun fetchVacanciesFromHH(): FetchResult {
        val activeConfigs = searchConfigProviderService.getActiveSearchConfigs()
        if (activeConfigs.isEmpty()) {
            log.warn("📥 [Fetch] No search configs. Use DB or application.yml")
            return FetchResult(emptyList(), emptyList())
        }

        val searchKeywords = activeConfigs.map { it.keywords }
        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.debug("📥 [Fetch] Reached limit $maxVacanciesPerCycle")
                    break
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn("📥 [Fetch] Unauthorized for config $configId, refreshing token")
                try {
                    tokenRefreshService.refreshTokenManually()
                    val vacancies = fetchVacanciesForConfig(config)
                    allNewVacancies.addAll(vacancies)
                } catch (refreshError: Exception) {
                    log.error("📥 [Fetch] Token refresh failed: ${refreshError.message}", refreshError)
                    notificationService.sendTokenExpiredAlert(e.message ?: "Unauthorized")
                }
            } catch (e: HHAPIException.RateLimitException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn("📥 [Fetch] Rate limit for config $configId")
            } catch (e: Exception) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("📥 [Fetch] Config $configId: ${e.message}", e)
            }
        }
        
        return FetchResult(allNewVacancies, searchKeywords)
    }

    /**
     * Загружает вакансии для одной конфигурации поиска
     */
    private suspend fun fetchVacanciesForConfig(config: SearchConfig): List<Vacancy> {
        val existingVacancyIds = vacancyPersistenceService.getAllVacancyIds()

        log.trace(
            "📥 [Fetch] Searching vacancies with config: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}",
        )

        val vacancyDtos = hhVacancyClient.searchVacancies(config)

        // Оптимизированная обработка: объединяем все фильтры в один проход с использованием Sequence
        // Это избегает создания промежуточных коллекций и улучшает производительность
        var excludedByExperience = 0
        var excludedByKeywords = 0

        val newVacancies = vacancyDtos
            .asSequence()
            .mapNotNull { vacancyDto ->
                // Фильтр 1: Проверка опыта работы (более 6 лет)
                if (vacancyDto.requiresMoreThan6YearsExperience()) {
                    excludedByExperience++
                    log.trace(
                        "📥 [Fetch] Excluding vacancy ${vacancyDto.id} - experience: ${vacancyDto.experience?.name} (more than 6 years)",
                    )
                    return@mapNotNull null
                }

                // Фильтр 2: Проверка запрещенных ключевых слов
                val containsExclusionKeyword = exclusionKeywordService.containsExclusionKeyword(vacancyDto.name)
                if (containsExclusionKeyword) {
                    excludedByKeywords++
                    log.trace(
                        "📥 [Fetch] Excluding vacancy ${vacancyDto.id} - contains exclusion keyword in name: '${vacancyDto.name}'",
                    )
                    return@mapNotNull null
                }

                vacancyDto
            }
            .map { it.toEntity(formattingConfig) }
            .filter { it.id !in existingVacancyIds }
            .map { it.copy(status = com.hhassistant.domain.entity.VacancyStatus.QUEUED) }
            .toList() // Преобразуем Sequence в List только в конце

        val totalExcluded = excludedByExperience + excludedByKeywords

        if (totalExcluded > 0) {
            log.debug("📥 [Fetch] Excluded: exp=$excludedByExperience, kw=$excludedByKeywords, new=${newVacancies.size}")
        }

        return newVacancies
    }
}
