package com.hhassistant.service

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.repository.VacancySkillRepository
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для очистки несуществующих вакансий из базы данных.
 * 
 * Проверяет вакансии на существование в HH.ru API и удаляет те, которые больше не существуют (404).
 */
@Service
class VacancyCleanupService(
    private val vacancyRepository: VacancyRepository,
    private val vacancySkillRepository: VacancySkillRepository,
    private val vacancyAnalysisRepository: VacancyAnalysisRepository,
    private val hhVacancyClient: HHVacancyClient,
    @Value("\${app.cleanup.enabled:true}") private val cleanupEnabled: Boolean,
    @Value("\${app.cleanup.batch-size:50}") private val batchSize: Int,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Периодически проверяет все вакансии на существование и удаляет несуществующие.
     * Запускается по расписанию из application.yml (app.cleanup.schedule).
     */
    @Scheduled(cron = "\${app.cleanup.schedule:0 0 2 * * *}") // По умолчанию: каждый день в 2:00
    fun cleanupNonExistentVacancies() {
        if (!cleanupEnabled) {
            log.debug("🧹 [VacancyCleanup] Cleanup is disabled, skipping")
            return
        }

        log.info("🧹 [VacancyCleanup] Starting cleanup of non-existent vacancies...")
        
        runBlocking {
            val allVacancies = vacancyRepository.findAll()
            log.info("📊 [VacancyCleanup] Checking ${allVacancies.size} vacancies for existence...")

            var deletedCount = 0
            var checkedCount = 0
            var errorCount = 0

            // Обрабатываем вакансии батчами для избежания перегрузки API
            allVacancies.chunked(batchSize).forEach { batch ->
                batch.forEach { vacancy ->
                    try {
                        checkedCount++
                        
                        // Проверяем существование вакансии через API
                        try {
                            hhVacancyClient.getVacancyDetails(vacancy.id)
                            // Вакансия существует - ничего не делаем
                            if (checkedCount % 10 == 0) {
                                log.debug("✅ [VacancyCleanup] Checked $checkedCount/${allVacancies.size} vacancies, deleted: $deletedCount")
                            }
                        } catch (e: HHAPIException.NotFoundException) {
                            // Вакансия не найдена (404) - удаляем из БД
                            log.warn("🗑️ [VacancyCleanup] Vacancy ${vacancy.id} ('${vacancy.name}') not found on HH.ru (404), deleting from database")
                            deleteVacancyAndRelatedData(vacancy.id)
                            deletedCount++
                        } catch (e: HHAPIException.RateLimitException) {
                            log.warn("⏸️ [VacancyCleanup] Rate limit exceeded, pausing cleanup")
                            errorCount++
                            return@forEach // Пропускаем остальные вакансии в батче
                        } catch (e: Exception) {
                            log.warn("⚠️ [VacancyCleanup] Error checking vacancy ${vacancy.id}: ${e.message}")
                            errorCount++
                        }
                    } catch (e: Exception) {
                        log.error("❌ [VacancyCleanup] Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
                        errorCount++
                    }
                }
                
                // Небольшая задержка между батчами для избежания rate limit
                if (batch.size == batchSize) {
                    kotlinx.coroutines.delay(1000) // 1 секунда между батчами
                }
            }

            log.info("✅ [VacancyCleanup] Cleanup completed: checked $checkedCount, deleted $deletedCount, errors $errorCount out of ${allVacancies.size} vacancies")
        }
    }

    /**
     * Проверяет и удаляет несуществующую вакансию.
     * 
     * @param vacancyId ID вакансии для проверки
     * @return true если вакансия была удалена, false если существует или произошла ошибка
     */
    suspend fun checkAndDeleteIfNotExists(vacancyId: String): Boolean {
        return try {
            // Проверяем существование вакансии
            hhVacancyClient.getVacancyDetails(vacancyId)
            // Вакансия существует
            false
        } catch (e: HHAPIException.NotFoundException) {
            // Вакансия не найдена - удаляем
            log.warn("🗑️ [VacancyCleanup] Vacancy $vacancyId not found on HH.ru (404), deleting from database")
            deleteVacancyAndRelatedData(vacancyId)
            true
        } catch (e: Exception) {
            log.warn("⚠️ [VacancyCleanup] Error checking vacancy $vacancyId: ${e.message}")
            false
        }
    }

    /**
     * Удаляет вакансию и все связанные данные (навыки, анализы).
     */
    @Transactional
    fun deleteVacancyAndRelatedData(vacancyId: String) {
        try {
            // Удаляем связи вакансия-навык
            vacancySkillRepository.deleteByVacancyId(vacancyId)
            log.debug("🗑️ [VacancyCleanup] Deleted VacancySkill links for vacancy $vacancyId")
            
            // Удаляем анализы вакансии
            vacancyAnalysisRepository.findByVacancyId(vacancyId)?.let { analysis ->
                vacancyAnalysisRepository.delete(analysis)
                log.debug("🗑️ [VacancyCleanup] Deleted VacancyAnalysis for vacancy $vacancyId")
            }
            
            // Удаляем саму вакансию
            vacancyRepository.deleteById(vacancyId)
            
            log.info("✅ [VacancyCleanup] Deleted vacancy $vacancyId and all related data")
        } catch (e: Exception) {
            log.error("❌ [VacancyCleanup] Failed to delete vacancy $vacancyId: ${e.message}", e)
            throw e
        }
    }
}

