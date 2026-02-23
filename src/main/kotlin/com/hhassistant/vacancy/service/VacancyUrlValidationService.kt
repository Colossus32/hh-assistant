package com.hhassistant.vacancy.service

import com.hhassistant.integration.hh.HHVacancyClient
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.exception.HHAPIException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import com.hhassistant.vacancy.port.VacancyUrlChecker
import org.springframework.stereotype.Service

/**
 * Сервис проверки доступности URL вакансий на HH.ru.
 * Выделен из VacancyAnalysisService для соблюдения SRP (PROJECT_REVIEW issue 3).
 */
@Service
class VacancyUrlValidationService(
    private val hhVacancyClient: HHVacancyClient,
    @Qualifier("vacancyUrlCheckCache") private val vacancyUrlCheckCache:
        com.github.benmanes.caffeine.cache.Cache<String, Boolean>,
    @Value("\${app.analysis.max-concurrent-url-checks:2}") private val maxConcurrentUrlChecks: Int,
) : VacancyUrlChecker {
    private val log = KotlinLogging.logger {}
    private val urlCheckSemaphore = Semaphore(maxConcurrentUrlChecks)

    /**
     * Проверяет URL вакансии на доступность (существует ли вакансия на HH.ru).
     * Использует кэш для уменьшения количества запросов к HH.ru API.
     *
     * @param vacancyId ID вакансии для проверки
     * @return true если вакансия доступна, false если не найдена (404)
     * @throws HHAPIException.RateLimitException если превышен rate limit
     */
    override suspend fun checkVacancyUrl(vacancyId: String): Boolean {
        vacancyUrlCheckCache.getIfPresent(vacancyId)?.let { cachedResult ->
            log.trace("🔗 [URL Check] Cache hit for vacancy $vacancyId: $cachedResult")
            return cachedResult
        }

        return urlCheckSemaphore.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    hhVacancyClient.getVacancyDetails(vacancyId)
                    vacancyUrlCheckCache.put(vacancyId, true)
                    log.debug("🔗 [URL Check] Vacancy $vacancyId is available (cache miss)")
                    true
                } catch (e: HHAPIException.NotFoundException) {
                    vacancyUrlCheckCache.put(vacancyId, false)
                    log.debug("🔗 [URL Check] Vacancy $vacancyId not found (404), cached as unavailable")
                    false
                } catch (e: HHAPIException.RateLimitException) {
                    log.warn("🔗 [URL Check] Rate limit while checking vacancy $vacancyId")
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "🔗 [URL Check] Error checking vacancy $vacancyId URL: ${e.message}, " +
                            "assuming URL is valid and proceeding",
                    )
                    true
                }
            }
        }
    }

    /**
     * Батчевая проверка URL нескольких вакансий параллельно.
     *
     * @param vacancies Список вакансий для проверки
     * @param batchSize Размер батча (по умолчанию 5)
     * @return Map: vacancyId -> true если доступна, false если не найдена (404)
     */
    override suspend fun checkVacancyUrlsBatch(
        vacancies: List<Vacancy>,
        batchSize: Int,
    ): Map<String, Boolean> {
        if (vacancies.isEmpty()) {
            return emptyMap()
        }

        val results = mutableMapOf<String, Boolean>()
        val batches = vacancies.chunked(batchSize)

        for (batch in batches) {
            val batchResults = coroutineScope {
                batch.map { vacancy ->
                    async(Dispatchers.IO) {
                        try {
                            val isAvailable = checkVacancyUrl(vacancy.id)
                            vacancy.id to isAvailable
                        } catch (e: HHAPIException.RateLimitException) {
                            log.warn(
                                "🔗 [URL Check Batch] Rate limit while checking ${vacancy.id}, " +
                                    "marking as available for retry",
                            )
                            vacancy.id to true
                        } catch (e: Exception) {
                            log.warn(
                                "🔗 [URL Check Batch] Error checking ${vacancy.id}: ${e.message}, " +
                                    "assuming available",
                            )
                            vacancy.id to true
                        }
                    }
                }.awaitAll()
            }

            results.putAll(batchResults)
        }

        log.debug(
            "🔗 [URL Check Batch] Checked ${vacancies.size} vacancies in ${batches.size} batches, " +
                "${results.values.count { !it }} not found (404)",
        )

        return results
    }
}
