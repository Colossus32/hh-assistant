package com.hhassistant.service.vacancy

import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.aspect.Loggable
import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.toEntity
import com.hhassistant.config.AppConstants
import com.hhassistant.config.FormattingConfig
import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.util.SearchConfigFactory
import com.hhassistant.service.util.TokenRefreshService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicInteger

@Service
class VacancyService(
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyRepository: VacancyRepository,
    private val searchConfigRepository: SearchConfigRepository,
    private val formattingConfig: FormattingConfig,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    private val searchConfigFactory: SearchConfigFactory,
    private val searchConfig: VacancyServiceConfig,
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
    @Qualifier("vacancyIdsCache") private val vacancyIdsCache: Cache<String, Set<String>>,
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
     *
     * @return Результат загрузки с вакансиями и ключевыми словами
     */
    @Loggable
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        log.info(" [VacancyService] Starting to fetch new vacancies from HH.ru API")

        // Получаем активные конфигурации поиска (приоритет: YAML rotation > YAML single > DB)
        val activeConfigs = getActiveSearchConfigs()

        if (activeConfigs.isEmpty()) {
            log.warn(" [VacancyService] No active search configurations found")
            log.warn(
                " [VacancyService] Configure search via DB (INSERT INTO search_configs) OR via application.yml (app.search.keywords-rotation)",
            )
            return FetchResult(emptyList(), emptyList())
        }

        val searchKeywords = activeConfigs.map { it.keywords }
        log.info(" [VacancyService] Found ${activeConfigs.size} active search configuration(s)")
        log.info(" [VacancyService] Search keywords: ${searchKeywords.joinToString(", ") { "'$it'" }}")

        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val configId = config.id?.toString() ?: "YAML"
                log.info(
                    " [VacancyService] Processing search config ID=$configId: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}",
                )
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)
                log.info(
                    " [VacancyService] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies",
                )

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.info(" [VacancyService] Reached max vacancies limit ($maxVacanciesPerCycle), stopping fetch")
                    break
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error(
                    " [VacancyService] HH.ru API unauthorized/forbidden error for config $configId: ${e.message}",
                    e,
                )
                log.error(" [VacancyService] This usually means: token expired, invalid, or lacks required permissions")

                // Пытаемся автоматически обновить токен через refresh token
                log.info(" [VacancyService] Attempting to refresh access token automatically...")
                val refreshSuccess = tokenRefreshService.refreshTokenManually()

                if (refreshSuccess) {
                    log.info(" [VacancyService] Token refreshed successfully, retrying request...")
                    // Пробуем еще раз после обновления токена
                    try {
                        val vacancies = fetchVacanciesForConfig(config)
                        allNewVacancies.addAll(vacancies)
                        log.info(
                            " [VacancyService] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies after token refresh",
                        )
                        continue // Успешно, продолжаем с другими конфигурациями
                    } catch (retryException: Exception) {
                        log.error(
                            " [VacancyService] Request failed even after token refresh: ${retryException.message}",
                            retryException,
                        )
                        // Пробрасываем исходное исключение
                        throw e
                    }
                } else {
                    log.warn(" [VacancyService] Token refresh failed or not available")
                    log.warn(
                        " [VacancyService] Please obtain a new token via OAuth: ${AppConstants.Urls.OAUTH_AUTHORIZE}",
                    )
                    // Пробрасываем исключение дальше, чтобы оно обработалось в Scheduler
                    throw e
                }
            } catch (e: HHAPIException.RateLimitException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn(" [VacancyService] Rate limit exceeded for config $configId, skipping: ${e.message}")
                // Прерываем загрузку при rate limit, чтобы не усугубить ситуацию
                break
            } catch (e: HHAPIException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error(" [VacancyService] HH.ru API error fetching vacancies for config $configId: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            } catch (e: Exception) {
                val configId = config.id?.toString() ?: "YAML"
                log.error(" [VacancyService] Unexpected error fetching vacancies for config $configId: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            }
        }

        val newVacancies = allNewVacancies.take(maxVacanciesPerCycle)
        log.info(" [VacancyService] Total fetched and saved: ${newVacancies.size} new vacancies")
        if (newVacancies.isNotEmpty()) {
            log.info(
                " [VacancyService] Sample vacancies: ${newVacancies.take(
                    AppConstants.Indices.SAMPLE_VACANCIES_COUNT,
                ).joinToString(", ") { "${it.name} (${it.id})" }}",
            )
        }

        return FetchResult(newVacancies, searchKeywords)
    }

    /**
     * Получает список новых вакансий, которые еще не были проанализированы.
     * Исключает вакансии со статусом NOT_INTERESTED (неинтересные).
     *
     * @return Список вакансий со статусом NEW
     */
    fun getNewVacanciesForAnalysis(): List<Vacancy> {
        return vacancyRepository.findByStatus(VacancyStatus.NEW)
            .filter { it.status != VacancyStatus.NOT_INTERESTED }
    }

    /**
     * Получает список вакансий со статусом QUEUED для обработки.
     * Используется для обработки вакансий, которые были добавлены в очередь, но еще не обработаны.
     *
     * @param limit Максимальное количество вакансий для возврата (по умолчанию 50)
     * @return Список вакансий со статусом QUEUED
     */
    fun getQueuedVacanciesForProcessing(limit: Int = 50): List<Vacancy> {
        return vacancyRepository.findByStatus(VacancyStatus.QUEUED)
            .take(limit)
    }

    /**
     * Получает список вакансий со статусом SKIPPED для повторной обработки.
     * Используется для восстановления вакансий, которые были пропущены из-за Circuit Breaker OPEN или других ошибок.
     * Фильтрация выполняется на стороне БД через SQL запрос.
     * Ограничивает retry только вакансиями, которые были получены недавно (за последние 48 часов),
     * чтобы избежать бесконечного цикла retry для старых вакансий.
     *
     * @param limit Максимальное количество вакансий для возврата
     * @param retryWindowHours Окно времени для retry в часах (по умолчанию 48 часов)
     * @return Список вакансий со статусом SKIPPED (исключая старые вакансии)
     */
    fun getSkippedVacanciesForRetry(limit: Int = 10, retryWindowHours: Int = 48): List<Vacancy> {
        val cutoffTime = java.time.LocalDateTime.now().minusHours(retryWindowHours.toLong())
        return vacancyRepository.findSkippedVacanciesForRetry(
            org.springframework.data.domain.PageRequest.of(0, limit),
            cutoffTime,
        )
    }

    /**
     * Получает список старых вакансий со статусом SKIPPED, которые вышли за пределы окна времени для retry.
     * Используется для финальной обработки старых вакансий (восстановление/терминальный статус/удаление).
     *
     * @param limit Максимальное количество вакансий для возврата
     * @param retryWindowHours Окно времени для retry в часах (по умолчанию 48 часов)
     * @return Список старых вакансий со статусом SKIPPED
     */
    fun getOldSkippedVacancies(limit: Int = 100, retryWindowHours: Int = 48): List<Vacancy> {
        val cutoffTime = java.time.LocalDateTime.now().minusHours(retryWindowHours.toLong())
        return vacancyRepository.findOldSkippedVacancies(
            org.springframework.data.domain.PageRequest.of(0, limit),
            cutoffTime,
        )
    }

    /**
     * Получает список вакансий, которые еще не были просмотрены пользователем.
     * Включает вакансии со статусами: NEW, ANALYZED, SENT_TO_USER
     * Исключает: SKIPPED, APPLIED, NOT_INTERESTED
     *
     * @return Список непросмотренных вакансий
     */
    fun getUnviewedVacancies(): List<Vacancy> {
        return vacancyRepository.findByStatusIn(
            listOf(
                VacancyStatus.NEW,
                VacancyStatus.ANALYZED,
                VacancyStatus.SENT_TO_USER,
            ),
        )
    }

    /**
     * Получает вакансию по ID
     *
     * @param id ID вакансии
     * @return Вакансия или null, если не найдена
     */
    fun getVacancyById(id: String): Vacancy? {
        return vacancyRepository.findById(id).orElse(null)
    }

    /**
     * Получает все вакансии
     *
     * @return Список всех вакансий
     */
    fun findAllVacancies(): List<Vacancy> {
        return vacancyRepository.findAll()
    }

    /**
     * Gets vacancies that were sent to Telegram
     *
     * @return List of vacancies that were successfully sent to Telegram
     */
    fun getSentToTelegramVacancies(): List<Vacancy> {
        return vacancyRepository.findByStatusAndSentToTelegramAtIsNotNull(VacancyStatus.SENT_TO_USER)
    }

    /**
     * Gets vacancies that were analyzed but not yet sent to Telegram
     *
     * @return List of vacancies ready to be sent but not sent yet
     */
    fun getNotSentToTelegramVacancies(): List<Vacancy> {
        return vacancyRepository.findByStatusInAndSentToTelegramAtIsNull(
            listOf(VacancyStatus.ANALYZED, VacancyStatus.SENT_TO_USER),
        )
    }

    /**
     * Checks if vacancy was sent to Telegram
     *
     * @param vacancyId ID of vacancy
     * @return true if vacancy was sent, false otherwise
     */
    fun wasSentToTelegram(vacancyId: String): Boolean {
        return vacancyRepository.findById(vacancyId)
            .map { it.isSentToUser() }
            .orElse(false)
    }

    /**
     * Получает вакансии по статусу
     *
     * @param status Статус вакансий
     * @return Список вакансий с указанным статусом
     */
    fun findVacanciesByStatus(status: VacancyStatus): List<Vacancy> {
        return vacancyRepository.findByStatus(status)
    }

    /**
     * Получает следующее ключевое слово из ротации (round-robin)
     *
     * @param keywords Список ключевых слов для ротации
     * @return Текущее ключевое слово
     */
    private fun getNextRotationKeyword(keywords: List<String>): String {
        if (keywords.isEmpty()) {
            throw IllegalArgumentException("Keywords rotation list cannot be empty")
        }

        val currentIndex = rotationIndex.getAndUpdate { current ->
            // Переходим к следующему индексу, если достигли конца - возвращаемся к началу
            (current + 1) % keywords.size
        }

        val keyword = keywords[currentIndex]
        log.debug(" [VacancyService] Rotation: using keyword '$keyword' (index: $currentIndex/${keywords.size - 1})")

        return keyword
    }

    /**
     * Получает активные конфигурации поиска с приоритетом:
     * 1. Ротация ключевых слов из application.yml
     * 2. Одно ключевое слово из application.yml (обратная совместимость)
     * 3. Конфигурации из БД
     */
    private fun getActiveSearchConfigs(): List<SearchConfig> {
        val keywordsRotation = searchConfig.keywordsRotation
        val keywords = searchConfig.keywords

        return when {
            // Приоритет 1: Ротация ключевых слов из application.yml
            !keywordsRotation.isNullOrEmpty() -> {
                val currentKeyword = getNextRotationKeyword(keywordsRotation)
                log.info(" [VacancyService] Using keyword rotation from application.yml")
                log.info(
                    " [VacancyService] Current rotation keyword: '$currentKeyword' (${keywordsRotation.size} keywords in rotation)",
                )
                listOf(searchConfigFactory.createFromYamlConfig(currentKeyword, searchConfig))
            }
            // Приоритет 2: Одно ключевое слово из application.yml (обратная совместимость)
            !keywords.isNullOrBlank() -> {
                log.info(" [VacancyService] Using single keyword from application.yml")
                listOf(searchConfigFactory.createFromYamlConfig(keywords, searchConfig))
            }
            // Приоритет 3: Конфигурации из БД (с кэшированием)
            else -> {
                val dbConfigs = getActiveSearchConfigsFromDb()
                log.info(" [VacancyService] Using search configurations from database (${dbConfigs.size} config(s))")
                dbConfigs
            }
        }
    }

    /**
     * Получает активные конфигурации поиска из БД с кэшированием
     */
    @Cacheable(value = ["searchConfigs"], key = "'active'")
    private fun getActiveSearchConfigsFromDb(): List<SearchConfig> {
        log.debug(" [VacancyService] Loading active search configs from DB (cache miss)")
        return searchConfigRepository.findByIsActiveTrue()
    }

    /**
     * Инвалидирует кэш конфигураций поиска
     */
    @CacheEvict(value = ["searchConfigs"], allEntries = true)
    fun evictSearchConfigCache() {
        log.debug(" [VacancyService] Evicted search config cache")
    }

    /**
     * Получает список всех ID вакансий с кэшированием
     */
    fun getAllVacancyIds(): Set<String> {
        val cacheKey = "all"
        vacancyIdsCache.getIfPresent(cacheKey)?.let { cached ->
            log.debug(" [VacancyService] Using cached vacancy IDs (${cached.size} IDs)")
            return cached
        }

        log.debug(" [VacancyService] Loading vacancy IDs from DB (cache miss)")
        val ids = vacancyRepository.findAllIds().toSet()
        vacancyIdsCache.put(cacheKey, ids)
        return ids
    }

    /**
     * Обновляет статус вакансии (Rich Domain Model - использует withStatus)
     */
    @CacheEvict(value = ["vacancyListCache", "vacancyIdsCache"], allEntries = true)
    fun updateVacancyStatus(updatedVacancy: Vacancy) {
        try {
            val oldStatus = vacancyRepository.findById(updatedVacancy.id)
                .map { it.status }
                .orElse(null)
            vacancyRepository.save(updatedVacancy)
            log.info(
                " [VacancyService] Updated vacancy ${updatedVacancy.id} ('${updatedVacancy.name}') status: $oldStatus -> ${updatedVacancy.status}",
            )

            // Инвалидируем кэш списков вакансий при изменении статуса
            invalidateVacancyListCache()
        } catch (e: Exception) {
            log.error("Error updating vacancy ${updatedVacancy.id} status: ${e.message}", e)
            throw VacancyProcessingException(
                "Failed to update vacancy status",
                updatedVacancy.id,
                e,
            )
        }
    }

    /**
     * Обновляет статус вакансии по ID (Rich Domain Model)
     */
    @CacheEvict(value = ["vacancyListCache", "vacancyIdsCache"], allEntries = true)
    fun updateVacancyStatusById(vacancyId: String, newStatus: VacancyStatus): Vacancy? {
        val vacancy = getVacancyById(vacancyId)
        return if (vacancy != null) {
            updateVacancyStatus(vacancy.withStatus(newStatus))
            getVacancyById(vacancyId) // Возвращаем обновленную версию
        } else {
            log.warn(" [VacancyService] Vacancy with ID $vacancyId not found, cannot update status")
            null
        }
    }

    private suspend fun fetchVacanciesForConfig(config: SearchConfig): List<Vacancy> {
        val configId = config.id?.toString() ?: "YAML"
        log.info(" [VacancyService] Fetching vacancies for config ID=$configId: '${config.keywords}'")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)
        log.info(" [VacancyService] Received ${vacancyDtos.size} vacancies from HH.ru API for config ID=$configId")

        val existingIds = getAllVacancyIds()
        log.debug(" [VacancyService] Checking against ${existingIds.size} existing vacancies in database")

        val newVacancies = vacancyDtos
            .filter { !existingIds.contains(it.id) }
            .map { it.toEntity(formattingConfig) }
            .take(maxVacanciesPerCycle)

        log.info(
            " [VacancyService] Found ${newVacancies.size} new vacancies (${vacancyDtos.size - newVacancies.size} already exist)",
        )

        if (newVacancies.isNotEmpty()) {
            // Сохраняем в транзакции для обеспечения атомарности
            saveVacanciesInTransaction(newVacancies)
            log.info(" [VacancyService]  Saved ${newVacancies.size} new vacancies to database for config ID=$configId")
            newVacancies.forEach { vacancy ->
                log.debug(
                    "   - Saved: ${vacancy.name} (ID: ${vacancy.id}, Employer: ${vacancy.employer}, Salary: ${vacancy.salary})",
                )
            }

            // Инкрементально обновляем кэш ID вакансий (добавляем новые ID вместо полной инвалидации)
            updateVacancyIdsCacheIncrementally(newVacancies.map { it.id })
            // Также инвалидируем кэш конфигураций поиска (на случай, если они изменились)
            // Это делается через @CacheEvict в getActiveSearchConfigs, но можно и явно
        } else {
            log.info(" [VacancyService] No new vacancies to save for config ID=$configId")
        }

        return newVacancies
    }

    /**
     * Сохраняет вакансии в транзакции для обеспечения атомарности операции.
     * Используется для сохранения вакансий из suspend функций.
     *
     * @param vacancies Список вакансий для сохранения
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveVacanciesInTransaction(vacancies: List<Vacancy>) {
        vacancyRepository.saveAll(vacancies)
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
            log.debug(
                " [VacancyService] Incrementally updated vacancy IDs cache: added ${newVacancyIds.size} new IDs (total: ${updatedIds.size})",
            )
        } else {
            // Кэш пуст - загружаем все ID из БД (это должно быть редко, только при старте приложения)
            log.debug(" [VacancyService] Cache is empty, loading all vacancy IDs from DB...")
            val allIds = vacancyRepository.findAllIds().toSet()
            vacancyIdsCache.put(cacheKey, allIds)
            log.debug(" [VacancyService] Loaded ${allIds.size} vacancy IDs from DB into cache")
        }
    }

    /**
     * Инвалидирует кэш списков вакансий
     */
    private fun invalidateVacancyListCache() {
        // Кэш списков вакансий будет автоматически обновлен через TTL (30 секунд)
        // Но можно явно инвалидировать через CacheManager, если нужно
        log.debug(" [VacancyService] Vacancy list cache will be refreshed on next request (TTL: 30s)")
    }

    /**
     * Получает статистику по вакансиям для команды /stats
     *
     * @return Статистика с количеством обработанных, в очереди и приблизительным временем
     */
    data class VacancyStatistics(
        val processedCount: Int,
        val queueCount: Int,
        val averageAnalysisTimeMs: Double?,
        val estimatedTimeMs: Long?,
    )

    /**
     * Получает статистику по вакансиям
     *
     * @param averageAnalysisTimeMs Среднее время обработки одной вакансии в миллисекундах
     * @return Статистика по вакансиям
     */
    fun getVacancyStatistics(averageAnalysisTimeMs: Double?): VacancyStatistics {
        // Подсчитываем обработанные вакансии (ANALYZED, SENT_TO_USER, SKIPPED, APPLIED)
        val processedStatuses = listOf(
            VacancyStatus.ANALYZED,
            VacancyStatus.SENT_TO_USER,
            VacancyStatus.SKIPPED,
            VacancyStatus.APPLIED,
        )
        val processedCount = vacancyRepository.findByStatusIn(processedStatuses).size

        // Подсчитываем вакансии в очереди на обработку (NEW)
        val queueCount = vacancyRepository.findByStatus(VacancyStatus.NEW).size

        // Вычисляем приблизительное время обработки оставшихся вакансий
        val estimatedTimeMs = if (averageAnalysisTimeMs != null && queueCount > 0) {
            (averageAnalysisTimeMs * queueCount).toLong()
        } else {
            null
        }

        return VacancyStatistics(
            processedCount = processedCount,
            queueCount = queueCount,
            averageAnalysisTimeMs = averageAnalysisTimeMs,
            estimatedTimeMs = estimatedTimeMs,
        )
    }
}
