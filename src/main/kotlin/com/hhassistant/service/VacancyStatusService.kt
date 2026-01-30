package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.event.VacancyStatusChangedEvent
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.repository.VacancyRepository
import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Сервис для управления статусами вакансий
 * Публикует VacancyStatusChangedEvent при изменении статуса
 */
@Service
class VacancyStatusService(
    private val vacancyRepository: VacancyRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обновляет статус вакансии и публикует событие
     */
    @CacheEvict(value = ["vacancyList", "vacancyDetails"], allEntries = true)
    fun updateVacancyStatus(updatedVacancy: Vacancy) {
        try {
            val oldStatus = vacancyRepository.findById(updatedVacancy.id)
                .map { it.status }
                .orElse(null)
            
            vacancyRepository.save(updatedVacancy)
            log.info("✅ [StatusService] Updated vacancy ${updatedVacancy.id} ('${updatedVacancy.name}') status: $oldStatus -> ${updatedVacancy.status}")

            // Публикуем событие изменения статуса
            eventPublisher.publishEvent(
                VacancyStatusChangedEvent(
                    this,
                    updatedVacancy,
                    oldStatus,
                    updatedVacancy.status,
                )
            )
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
     * Обновляет статус вакансии по ID
     */
    fun updateVacancyStatusById(vacancyId: String, newStatus: VacancyStatus): Vacancy? {
        val vacancy = vacancyRepository.findById(vacancyId).orElse(null)
        return if (vacancy != null) {
            updateVacancyStatus(vacancy.withStatus(newStatus))
            vacancyRepository.findById(vacancyId).orElse(null) // Возвращаем обновленную версию
        } else {
            log.warn("⚠️ [StatusService] Vacancy with ID $vacancyId not found, cannot update status")
            null
        }
    }
}


