package com.hhassistant.service.vacancy

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.requiresMoreThan6YearsExperience
import com.hhassistant.client.hh.dto.toEntity
import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.event.VacancyFetchedEvent
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.exclusion.ExclusionKeywordService
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.util.SearchConfigFactory
import com.hhassistant.service.util.TokenRefreshService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сервис для получения вакансий от HH.ru API
 * Публикует VacancyFetchedEvent после успешного получения
 */
@Service
class VacancyFetchService(
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyRepository: VacancyRepository,
    private val searchConfigRepository: SearchConfigRepository,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    private val searchConfigFactory: SearchConfigFactory,
    private val searchConfig: VacancyServiceConfig,
    private val formattingConfig: com.hhassistant.config.FormattingConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    private val vacancyProcessingQueueService: VacancyProcessingQueueService,
    private val exclusionKeywordService: ExclusionKeywordService,
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
    @Qualifier("vacancyIdsCache") private val vacancyIdsCache: com.github.benmanes.caffeine.cache.Cache<String, Set<String>>,
) {
    private val log = KotlinLogging.logger {}

    // Индекс для ротации ключевых слов
    private val rotationIndex = AtomicInteger(0)

    /**
     * Результат загрузки вакансий
     */
    data class FetchResult(
        val vacancies: List<Vacancy>,
        val searchKeywords: List<String>,
    )

    /**
     * Результат загрузки вакансий для одной конфигурации
     */
    private sealed class ConfigFetchResult {
        data class Success(val vacancies: List<Vacancy>) : ConfigFetchResult()
        data class Unauthorized(val exception: HHAPIException.UnauthorizedException) : ConfigFetchResult()
        data class RateLimited(val exception: HHAPIException.RateLimitException) : ConfigFetchResult()
        data class Error(val exception: Exception) : ConfigFetchResult()
    }

    /**
     * Загружает новые вакансии из HH.ru API и сохраняет их в БД.
     * Публикует VacancyFetchedEvent после успешного получения.
     *
     * @return Результат загрузки с вакансиями и ключевыми словами
     */
    @Loggable
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        val startTime = System.currentTimeMillis()
        log.info("[VacancyFetch] Starting to fetch new vacancies from HH.ru API")

        // Get active search configurations (priority: YAML rotation > YAML single > DB)
        val activeConfigs = getActiveSearchConfigs()

        if (activeConfigs.isEmpty()) {
            log.warn("[VacancyFetch] No active search configurations found. Configure via DB (INSERT INTO search_configs) OR via application.yml (app.search.keywords-rotation)")
            return FetchResult(emptyList(), emptyList())
        }

        val searchKeywords = activeConfigs.map { it.keywords }
        log.info("[VacancyFetch] Found ${activeConfigs.size} active search configuration(s): ${searchKeywords.joinToString(", ") { "'$it'" }}")

        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val configId = config.id?.toString() ?: "YAML"
                log.debug("[VacancyFetch] Processing search config ID=$configId: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}")
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)
                log.info("[VacancyFetch] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies")

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.info("[VacancyFetch] Reached max vacancies limit ($maxVacanciesPerCycle), stopping fetch")
                    break
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("[VacancyFetch] HH.ru API unauthorized/forbidden error for config $configId: ${e.message}", e)

                // Attempt to automatically refresh token
                try {
                    log.info("[VacancyFetch] Attempting to refresh token automatically...")
                    tokenRefreshService.refreshTokenManually()
                    log.info("[VacancyFetch] Token refreshed successfully, retrying fetch...")
                    val vacancies = fetchVacanciesForConfig(config)
                    allNewVacancies.addAll(vacancies)
                    log.info("[VacancyFetch] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies after token refresh")
                } catch (refreshError: Exception) {
                    log.error("[VacancyFetch] Failed to refresh token: ${refreshError.message}", refreshError)
                    notificationService.sendTokenExpiredAlert(e.message ?: "Unauthorized")
                }
            } catch (e: HHAPIException.RateLimitException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn("[VacancyFetch] Rate limit exceeded for config $configId, skipping")
            } catch (e: Exception) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("[VacancyFetch] Error fetching vacancies for config $configId: ${e.message}", e)
            }
        }

        // Save all new vacancies to database with QUEUED status and add to processing queue
        if (allNewVacancies.isNotEmpty()) {
            log.debug("[VacancyFetch] Saving ${allNewVacancies.size} new vacancies to database with QUEUED status using batch operations...")

            // Сохраняем батчами для оптимизации (Hibernate batch будет автоматически разбивать на группы)
            val savedVacancies = saveVacanciesInBatches(allNewVacancies)

            log.info("[VacancyFetch] Saved ${savedVacancies.size} vacancies to database with QUEUED status (using batch operations)")

            // Инкрементально обновляем кэш ID вакансий (добавляем новые ID вместо полной инвалидации)
            updateVacancyIdsCacheIncrementally(savedVacancies.map { it.id })

            metricsService.incrementVacanciesFetched(allNewVacancies.size)

            // Add vacancies to processing queue
            val vacancyIds = savedVacancies.map { it.id }
            val enqueuedCount = vacancyProcessingQueueService.enqueueBatch(vacancyIds)
            val skippedCount = vacancyIds.size - enqueuedCount
            log.info("[VacancyFetch] Added $enqueuedCount vacancies to processing queue ($skippedCount skipped as duplicates)")

            // Publish event for each group of vacancies by keywords
            val vacanciesByKeywords = allNewVacancies.groupBy {
                activeConfigs.find { config ->
                    it.name.contains(config.keywords, ignoreCase = true) ||
                        it.description?.contains(config.keywords, ignoreCase = true) == true
                }?.keywords ?: searchKeywords.firstOrNull() ?: "unknown"
            }

            vacanciesByKeywords.forEach { (keywords, vacancies) ->
                eventPublisher.publishEvent(VacancyFetchedEvent(this, vacancies, keywords))
            }
        } else {
            log.debug("[VacancyFetch] No new vacancies found")
        }

        val duration = System.currentTimeMillis() - startTime
        metricsService.recordVacancyFetchTime(duration)
        return FetchResult(allNewVacancies, searchKeywords)
    }

    /**
     * Получает активные конфигурации поиска
     */
    private fun getActiveSearchConfigs(): List<SearchConfig> {
        // Priority 1: YAML keywords-rotation (array of strings)
        val keywordsRotation = searchConfig.keywordsRotation
        if (!keywordsRotation.isNullOrEmpty()) {
            log.trace("[VacancyFetch] Using YAML keywords-rotation: $keywordsRotation")
            return keywordsRotation.map { keywords ->
                searchConfigFactory.createFromYamlConfig(keywords, searchConfig)
            }
        }

        // Priority 2: YAML keywords (single string)
        if (!searchConfig.keywords.isNullOrBlank()) {
            log.trace("[VacancyFetch] Using YAML keywords: ${searchConfig.keywords}")
            return listOf(searchConfigFactory.createFromYamlConfig(searchConfig.keywords, searchConfig))
        }

        // Priority 3: DB (active search configs)
        val dbConfigs = searchConfigRepository.findByIsActiveTrue()
        if (dbConfigs.isNotEmpty()) {
            log.trace("[VacancyFetch] Using DB search configs: ${dbConfigs.size} active config(s)")
            return dbConfigs
        }

        return emptyList()
    }

    /**
     * Загружает вакансии для одной конфигурации поиска
     */
    private suspend fun fetchVacanciesForConfig(config: SearchConfig): List<Vacancy> {
        val existingVacancyIds = vacancyIdsCache.get("all", {
            vacancyRepository.findAllIds().toSet()
        }) ?: emptySet()

        log.trace("[VacancyFetch] Searching vacancies with config: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}")

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
                    log.trace("[VacancyFetch] Excluding vacancy ${vacancyDto.id} - experience: ${vacancyDto.experience?.name} (more than 6 years)")
                    return@mapNotNull null
                }

                // Фильтр 2: Проверка запрещенных ключевых слов
                val containsExclusionKeyword = exclusionKeywordService.containsExclusionKeyword(vacancyDto.name)
                if (containsExclusionKeyword) {
                    excludedByKeywords++
                    log.trace("[VacancyFetch] Excluding vacancy ${vacancyDto.id} - contains exclusion keyword in name: '${vacancyDto.name}'")
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
            log.debug("[VacancyFetch] Excluded $excludedByExperience vacancies (experience > 6 years), $excludedByKeywords vacancies (exclusion keywords), total excluded: $totalExcluded")
        }
        log.debug("[VacancyFetch] Found ${vacancyDtos.size} total vacancies, excluded $totalExcluded, ${newVacancies.size} new (not in DB)")

        return newVacancies
    }

    /**
     * Сохраняет вакансии батчами для оптимизации производительности.
     * Разбивает большой список на батчи и сохраняет каждый батч через saveAll().
     * Hibernate batch автоматически группирует INSERT-запросы.
     *
     * @param vacancies Список вакансий для сохранения
     * @return Список сохраненных вакансий
     */
    private fun saveVacanciesInBatches(vacancies: List<Vacancy>): List<Vacancy> {
        val batchSize = 100 // Размер батча для сохранения
        val allSaved = mutableListOf<Vacancy>()

        if (vacancies.size <= batchSize) {
            // Если вакансий немного, сохраняем все сразу
            val saved = vacancyRepository.saveAll(vacancies)
            log.debug("[VacancyFetch] Saved ${saved.size} vacancies in single batch")
            return saved
        }

        // Разбиваем на батчи и сохраняем по частям
        vacancies.chunked(batchSize).forEachIndexed { index, batch ->
            val saved = vacancyRepository.saveAll(batch)
            allSaved.addAll(saved)
            log.debug("[VacancyFetch] Saved batch ${index + 1}: ${saved.size} vacancies (total saved: ${allSaved.size}/${vacancies.size})")
        }

        log.info("[VacancyFetch] Saved ${allSaved.size} vacancies in ${(vacancies.size + batchSize - 1) / batchSize} batches")
        return allSaved
    }

    /**
     * Инкрементально обновляет кэш ID вакансий, добавляя новые ID в существующий Set.
     * Это намного быстрее, чем полная инвалидация и перезагрузка всех ID из БД.
     *
     * @param newVacancyIds Список новых ID вакансий для добавления в кэш
     */
    private fun updateVacancyIdsCacheIncrementally(newVacancyIds: List<String>) {
        val cacheKey = "all"
        val existingIds = vacancyIdsCache.getIfPresent(cacheKey)

        if (existingIds != null) {
            // Кэш существует - добавляем новые ID инкрементально
            val updatedIds = existingIds.toMutableSet().apply {
                addAll(newVacancyIds)
            }
            vacancyIdsCache.put(cacheKey, updatedIds)
            log.debug("[VacancyFetch] Incrementally updated vacancy IDs cache: added ${newVacancyIds.size} new IDs (total: ${updatedIds.size})")
        } else {
            // Кэш пуст - загружаем все ID из БД (это должно быть редко, только при старте приложения)
            log.debug("[VacancyFetch] Cache is empty, loading all vacancy IDs from DB...")
            val allIds = vacancyRepository.findAllIds().toSet()
            vacancyIdsCache.put(cacheKey, allIds)
            log.debug("[VacancyFetch] Loaded ${allIds.size} vacancy IDs from DB into cache")
        }
    }
}
