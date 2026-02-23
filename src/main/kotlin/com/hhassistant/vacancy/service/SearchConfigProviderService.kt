package com.hhassistant.vacancy.service

import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.vacancy.repository.SearchConfigRepository
import com.hhassistant.service.util.SearchConfigFactory
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сервис для получения активных конфигураций поиска вакансий.
 * Выделен из VacancyService и VacancyFetchService для устранения дублирования (DRY).
 *
 * Стратегии (app.search.rotation-strategy):
 * - "all" (default): все ключевые слова за один цикл (как VacancyFetchService)
 * - "rotation": один keyword за цикл, round-robin (как VacancyService)
 */
@Service
class SearchConfigProviderService(
    private val searchConfigRepository: SearchConfigRepository,
    private val searchConfigFactory: SearchConfigFactory,
    private val searchConfig: VacancyServiceConfig,
    @Value("\${app.search.rotation-strategy:all}") private val rotationStrategy: String,
) {
    private val log = KotlinLogging.logger {}
    private val rotationIndex = AtomicInteger(0)

    /**
     * Получает активные конфигурации поиска с приоритетом:
     * 1. Ротация/все ключевые слова из application.yml
     * 2. Одно ключевое слово из application.yml
     * 3. Конфигурации из БД
     */
    fun getActiveSearchConfigs(): List<SearchConfig> {
        val keywordsRotation = searchConfig.keywordsRotation
        val keywords = searchConfig.keywords

        return when {
            !keywordsRotation.isNullOrEmpty() -> when (rotationStrategy) {
                "rotation" -> {
                    val currentKeyword = getNextRotationKeyword(keywordsRotation)
                    log.info("[SearchConfigProvider] Using keyword rotation: '$currentKeyword' (${keywordsRotation.size} in rotation)")
                    listOf(searchConfigFactory.createFromYamlConfig(currentKeyword, searchConfig))
                }
                else -> {
                    log.trace("[SearchConfigProvider] Using YAML keywords-rotation (all): $keywordsRotation")
                    keywordsRotation.map { searchConfigFactory.createFromYamlConfig(it, searchConfig) }
                }
            }
            !keywords.isNullOrBlank() -> {
                log.trace("[SearchConfigProvider] Using YAML keywords: $keywords")
                listOf(searchConfigFactory.createFromYamlConfig(keywords, searchConfig))
            }
            else -> {
                val dbConfigs = getActiveSearchConfigsFromDb()
                log.info("[SearchConfigProvider] Using search configurations from database (${dbConfigs.size} config(s))")
                dbConfigs
            }
        }
    }

    @Cacheable(value = ["searchConfigs"], key = "'active'")
    fun getActiveSearchConfigsFromDb(): List<SearchConfig> {
        log.debug("[SearchConfigProvider] Loading active search configs from DB (cache miss)")
        return searchConfigRepository.findByIsActiveTrue()
    }

    @CacheEvict(value = ["searchConfigs"], allEntries = true)
    fun evictSearchConfigCache() {
        log.debug("[SearchConfigProvider] Evicted search config cache")
    }

    private fun getNextRotationKeyword(keywords: List<String>): String {
        if (keywords.isEmpty()) {
            throw IllegalArgumentException("Keywords rotation list cannot be empty")
        }
        val currentIndex = rotationIndex.getAndUpdate { current ->
            (current + 1) % keywords.size
        }
        val keyword = keywords[currentIndex]
        log.debug("[SearchConfigProvider] Rotation: using keyword '$keyword' (index: $currentIndex/${keywords.size - 1})")
        return keyword
    }
}
