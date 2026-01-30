package com.hhassistant.service

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.client.hh.dto.toEntity
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.event.VacancyFetchedEvent
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
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
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        log.info("🚀 [VacancyFetch] Starting to fetch new vacancies from HH.ru API")

        // Получаем активные конфигурации поиска (приоритет: YAML rotation > YAML single > DB)
        val activeConfigs = getActiveSearchConfigs()
        
        if (activeConfigs.isEmpty()) {
            log.warn("⚠️ [VacancyFetch] No active search configurations found")
            log.warn("⚠️ [VacancyFetch] Configure search via DB (INSERT INTO search_configs) OR via application.yml (app.search.keywords-rotation)")
            return FetchResult(emptyList(), emptyList())
        }

        val searchKeywords = activeConfigs.map { it.keywords }
        log.info("📊 [VacancyFetch] Found ${activeConfigs.size} active search configuration(s)")
        log.info("🔍 [VacancyFetch] Search keywords: ${searchKeywords.joinToString(", ") { "'$it'" }}")

        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val configId = config.id?.toString() ?: "YAML"
                log.info("🔎 [VacancyFetch] Processing search config ID=$configId: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}")
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)
                log.info("✅ [VacancyFetch] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies")

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.info("⏸️ [VacancyFetch] Reached max vacancies limit ($maxVacanciesPerCycle), stopping fetch")
                    break
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("🚨 [VacancyFetch] HH.ru API unauthorized/forbidden error for config $configId: ${e.message}", e)
                log.error("🚨 [VacancyFetch] This usually means: token expired, invalid, or lacks required permissions")
                
                // Пытаемся автоматически обновить токен через refresh token
                try {
                    log.info("🔄 [VacancyFetch] Attempting to refresh token automatically...")
                    tokenRefreshService.refreshTokenManually()
                    log.info("✅ [VacancyFetch] Token refreshed successfully, retrying fetch...")
                    // Повторяем попытку после обновления токена
                    val vacancies = fetchVacanciesForConfig(config)
                    allNewVacancies.addAll(vacancies)
                    log.info("✅ [VacancyFetch] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies after token refresh")
                } catch (refreshError: Exception) {
                    log.error("❌ [VacancyFetch] Failed to refresh token: ${refreshError.message}", refreshError)
                    notificationService.sendTokenExpiredAlert(e.message ?: "Unauthorized")
                    // Продолжаем с другими конфигурациями
                }
            } catch (e: HHAPIException.RateLimitException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn("⏸️ [VacancyFetch] Rate limit exceeded for config $configId, skipping")
                // Продолжаем с другими конфигурациями
            } catch (e: Exception) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("❌ [VacancyFetch] Error fetching vacancies for config $configId: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            }
        }

        // Сохраняем все новые вакансии в БД
        if (allNewVacancies.isNotEmpty()) {
            log.info("💾 [VacancyFetch] Saving ${allNewVacancies.size} new vacancies to database...")
            val savedVacancies = allNewVacancies.map { vacancyRepository.save(it) }
            log.info("✅ [VacancyFetch] Saved ${savedVacancies.size} vacancies to database")
            
            // Публикуем событие для каждой группы вакансий по ключевым словам
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
            log.info("ℹ️ [VacancyFetch] No new vacancies found")
        }

        return FetchResult(allNewVacancies, searchKeywords)
    }

    /**
     * Получает активные конфигурации поиска
     */
    private fun getActiveSearchConfigs(): List<SearchConfig> {
        // Приоритет 1: YAML keywords-rotation (массив строк)
        val keywordsRotation = searchConfig.keywordsRotation
        if (!keywordsRotation.isNullOrEmpty()) {
            log.debug("📋 [VacancyFetch] Using YAML keywords-rotation: $keywordsRotation")
            return keywordsRotation.map { keywords ->
                searchConfigFactory.createFromYamlConfig(keywords, searchConfig)
            }
        }

        // Приоритет 2: YAML keywords (одна строка)
        if (!searchConfig.keywords.isNullOrBlank()) {
            log.debug("📋 [VacancyFetch] Using YAML keywords: ${searchConfig.keywords}")
            return listOf(searchConfigFactory.createFromYamlConfig(searchConfig.keywords!!, searchConfig))
        }

        // Приоритет 3: DB (active search configs)
        val dbConfigs = searchConfigRepository.findByIsActiveTrue()
        if (dbConfigs.isNotEmpty()) {
            log.debug("📋 [VacancyFetch] Using DB search configs: ${dbConfigs.size} active config(s)")
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

        log.debug("🔍 [VacancyFetch] Searching vacancies with config: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)

        val newVacancies = vacancyDtos
            .map { it.toEntity(formattingConfig) }
            .filter { it.id !in existingVacancyIds }
            .map { it.copy(status = com.hhassistant.domain.entity.VacancyStatus.NEW) }

        log.info("📊 [VacancyFetch] Found ${vacancyDtos.size} total vacancies, ${newVacancies.size} new (not in DB)")

        return newVacancies
    }
}

