package com.hhassistant.service

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
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class VacancyService(
    private val hhVacancyClient: HHVacancyClient,
    private val vacancyRepository: VacancyRepository,
    private val searchConfigRepository: SearchConfigRepository,
    private val formattingConfig: FormattingConfig,
    private val notificationService: NotificationService,
    private val tokenRefreshService: TokenRefreshService,
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
    @Value("\${app.search.keywords:}") private val yamlKeywords: String?,
    @Value("\${app.search.area:}") private val yamlArea: String?,
    @Value("\${app.search.min-salary:}") private val yamlMinSalary: Int?,
    @Value("\${app.search.experience:}") private val yamlExperience: String?,
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
    suspend fun fetchAndSaveNewVacancies(): FetchResult {
        log.info("🚀 [VacancyService] Starting to fetch new vacancies from HH.ru API")

        // Проверяем, есть ли настройки в application.yml
        val activeConfigs = if (yamlKeywords.isNullOrBlank()) {
            // Используем конфигурации из БД
            val dbConfigs = searchConfigRepository.findByIsActiveTrue()
            if (dbConfigs.isEmpty()) {
                log.warn("⚠️ [VacancyService] No active search configurations found (neither in DB nor in application.yml)")
                log.warn("⚠️ [VacancyService] Configure search via DB (INSERT INTO search_configs) OR via application.yml (app.search.keywords)")
                return FetchResult(emptyList(), emptyList())
            }
            log.info("📊 [VacancyService] Using search configurations from database (${dbConfigs.size} config(s))")
            dbConfigs
        } else {
            // Используем конфигурацию из application.yml
            log.info("📊 [VacancyService] Using search configuration from application.yml")
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
     *
     * @return Список вакансий со статусом NEW
     */
    fun getNewVacanciesForAnalysis(): List<Vacancy> {
        return vacancyRepository.findByStatus(VacancyStatus.NEW)
    }

    /**
     * Обновляет статус вакансии.
     *
     * @param vacancy Вакансия для обновления
     * @param newStatus Новый статус
     */
    fun updateVacancyStatus(vacancy: Vacancy, newStatus: VacancyStatus) {
        try {
            val updatedVacancy = vacancy.copy(status = newStatus)
            vacancyRepository.save(updatedVacancy)
            log.debug("Updated vacancy ${vacancy.id} status to $newStatus")
        } catch (e: Exception) {
            log.error("Error updating vacancy ${vacancy.id} status: ${e.message}", e)
            throw VacancyProcessingException(
                "Failed to update vacancy status",
                vacancy.id,
                e,
            )
        }
    }

    private suspend fun fetchVacanciesForConfig(config: SearchConfig): List<Vacancy> {
        val configId = config.id?.toString() ?: "YAML"
        log.info("🔍 [VacancyService] Fetching vacancies for config ID=$configId: '${config.keywords}'")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)
        log.info("📥 [VacancyService] Received ${vacancyDtos.size} vacancies from HH.ru API for config ID=$configId")

        val existingIds = vacancyRepository.findAllIds().toSet()
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
        } else {
            log.info("ℹ️ [VacancyService] No new vacancies to save for config ID=$configId")
        }

        return newVacancies
    }
}
