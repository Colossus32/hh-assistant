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
     * Обновляет статус вакансии.
     * ВАЖНО: Для корутин всегда перезагружает entity из БД перед обновлением,
     * чтобы избежать конфликтов optimistic locking с detached entities.
     * @param updatedVacancy Вакансия с новым статусом (может быть detached entity)
     */
    @Loggable
    @CacheEvict(value = ["vacancyList", "vacancyDetails"], allEntries = true)
    fun updateVacancyStatus(updatedVacancy: Vacancy) {
        try {
            // ВАЖНО: Всегда перезагружаем entity из БД перед обновлением
            // Это предотвращает конфликты optimistic locking при вызове из корутин
            // где entity может быть detached (отсоединена от Hibernate session)
            val currentVacancy = vacancyRepository.findById(updatedVacancy.id).orElse(null)
            if (currentVacancy == null) {
                log.warn("⚠️ [StatusService] Vacancy ${updatedVacancy.id} not found in database")
                throw VacancyProcessingException(
                    "Vacancy not found",
                    updatedVacancy.id,
                )
            }

            val oldStatus = currentVacancy.status
            val newStatus = updatedVacancy.status

            // Если статус не изменился, пропускаем обновление
            if (oldStatus == newStatus) {
                log.debug("[StatusService] Vacancy ${updatedVacancy.id} already has status $newStatus, skipping update")
                return
            }

            // Обновляем статус с актуальной версией entity (для optimistic locking)
            val updatedWithCurrentVersion = currentVacancy.withStatus(newStatus)
            vacancyRepository.save(updatedWithCurrentVersion)
            log.info(
                "✅ [StatusService] Updated vacancy ${updatedVacancy.id} ('${updatedVacancy.name}') status: $oldStatus -> $newStatus",
            )
        } catch (e: OptimisticLockException) {
            log.warn(
                "⚠️ [StatusService] Optimistic lock conflict for vacancy ${updatedVacancy.id}: " +
                    "vacancy was modified by another transaction. Retrying with fresh data...",
            )
            // Пытаемся загрузить актуальную версию и обновить снова (максимум 1 retry)
            try {
                val currentVacancy = vacancyRepository.findById(updatedVacancy.id).orElse(null)
                if (currentVacancy != null) {
                    val oldStatus = currentVacancy.status
                    val newStatus = updatedVacancy.status

                    // Если статус уже обновлен другим потоком, пропускаем
                    if (oldStatus == newStatus) {
                        log.debug(
                            "[StatusService] Vacancy ${updatedVacancy.id} already has status $newStatus " +
                                "(updated by another transaction), skipping",
                        )
                        return
                    }

                    // Обновляем статус с актуальной версией
                    val updatedWithCurrentVersion = currentVacancy.withStatus(newStatus)
                    vacancyRepository.save(updatedWithCurrentVersion)
                    log.info(
                        "✅ [StatusService] Successfully updated vacancy ${updatedVacancy.id} after optimistic lock retry: $oldStatus -> $newStatus",
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
