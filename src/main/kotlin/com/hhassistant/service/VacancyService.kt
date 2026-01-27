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
    @Value("\${app.max-vacancies-per-cycle:50}") private val maxVacanciesPerCycle: Int,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Загружает новые вакансии из HH.ru API и сохраняет их в БД.
     *
     * @return Список новых вакансий для анализа
     */
    suspend fun fetchAndSaveNewVacancies(): List<Vacancy> {
        log.info("Starting to fetch new vacancies")

        val activeConfigs = searchConfigRepository.findByIsActiveTrue()
        if (activeConfigs.isEmpty()) {
            log.warn("No active search configurations found")
            return emptyList()
        }

        val allNewVacancies = mutableListOf<Vacancy>()

        for (config in activeConfigs) {
            try {
                val vacancies = fetchVacanciesForConfig(config)
                allNewVacancies.addAll(vacancies)

                if (allNewVacancies.size >= maxVacanciesPerCycle) {
                    log.info("Reached max vacancies limit ($maxVacanciesPerCycle), stopping fetch")
                    break
                }
            } catch (e: HHAPIException.RateLimitException) {
                log.warn("Rate limit exceeded for config ${config.id}, skipping: ${e.message}")
                // Прерываем загрузку при rate limit, чтобы не усугубить ситуацию
                break
            } catch (e: HHAPIException) {
                log.error("HH.ru API error fetching vacancies for config ${config.id}: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            } catch (e: Exception) {
                log.error("Unexpected error fetching vacancies for config ${config.id}: ${e.message}", e)
                // Продолжаем с другими конфигурациями
            }
        }

        val newVacancies = allNewVacancies.take(maxVacanciesPerCycle)
        log.info("Fetched ${newVacancies.size} new vacancies")

        return newVacancies
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
        log.debug("Fetching vacancies for config: ${config.keywords}")

        val vacancyDtos = hhVacancyClient.searchVacancies(config)
        val existingIds = vacancyRepository.findAllIds().toSet()

        val newVacancies = vacancyDtos
            .filter { !existingIds.contains(it.id) }
            .map { it.toEntity(formattingConfig) }
            .take(maxVacanciesPerCycle)

        if (newVacancies.isNotEmpty()) {
            vacancyRepository.saveAll(newVacancies)
            log.info("Saved ${newVacancies.size} new vacancies for config ${config.id}")
        }

        return newVacancies
    }
}
