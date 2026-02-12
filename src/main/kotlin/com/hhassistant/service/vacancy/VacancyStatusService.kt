package com.hhassistant.service.vacancy

import com.hhassistant.aspect.Loggable
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.repository.VacancyRepository
import jakarta.persistence.OptimisticLockException
import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service

/**
 * Сервис для управления статусами вакансий
 * Использует прямые вызовы вместо событий
 */
@Service
class VacancyStatusService(
    private val vacancyRepository: VacancyRepository,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обновляет статус вакансии
     */
    @Loggable
    @CacheEvict(value = ["vacancyList", "vacancyDetails"], allEntries = true)
    fun updateVacancyStatus(updatedVacancy: Vacancy) {
        try {
            val oldStatus = vacancyRepository.findById(updatedVacancy.id)
                .map { it.status }
                .orElse(null)

            vacancyRepository.save(updatedVacancy)
            log.info(
                "✅ [StatusService] Updated vacancy ${updatedVacancy.id} ('${updatedVacancy.name}') status: $oldStatus -> ${updatedVacancy.status}",
            )
        } catch (e: OptimisticLockException) {
            log.warn(
                "⚠️ [StatusService] Optimistic lock conflict for vacancy ${updatedVacancy.id}: " +
                    "vacancy was modified by another transaction. Retrying with fresh data...",
            )
            // Пытаемся загрузить актуальную версию и обновить снова
            try {
                val currentVacancy = vacancyRepository.findById(updatedVacancy.id).orElse(null)
                if (currentVacancy != null) {
                    // Обновляем статус с актуальной версией
                    val updatedWithCurrentVersion = currentVacancy.withStatus(updatedVacancy.status)
                    vacancyRepository.save(updatedWithCurrentVersion)
                    log.info(
                        "✅ [StatusService] Successfully updated vacancy ${updatedVacancy.id} after optimistic lock retry",
                    )
                } else {
                    log.error("❌ [StatusService] Vacancy ${updatedVacancy.id} not found after optimistic lock conflict")
                    throw VacancyProcessingException(
                        "Vacancy not found after optimistic lock conflict",
                        updatedVacancy.id,
                        e,
                    )
                }
            } catch (retryException: Exception) {
                log.error(
                    "❌ [StatusService] Failed to update vacancy ${updatedVacancy.id} after optimistic lock retry: ${retryException.message}",
                    retryException,
                )
                throw VacancyProcessingException(
                    "Failed to update vacancy status after optimistic lock conflict",
                    updatedVacancy.id,
                    retryException,
                )
            }
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
