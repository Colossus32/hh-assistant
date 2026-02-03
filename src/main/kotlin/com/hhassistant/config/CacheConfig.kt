package com.hhassistant.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Конфигурация кэширования с использованием Caffeine
 *
 * Кэши используются для:
 * - HH.ru API запросы (детали вакансий)
 * - Запросы к БД (SearchConfig, список ID вакансий)
 * - GET endpoints (списки вакансий)
 */
@Configuration
@EnableCaching
class CacheConfig {

    /**
     * Кэш для деталей вакансий из HH.ru API
     * TTL: 1 час (детали вакансий редко меняются)
     */
    @Bean("vacancyDetailsCache")
    fun vacancyDetailsCache(): com.github.benmanes.caffeine.cache.Cache<String, com.hhassistant.client.hh.dto.VacancyDto> {
        return Caffeine.newBuilder()
            .maximumSize(AppConstants.Cache.VACANCY_DETAILS_MAX_SIZE.toLong())
            .expireAfterWrite(1, TimeUnit.HOURS) // TTL: 1 час
            .recordStats() // Включаем статистику для мониторинга
            .build()
    }

    /**
     * Кэш для конфигураций поиска из БД
     * TTL: 30 минут (конфигурации редко меняются)
     */
    @Bean("searchConfigCache")
    fun searchConfigCache(): com.github.benmanes.caffeine.cache.Cache<String, Any> {
        return Caffeine.newBuilder()
            .maximumSize(AppConstants.Cache.SEARCH_CONFIG_MAX_SIZE.toLong())
            .expireAfterWrite(30, TimeUnit.MINUTES) // TTL: 30 минут
            .recordStats()
            .build()
    }

    /**
     * Кэш для списка ID вакансий (для проверки существования)
     * TTL: 5 минут (список обновляется при добавлении новых вакансий)
     */
    @Bean("vacancyIdsCache")
    fun vacancyIdsCache(): com.github.benmanes.caffeine.cache.Cache<String, Set<String>> {
        return Caffeine.newBuilder()
            .maximumSize(AppConstants.Cache.VACANCY_IDS_MAX_SIZE.toLong())
            .expireAfterWrite(5, TimeUnit.MINUTES) // TTL: 5 минут
            .recordStats()
            .build()
    }

    /**
     * Кэш для GET endpoints (списки вакансий)
     * TTL: 30 секунд (быстрое обновление при изменениях)
     */
    @Bean("vacancyListCache")
    fun vacancyListCache(): com.github.benmanes.caffeine.cache.Cache<String, Any> {
        return Caffeine.newBuilder()
            .maximumSize(AppConstants.Cache.VACANCY_LIST_MAX_SIZE.toLong())
            .expireAfterWrite(30, TimeUnit.SECONDS) // TTL: 30 секунд
            .recordStats()
            .build()
    }

    /**
     * Основной CacheManager для Spring Cache
     * Настраивает кэши по умолчанию для всех @Cacheable методов
     *
     * Кэши для exclusion rules (exclusionKeywords, exclusionPhrases, exclusionCaseSensitive)
     * создаются автоматически при первом использовании и используют эти настройки.
     * Они инвалидируются через @CacheEvict при добавлении/удалении правил.
     */
    @Bean
    fun cacheManager(): CacheManager {
        val cacheManager = CaffeineCacheManager()

        // Настройка кэшей по умолчанию
        // Для exclusion rules используется длинный TTL, так как они инвалидируются
        // при изменении через @CacheEvict, а не по истечении TTL
        cacheManager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(AppConstants.Cache.DEFAULT_MAX_SIZE.toLong())
                .expireAfterWrite(24, TimeUnit.HOURS) // Длинный TTL для exclusion rules
                .recordStats(), // Статистика для мониторинга
        )

        return cacheManager
    }
}
