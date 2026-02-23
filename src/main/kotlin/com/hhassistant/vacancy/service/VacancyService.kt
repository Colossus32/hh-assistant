package com.hhassistant.vacancy.service

import com.hhassistant.aspect.Loggable
import com.hhassistant.integration.hh.HHVacancyClient
import com.hhassistant.integration.hh.dto.toEntity
import com.hhassistant.config.AppConstants
import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.vacancy.repository.VacancyRepository
import com.hhassistant.service.audit.VacancyFetchAuditService
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.util.TokenRefreshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class VacancyService(
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyRepository: VacancyRepository,
    private val formattingConfig: FormattingConfig,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    private val vacancyStatusService: VacancyStatusService,
    private val processedVacancyCacheService: ProcessedVacancyCacheService,
    private val vacancyFetchAuditService: VacancyFetchAuditService,
    private val vacancyPersistenceService: VacancyPersistenceService,
    private val searchConfigProviderService: SearchConfigProviderService,
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
     * Загружает новые вакансии из HH.ru API и сохраняет их в БД.
     *
     * @return Результат загрузки с вакансиями и ключевыми словами
     */
    @Loggable
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        log.info(" [VacancyService] Starting to fetch new vacancies from HH.ru API")

        // Получаем активные конфигурации поиска (приоритет: YAML rotation > YAML single > DB)
        val activeConfigs = searchConfigProviderService.getActiveSearchConfigs()

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
        val auditSummaries = mutableListOf<com.hhassistant.service.audit.ConfigAuditSummary>()

        for (config in activeConfigs) {
            try {
                val configId = config.id?.toString() ?: "YAML"
                log.info(
                    " [VacancyService] Processing search config ID=$configId: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}",
                )
                val (vacancies, auditSummary) = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)
                auditSummaries.add(auditSummary)
                log.info(
                    " [VacancyService] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies (audit: valid=${auditSummary.valid}, invalid=${auditSummary.invalid}, duplicates=${auditSummary.duplicates})",
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
                        val (vacancies, auditSummary) = fetchVacanciesForConfig(config)
                        allNewVacancies.addAll(vacancies)
                        auditSummaries.add(auditSummary)
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

        // Сохраняем общую статистику аудита
        if (auditSummaries.isNotEmpty()) {
            vacancyFetchAuditService.saveAuditStatistics(auditSummaries)
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
     *
     * @return Список вакансий со статусом NEW
     */
    fun getNewVacanciesForAnalysis(): List<Vacancy> {
        return vacancyRepository.findByStatus(VacancyStatus.NEW)
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
     * Получает вакансии с пагинацией, отсортированные по дате получения (новые первыми).
     *
     * @param page Номер страницы (0-based)
     * @param size Размер страницы
     * @return Страница вакансий
     */
    fun findAllVacanciesPaged(page: Int, size: Int): Page<Vacancy> {
        return vacancyRepository.findAllByOrderByFetchedAtDesc(PageRequest.of(page, size))
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
     * Инвалидирует кэш конфигураций поиска
     */
    fun evictSearchConfigCache() {
        searchConfigProviderService.evictSearchConfigCache()
    }

    /**
     * Получает список всех ID вакансий с кэшированием
     */
    fun getAllVacancyIds(): Set<String> {
        return vacancyPersistenceService.getAllVacancyIds()
    }

    /**
     * Обновляет статус вакансии (Rich Domain Model - использует withStatus)
     */
    @CacheEvict(value = ["vacancyListCache"], allEntries = true)
    fun updateVacancyStatus(updatedVacancy: Vacancy) {
        try {
            val oldStatus = vacancyRepository.findById(updatedVacancy.id)
                .map { it.status }
                .orElse(null)
            vacancyRepository.save(updatedVacancy)
            log.info(
                " [VacancyService] Updated vacancy ${updatedVacancy.id} ('${updatedVacancy.name}') status: $oldStatus -> ${updatedVacancy.status}",
            )

            // Инвалидируем кэш списков вакансий и ID при изменении статуса
            vacancyPersistenceService.evictVacancyIdsCache()
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
    @CacheEvict(value = ["vacancyListCache"], allEntries = true)
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

    private suspend fun fetchVacanciesForConfig(config: SearchConfig): Pair<List<Vacancy>, com.hhassistant.service.audit.ConfigAuditSummary> {
        val configId = config.id?.toString() ?: "YAML"
        log.info(" [VacancyService] Fetching vacancies for config ID=$configId: '${config.keywords}'")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)
        log.info(" [VacancyService] Received ${vacancyDtos.size} vacancies from HH.ru API for config ID=$configId")

        val existingIds = vacancyPersistenceService.getAllVacancyIds()
        log.debug(" [VacancyService] Checking against ${existingIds.size} existing vacancies in database")

        // Аудит вакансий: проверяем валидность и причины, почему не сохраняются
        val auditSummary = vacancyFetchAuditService.auditVacanciesForConfig(vacancyDtos, config, existingIds)

        // Фильтруем только валидные и новые вакансии для сохранения
        val newVacancies = vacancyDtos
            .filter { !existingIds.contains(it.id) }
            .filter { vacancy -> // Дополнительная фильтрация на основе аудита
                // Уже проверили в аудите, что вакансия валидная
                auditSummary.details.find { it.id == vacancy.id }?.let { entry ->
                    entry.category != com.hhassistant.service.audit.AuditCategory.INVALID
                } ?: true // Если нет в деталях аудита, значит валидна
            }
            .map { it.toEntity(formattingConfig) }
            .take(maxVacanciesPerCycle)

        log.info(
            " [VacancyService] Found ${newVacancies.size} new vacancies (${vacancyDtos.size - newVacancies.size} already exist, ${auditSummary.invalid} invalid)",
        )

        if (newVacancies.isNotEmpty()) {
            // Сохраняем в транзакции для обеспечения атомарности (blocking DB — выполняется на Dispatchers.IO)
            val savedVacancies = withContext(Dispatchers.IO) {
                vacancyPersistenceService.saveVacanciesInTransaction(newVacancies)
            }
            val duplicatesCount = newVacancies.size - savedVacancies.size
            log.info(
                " [VacancyService]  Saved ${savedVacancies.size} new vacancies to database for config ID=$configId " +
                    "($duplicatesCount duplicates skipped, ${auditSummary.invalid} invalid skipped)",
            )
            savedVacancies.forEach { vacancy ->
                log.debug(
                    "   - Saved: ${vacancy.name} (ID: ${vacancy.id}, Employer: ${vacancy.employer}, Salary: ${vacancy.salary})",
                )
            }

            // Инкрементально обновляем кэш ID вакансий (добавляем новые ID вместо полной инвалидации)
            vacancyPersistenceService.updateVacancyIdsCacheIncrementally(savedVacancies.map { it.id })
            // Также инвалидируем кэш конфигураций поиска (на случай, если они изменились)
            // Это делается через @CacheEvict в getActiveSearchConfigs, но можно и явно
        } else {
            log.info(" [VacancyService] No new vacancies to save for config ID=$configId")
        }

        return Pair(newVacancies, auditSummary)
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

        // Подсчитываем вакансии в очереди на обработку (NEW + QUEUED)
        val queueCount = vacancyRepository.countPendingVacancies().toInt()

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
    
    /**
     * Перезапускает вакансии, которые были добавлены за последние 24 часа.
     * Сбрасывает их статус на NEW.
     * 
     * @return Pair<количество перезапущенных вакансий, список ID перезапущенных вакансий>
     */
    @Loggable
    suspend fun restartRecentVacancies(): Pair<Int, List<String>> {
        val cutoffTime = java.time.LocalDateTime.now().minusHours(24)
        log.info("[VacancyService] Restarting vacancies added in last 24 hours (after $cutoffTime)")
        
        val recentVacancies = vacancyRepository.findVacanciesAddedInLast24Hours(cutoffTime)
        
        if (recentVacancies.isEmpty()) {
            log.info("[VacancyService] No vacancies found added in last 24 hours")
            return Pair(0, emptyList())
        }
        
        log.info("[VacancyService] Found ${recentVacancies.size} vacancies added in last 24 hours")
        
        // Логируем распределение по статусам для отладки
        val statusCounts = recentVacancies.groupingBy { it.status }.eachCount()
        log.info("[VacancyService] Status distribution: $statusCounts")
        
        var restartedCount = 0
        var skippedNewOrQueued = 0
        var skippedTerminal = 0
        val vacancyIdsToEnqueue = mutableListOf<String>()
        
        for (vacancy in recentVacancies) {
            // Пропускаем вакансии, которые уже в начальных статусах (не нужно перезапускать)
            if (vacancy.status == VacancyStatus.NEW || vacancy.status == VacancyStatus.QUEUED) {
                skippedNewOrQueued++
                log.debug("[VacancyService] Skipping vacancy ${vacancy.id} with status ${vacancy.status} (already in initial state)")
                continue
            }
            
            // Пропускаем вакансии в терминальных статусах (не перезапускаем)
            if (vacancy.status == VacancyStatus.NOT_INTERESTED ||
                vacancy.status == VacancyStatus.NOT_SUITABLE ||
                vacancy.status == VacancyStatus.IN_ARCHIVE ||
                vacancy.status == VacancyStatus.APPLIED ||
                vacancy.status == VacancyStatus.REJECTED_BY_VALIDATOR
            ) {
                skippedTerminal++
                log.debug("[VacancyService] Skipping vacancy ${vacancy.id} with status ${vacancy.status} (terminal status)")
                continue
            }
            
            // Перезапускаем вакансии со статусами ANALYZED, SENT_TO_USER, SKIPPED, IN_ARCHIVE
            // IN_ARCHIVE перезапускаем, так как это может быть ошибка (особенно для Telegram вакансий)
            // Сбрасываем статус на NEW
            try {
                log.info("[VacancyService] Restarting vacancy ${vacancy.id} ('${vacancy.name}') from ${vacancy.status} to NEW")
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                vacancyIdsToEnqueue.add(vacancy.id)
                restartedCount++
            } catch (e: Exception) {
                log.error("[VacancyService] Failed to restart vacancy ${vacancy.id}: ${e.message}", e)
            }
        }
        
        log.info("[VacancyService] Restart summary: $restartedCount restarted, $skippedNewOrQueued skipped (NEW/QUEUED), $skippedTerminal skipped (terminal)")
        
        // Удаляем перезапущенные вакансии из кэша обработанных, чтобы они могли быть обработаны заново
        // Удаляем все перезапущенные вакансии из кэша, независимо от времени анализа
        if (vacancyIdsToEnqueue.isNotEmpty()) {
            var removedFromCache = 0
            for (vacancyId in vacancyIdsToEnqueue) {
                processedVacancyCacheService.removeFromCache(vacancyId)
                removedFromCache++
            }
            log.info("[VacancyService] Removed $removedFromCache vacancies from processed cache (out of $restartedCount restarted)")
        }
        
        log.info("[VacancyService] Successfully restarted $restartedCount vacancies out of ${recentVacancies.size} found")
        return Pair(restartedCount, vacancyIdsToEnqueue)
    }
}
