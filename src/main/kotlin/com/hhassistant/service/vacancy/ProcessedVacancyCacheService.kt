package com.hhassistant.service.vacancy

import com.hhassistant.repository.VacancyAnalysisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Сервис для кэширования обработанных вакансий.
 * Кэш хранит ID вакансий, которые уже были проанализированы.
 * Основной сценарий - только добавление номеров вакансий, перезапуск держит валидный пул отработанных вакансий.
 * Кэш:
 * - Заполняется при старте приложения
 * - Инвалидируется в полночь и пересобирается заново
 * - Обновляется при создании нового анализа
 */
@Service
class ProcessedVacancyCacheService(
    private val vacancyAnalysisRepository: VacancyAnalysisRepository,
) {
    private val log = KotlinLogging.logger {}

    // Кэш обработанных вакансий (ID вакансий)
    private val processedVacanciesCache = ConcurrentHashMap<String, Boolean>()

    // Блокировка для безопасного чтения/записи
    private val cacheLock = ReentrantReadWriteLock()

    // Счетчики для статистики
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /**
     * Проверяет, была ли вакансия уже обработана (есть анализ в БД)
     * @param vacancyId ID вакансии
     * @return true если вакансия была обработана, false в противном случае
     */
    fun isProcessed(vacancyId: String): Boolean {
        val isInCache = cacheLock.read {
            processedVacanciesCache.containsKey(vacancyId)
        }
        
        if (isInCache) {
            cacheHits++
            log.debug("✅ [ProcessedVacancyCache] Cache HIT for vacancy $vacancyId (hits: $cacheHits, misses: $cacheMisses)")
        } else {
            cacheMisses++
            log.debug("❌ [ProcessedVacancyCache] Cache MISS for vacancy $vacancyId (hits: $cacheHits, misses: $cacheMisses)")
        }
        
        return isInCache
    }

    /**
     * Добавляет вакансию в кэш обработанных
     * @param vacancyId ID вакансии
     */
    fun markAsProcessed(vacancyId: String) {
        val wasNew = cacheLock.write {
            val wasNew = !processedVacanciesCache.containsKey(vacancyId)
            processedVacanciesCache[vacancyId] = true
            wasNew
        }
        if (wasNew) {
            log.debug("📦 [ProcessedVacancyCache] Added vacancy $vacancyId to cache (cache size: ${processedVacanciesCache.size})")
        } else {
            log.debug("📦 [ProcessedVacancyCache] Vacancy $vacancyId already in cache (cache size: ${processedVacanciesCache.size})")
        }
    }

    /**
     * Удаляет вакансию из кэша (если нужно)
     * @param vacancyId ID вакансии
     */
    fun removeFromCache(vacancyId: String) {
        cacheLock.write {
            processedVacanciesCache.remove(vacancyId)
        }
        log.debug("📦 [ProcessedVacancyCache] Removed vacancy $vacancyId from cache (cache size: ${processedVacanciesCache.size})")
    }

    /**
     * Загружает все обработанные вакансии из БД в кэш
     */
    private suspend fun loadCacheFromDatabase() {
        return withContext(Dispatchers.IO) {
            try {
                log.info("📦 [ProcessedVacancyCache] Loading processed vacancies from database...")

                // Получаем все анализы из БД и извлекаем vacancyId
                val allAnalyses = vacancyAnalysisRepository.findAll()
                val vacancyIds = allAnalyses.map { it.vacancyId }.toSet()
                cacheLock.write {
                    processedVacanciesCache.clear()
                    vacancyIds.forEach { vacancyId ->
                        processedVacanciesCache[vacancyId] = true
                    }
                }

                log.info(
                    "✅ [ProcessedVacancyCache] Loaded ${processedVacanciesCache.size} processed vacancies into cache",
                )
            } catch (e: Exception) {
                log.error(
                    "❌ [ProcessedVacancyCache] Failed to load cache from database: ${e.message}",
                    e,
                )
                throw e
            }
        }
    }

    /**
     * Загружает кэш при старте приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun loadCacheOnStartup() {
        runBlocking {
            try {
                loadCacheFromDatabase()
                // Сбрасываем счетчики после загрузки кэша при старте
                cacheLock.write {
                    cacheHits = 0
                    cacheMisses = 0
                }
                log.info("✅ [ProcessedVacancyCache] Cache loaded on startup, stats reset")
            } catch (e: Exception) {
                log.error(
                    "❌ [ProcessedVacancyCache] Failed to load cache on startup: ${e.message}",
                    e,
                )
                // Не прерываем запуск приложения, кэш будет пустым и будет использоваться fallback к БД
            }
        }
    }

    /**
     * Инвалидирует кэш в полночь и пересобирает его заново
     */
    @Scheduled(cron = "0 0 0 * * *") // Каждый день в полночь
    fun invalidateAndRebuildCache() {
        log.info("🔄 [ProcessedVacancyCache] Invalidating and rebuilding cache at midnight...")
        
        // Логируем статистику перед инвалидацией
        val stats = getCacheStats()
        log.info(
            "📊 [ProcessedVacancyCache] Cache stats before rebuild: hits=${stats.hits}, misses=${stats.misses}, " +
                "hitRate=${String.format("%.2f", stats.hitRate)}%, size=${stats.size}",
        )
        
        runBlocking {
            try {
                loadCacheFromDatabase()
                
                // Сбрасываем счетчики после пересборки
                cacheLock.write {
                    cacheHits = 0
                    cacheMisses = 0
                }
                
                log.info("✅ [ProcessedVacancyCache] Cache successfully rebuilt at midnight, stats reset")
            } catch (e: Exception) {
                log.error(
                    "❌ [ProcessedVacancyCache] Failed to rebuild cache at midnight: ${e.message}",
                    e,
                )
            }
        }
    }

    /**
     * Периодически логирует статистику кэша (каждый час)
     */
    @Scheduled(cron = "0 0 * * * *") // Каждый час
    fun logCacheStats() {
        val stats = getCacheStats()
        if (stats.hits + stats.misses > 0) {
            log.info(
                "📊 [ProcessedVacancyCache] Cache stats: hits=${stats.hits}, misses=${stats.misses}, " +
                    "hitRate=${String.format("%.2f", stats.hitRate)}%, size=${stats.size}",
            )
        }
    }

    /**
     * Получает текущий размер кэша (для мониторинга)
     */
    fun getCacheSize(): Int {
        return cacheLock.read {
            processedVacanciesCache.size
        }
    }

    /**
     * Получает статистику кэша (хиты/миссы)
     */
    fun getCacheStats(): CacheStats {
        return cacheLock.read {
            CacheStats(
                hits = cacheHits,
                misses = cacheMisses,
                size = processedVacanciesCache.size,
                hitRate = if (cacheHits + cacheMisses > 0) {
                    cacheHits.toDouble() / (cacheHits + cacheMisses) * 100.0
                } else {
                    0.0
                },
            )
        }
    }

    /**
     * Статистика кэша
     */
    data class CacheStats(
        val hits: Long,
        val misses: Long,
        val size: Int,
        val hitRate: Double,
    )
}
