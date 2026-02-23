package com.hhassistant.vacancy.service

import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.vacancy.repository.VacancyRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для сохранения вакансий и работы с кэшем ID.
 * Выделен из VacancyService и VacancyFetchService для устранения дублирования (DRY).
 */
@Service
class VacancyPersistenceService(
    private val vacancyRepository: VacancyRepository,
    @Qualifier("vacancyIdsCache") private val vacancyIdsCache: Cache<String, Set<String>>,
) {
    private val log = KotlinLogging.logger {}
    private val cacheKey = "all"

    /**
     * Получает список всех ID вакансий с кэшированием.
     */
    fun getAllVacancyIds(): Set<String> {
        vacancyIdsCache.getIfPresent(cacheKey)?.let { cached ->
            log.debug("[VacancyPersistence] Using cached vacancy IDs (${cached.size} IDs)")
            return cached
        }

        log.debug("[VacancyPersistence] Loading vacancy IDs from DB (cache miss)")
        val ids = vacancyRepository.findAllIds().toSet()
        vacancyIdsCache.put(cacheKey, ids)
        return ids
    }

    /**
     * Сохраняет вакансии с обработкой дубликатов.
     * Если вакансия с таким ID уже существует, она пропускается.
     * Вызывается только из транзакционных методов (saveVacanciesInBatches, saveVacanciesInTransaction).
     *
     * @param vacancies Список вакансий для сохранения
     * @return Список успешно сохраненных вакансий (без дубликатов)
     */
    private fun saveVacanciesWithDuplicateHandling(vacancies: List<Vacancy>): List<Vacancy> {
        val saved = mutableListOf<Vacancy>()
        var duplicateCount = 0

        for (vacancy in vacancies) {
            try {
                if (vacancyIdsCache.getIfPresent(cacheKey)?.contains(vacancy.id) == true) {
                    log.trace("[VacancyPersistence] Vacancy ${vacancy.id} already exists (from cache), skipping")
                    duplicateCount++
                    continue
                }

                val savedVacancy = vacancyRepository.save(vacancy)
                saved.add(savedVacancy)
            } catch (e: DataIntegrityViolationException) {
                log.debug("[VacancyPersistence] Vacancy ${vacancy.id} already exists (database constraint), skipping: ${e.message}")
                duplicateCount++
            }
        }

        if (duplicateCount > 0) {
            log.debug("[VacancyPersistence] Skipped $duplicateCount duplicate vacancies out of ${vacancies.size}")
        }

        return saved
    }

    /**
     * Сохраняет вакансии батчами для оптимизации производительности.
     *
     * @param vacancies Список вакансий для сохранения
     * @param batchSize Размер батча (по умолчанию 100)
     * @return Список сохраненных вакансий
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveVacanciesInBatches(vacancies: List<Vacancy>, batchSize: Int = 100): List<Vacancy> {
        val allSaved = mutableListOf<Vacancy>()

        if (vacancies.size <= batchSize) {
            val saved = saveVacanciesWithDuplicateHandling(vacancies)
            log.debug("[VacancyPersistence] Saved ${saved.size} vacancies in single batch")
            return saved
        }

        vacancies.chunked(batchSize).forEachIndexed { index, batch ->
            val saved = saveVacanciesWithDuplicateHandling(batch)
            allSaved.addAll(saved)
            log.debug(
                "[VacancyPersistence] Saved batch ${index + 1}: ${saved.size} vacancies (total saved: ${allSaved.size}/${vacancies.size})",
            )
        }

        log.info(
            "[VacancyPersistence] Saved ${allSaved.size} vacancies in ${(vacancies.size + batchSize - 1) / batchSize} batches",
        )
        return allSaved
    }

    /**
     * Сохраняет вакансии в одной транзакции с обработкой дубликатов.
     * Алиас для совместимости с VacancyService.
     *
     * @param vacancies Список вакансий для сохранения
     * @return Список успешно сохраненных вакансий
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveVacanciesInTransaction(vacancies: List<Vacancy>): List<Vacancy> {
        return saveVacanciesWithDuplicateHandling(vacancies)
    }

    /**
     * Инкрементально обновляет кэш ID вакансий.
     *
     * @param newVacancyIds Список новых ID для добавления в кэш
     */
    fun updateVacancyIdsCacheIncrementally(newVacancyIds: List<String>) {
        if (newVacancyIds.isEmpty()) return

        val existingIds = vacancyIdsCache.getIfPresent(cacheKey)

        if (existingIds != null) {
            val updatedIds = existingIds.toMutableSet().apply {
                addAll(newVacancyIds)
            }
            vacancyIdsCache.put(cacheKey, updatedIds)
            log.debug(
                "[VacancyPersistence] Incrementally updated vacancy IDs cache: added ${newVacancyIds.size} new IDs (total: ${updatedIds.size})",
            )
        } else {
            log.debug("[VacancyPersistence] Cache is empty, loading all vacancy IDs from DB...")
            val allIds = vacancyRepository.findAllIds().toSet()
            vacancyIdsCache.put(cacheKey, allIds)
            log.debug("[VacancyPersistence] Loaded ${allIds.size} vacancy IDs from DB into cache")
        }
    }

    /**
     * Инвалидирует кэш ID вакансий (вызывается при обновлении статуса и т.п.).
     */
    fun evictVacancyIdsCache() {
        vacancyIdsCache.invalidateAll()
        log.debug("[VacancyPersistence] Evicted vacancy IDs cache")
    }
}
