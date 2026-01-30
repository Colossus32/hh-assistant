package com.hhassistant.service

import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.toEntity
import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

@Service
class VacancyService(
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyRepository: VacancyRepository,
    private val searchConfigRepository: SearchConfigRepository,
    private val formattingConfig: FormattingConfig,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
    @Value("\${app.search.keywords-rotation:#{null}}") private val yamlKeywordsRotation: List<String>?,
    @Value("\${app.search.keywords:}") private val yamlKeywords: String?, // Оставляем для обратной совместимости
    @Value("\${app.search.area:}") private val yamlArea: String?,
    @Value("\${app.search.min-salary:}") private val yamlMinSalary: Int?,
    @Value("\${app.search.experience:}") private val yamlExperience: String?,
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
     * Загружает новые вакансии из HH.ru API и сохраняет их в БД.
     *
     * @return Результат загрузки с вакансиями и ключевыми словами
     */
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        log.info("🚀 [VacancyService] Starting to fetch new vacancies from HH.ru API")

        // Проверяем, есть ли настройки в application.yml
        val activeConfigs = when {
            // Приоритет 1: Ротация ключевых слов из application.yml
            !yamlKeywordsRotation.isNullOrEmpty() -> {
                val currentKeyword = getNextRotationKeyword(yamlKeywordsRotation)
                log.info("📊 [VacancyService] Using keyword rotation from application.yml")
                log.info("🔄 [VacancyService] Current rotation keyword: '$currentKeyword' (${yamlKeywordsRotation.size} keywords in rotation)")
                val yamlConfig = SearchConfig(
                    keywords = currentKeyword,
                    area = yamlArea?.takeIf { it.isNotBlank() },
                    minSalary = yamlMinSalary,
                    maxSalary = null,
                    experience = yamlExperience?.takeIf { it.isNotBlank() },
                    isActive = true,
                )
                listOf(yamlConfig)
            }
            // Приоритет 2: Одно ключевое слово из application.yml (обратная совместимость)
            !yamlKeywords.isNullOrBlank() -> {
                log.info("📊 [VacancyService] Using single keyword from application.yml")
                val yamlConfig = SearchConfig(
                    keywords = yamlKeywords,
                    area = yamlArea?.takeIf { it.isNotBlank() },
                    minSalary = yamlMinSalary,
                    maxSalary = null,
                    experience = yamlExperience?.takeIf { it.isNotBlank() },
                    isActive = true,
                )
                listOf(yamlConfig)
            }
            // Приоритет 3: Конфигурации из БД (с кэшированием)
            else -> {
                val dbConfigs = getActiveSearchConfigs()
                if (dbConfigs.isEmpty()) {
                    log.warn("⚠️ [VacancyService] No active search configurations found (neither in DB nor in application.yml)")
                    log.warn("⚠️ [VacancyService] Configure search via DB (INSERT INTO search_configs) OR via application.yml (app.search.keywords-rotation)")
                    return FetchResult(emptyList(), emptyList())
                }
                log.info("📊 [VacancyService] Using search configurations from database (${dbConfigs.size} config(s))")
                dbConfigs
            }
        }

        val searchKeywords = activeConfigs.map { it.keywords }
        log.info("📊 [VacancyService] Found ${activeConfigs.size} active search configuration(s)")
        log.info("🔍 [VacancyService] Search keywords: ${searchKeywords.joinToString(", ") { "'$it'" }}")

        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val configId = config.id?.toString() ?: "YAML"
                log.info("🔎 [VacancyService] Processing search config ID=$configId: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}")
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)
                log.info("✅ [VacancyService] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies")

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.info("⏸️ [VacancyService] Reached max vacancies limit ($maxVacanciesPerCycle), stopping fetch")
                    break
                }
            } catch (e: HHAPIException.UnauthorizedException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("🚨 [VacancyService] HH.ru API unauthorized/forbidden error for config $configId: ${e.message}", e)
                log.error("🚨 [VacancyService] This usually means: token expired, invalid, or lacks required permissions")
                
                // Пытаемся автоматически обновить токен через refresh token
                log.info("🔄 [VacancyService] Attempting to refresh access token automatically...")
                val refreshSuccess = tokenRefreshService.refreshTokenManually()
                
                if (refreshSuccess) {
                    log.info("✅ [VacancyService] Token refreshed successfully, retrying request...")
                    // Пробуем еще раз после обновления токена
                    try {
                        val vacancies = fetchVacanciesForConfig(config)
                        allNewVacancies.addAll(vacancies)
                        log.info("✅ [VacancyService] Config ID=$configId ('${config.keywords}'): found ${vacancies.size} new vacancies after token refresh")
                        continue // Успешно, продолжаем с другими конфигурациями
                    } catch (retryException: Exception) {
                        log.error("❌ [VacancyService] Request failed even after token refresh: ${retryException.message}", retryException)
                        // Пробрасываем исходное исключение
                        throw e
                    }
                } else {
                    log.warn("⚠️ [VacancyService] Token refresh failed or not available")
                    log.warn("⚠️ [VacancyService] Please obtain a new token via OAuth: http://localhost:8080/oauth/authorize")
                    // Пробрасываем исключение дальше, чтобы оно обработалось в Scheduler
                    throw e
                }
            } catch (e: HHAPIException.RateLimitException) {
                val configId = config.id?.toString() ?: "YAML"
                log.warn("⚠️ [VacancyService] Rate limit exceeded for config $configId, skipping: ${e.message}")
                // Прерываем загрузку при rate limit, чтобы не усугубить ситуацию
                break
            } catch (e: HHAPIException) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("❌ [VacancyService] HH.ru API error fetching vacancies for config $configId: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            } catch (e: Exception) {
                val configId = config.id?.toString() ?: "YAML"
                log.error("❌ [VacancyService] Unexpected error fetching vacancies for config $configId: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            }
        }

        val newVacancies = allNewVacancies.take(maxVacanciesPerCycle)
        log.info("✅ [VacancyService] Total fetched and saved: ${newVacancies.size} new vacancies")
        if (newVacancies.isNotEmpty()) {
            log.info("📝 [VacancyService] Sample vacancies: ${newVacancies.take(3).joinToString(", ") { "${it.name} (${it.id})" }}")
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
            )
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
        log.debug("🔄 [VacancyService] Rotation: using keyword '$keyword' (index: $currentIndex/${keywords.size - 1})")
        
        return keyword
    }

    /**
     * Получает активные конфигурации поиска с кэшированием
     */
    @Cacheable(value = ["searchConfigs"], key = "'active'")
    fun getActiveSearchConfigs(): List<SearchConfig> {
        log.debug("💾 [VacancyService] Loading active search configs from DB (cache miss)")
        return searchConfigRepository.findByIsActiveTrue()
    }
    
    /**
     * Инвалидирует кэш конфигураций поиска
     */
    @CacheEvict(value = ["searchConfigs"], allEntries = true)
    fun evictSearchConfigCache() {
        log.debug("🔄 [VacancyService] Evicted search config cache")
    }

    /**
     * Получает список всех ID вакансий с кэшированием
     */
    fun getAllVacancyIds(): Set<String> {
        val cacheKey = "all"
        vacancyIdsCache.getIfPresent(cacheKey)?.let { cached ->
            log.debug("💾 [VacancyService] Using cached vacancy IDs (${cached.size} IDs)")
            return cached
        }

        log.debug("💾 [VacancyService] Loading vacancy IDs from DB (cache miss)")
        val ids = vacancyRepository.findAllIds().toSet()
        vacancyIdsCache.put(cacheKey, ids)
        return ids
    }

    /**
     * Обновляет статус вакансии.
     *
     * @param vacancy Вакансия для обновления
     * @param newStatus Новый статус
     */
    fun updateVacancyStatus(vacancy: Vacancy, newStatus: VacancyStatus) {
        try {
            val oldStatus = vacancy.status
            val updatedVacancy = vacancy.copy(status = newStatus)
            vacancyRepository.save(updatedVacancy)
            log.info("✅ [VacancyService] Updated vacancy ${vacancy.id} ('${vacancy.name}') status: $oldStatus -> $newStatus")
            
            // Инвалидируем кэш списков вакансий при изменении статуса
            invalidateVacancyListCache()
        } catch (e: Exception) {
            log.error("Error updating vacancy ${vacancy.id} status: ${e.message}", e)
            throw VacancyProcessingException(
                "Failed to update vacancy status",
                vacancy.id,
                e,
            )
        }
    }
    
    /**
     * Обновляет статус вакансии по ID
     *
     * @param vacancyId ID вакансии
     * @param newStatus Новый статус
     * @return Обновленная вакансия или null, если не найдена
     */
    fun updateVacancyStatusById(vacancyId: String, newStatus: VacancyStatus): Vacancy? {
        val vacancy = getVacancyById(vacancyId)
        return if (vacancy != null) {
            updateVacancyStatus(vacancy, newStatus)
            getVacancyById(vacancyId) // Возвращаем обновленную версию
        } else {
            log.warn("⚠️ [VacancyService] Vacancy with ID $vacancyId not found, cannot update status")
            null
        }
    }

    private suspend fun fetchVacanciesForConfig(config: SearchConfig): List<Vacancy> {
        val configId = config.id?.toString() ?: "YAML"
        log.info("🔍 [VacancyService] Fetching vacancies for config ID=$configId: '${config.keywords}'")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)
        log.info("📥 [VacancyService] Received ${vacancyDtos.size} vacancies from HH.ru API for config ID=$configId")

        val existingIds = getAllVacancyIds()
        log.debug("💾 [VacancyService] Checking against ${existingIds.size} existing vacancies in database")

        val newVacancies = vacancyDtos
            .filter { !existingIds.contains(it.id) }
            .map { it.toEntity(formattingConfig) }
            .take(maxVacanciesPerCycle)

        log.info("🆕 [VacancyService] Found ${newVacancies.size} new vacancies (${vacancyDtos.size - newVacancies.size} already exist)")

        if (newVacancies.isNotEmpty()) {
            vacancyRepository.saveAll(newVacancies)
            log.info("💾 [VacancyService] ✅ Saved ${newVacancies.size} new vacancies to database for config ID=$configId")
            newVacancies.forEach { vacancy ->
                log.debug("   - Saved: ${vacancy.name} (ID: ${vacancy.id}, Employer: ${vacancy.employer}, Salary: ${vacancy.salary})")
            }
            
            // Инвалидируем кэш ID вакансий при добавлении новых
            invalidateVacancyIdsCache()
            // Также инвалидируем кэш конфигураций поиска (на случай, если они изменились)
            // Это делается через @CacheEvict в getActiveSearchConfigs, но можно и явно
        } else {
            log.info("ℹ️ [VacancyService] No new vacancies to save for config ID=$configId")
        }

        return newVacancies
    }

    /**
     * Инвалидирует кэш ID вакансий
     */
    private fun invalidateVacancyIdsCache() {
        vacancyIdsCache.invalidateAll()
        log.debug("🔄 [VacancyService] Invalidated vacancy IDs cache")
    }

    /**
     * Инвалидирует кэш списков вакансий
     */
    private fun invalidateVacancyListCache() {
        // Кэш списков вакансий будет автоматически обновлен через TTL (30 секунд)
        // Но можно явно инвалидировать через CacheManager, если нужно
        log.debug("🔄 [VacancyService] Vacancy list cache will be refreshed on next request (TTL: 30s)")
    }
}
