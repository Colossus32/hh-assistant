package com.hhassistant.service

import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Сервис для обработки очереди ретраев генерации сопроводительных писем
 */
@Service
class CoverLetterRetryService(
    private val vacancyAnalysisRepository: VacancyAnalysisRepository,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val vacancyRepository: VacancyRepository,
    private val resumeService: ResumeService,
    @Value("\${app.analysis.cover-letter.retry-queue.enabled:true}") private val retryQueueEnabled: Boolean,
    @Value("\${app.analysis.cover-letter.retry-queue.batch-size:10}") private val batchSize: Int,
    @Value("\${app.analysis.cover-letter.max-retries:3}") private val maxRetries: Int,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает очередь ретраев для генерации сопроводительных писем
     * Запускается по расписанию из application.yml
     */
    @Scheduled(cron = "\${app.analysis.cover-letter.retry-queue.cron:0 */5 * * * *}")
    fun processRetryQueue() {
        if (!retryQueueEnabled) {
            log.debug("🔄 [CoverLetterRetry] Retry queue is disabled, skipping")
            return
        }

        log.info("🔄 [CoverLetterRetry] Starting to process retry queue...")

        runBlocking {
            try {
                // Получаем анализы, которые нужно повторить
                val analysesToRetry = vacancyAnalysisRepository.findByCoverLetterGenerationStatusAndCoverLetterAttemptsLessThan(
                    CoverLetterGenerationStatus.RETRY_QUEUED,
                    maxRetries,
                )

                if (analysesToRetry.isEmpty()) {
                    log.debug("ℹ️ [CoverLetterRetry] No analyses in retry queue")
                    return@runBlocking
                }

                log.info("📋 [CoverLetterRetry] Found ${analysesToRetry.size} analyses in retry queue")
                
                // Обрабатываем батчами
                val batches = analysesToRetry.chunked(batchSize)
                log.info("📦 [CoverLetterRetry] Processing ${batches.size} batch(es) of up to $batchSize analyses each")

                var successCount = 0
                var failureCount = 0

                for ((batchIndex, batch) in batches.withIndex()) {
                    log.info("🔄 [CoverLetterRetry] Processing batch ${batchIndex + 1}/${batches.size} (${batch.size} analyses)")

                    for (analysis in batch) {
                        try {
                            // Обновляем статус на IN_PROGRESS
                            val updatedAnalysis = analysis.copy(
                                coverLetterGenerationStatus = CoverLetterGenerationStatus.IN_PROGRESS,
                                coverLetterLastAttemptAt = LocalDateTime.now(),
                            )
                            vacancyAnalysisRepository.save(updatedAnalysis)

                            // Пытаемся сгенерировать письмо
                            val result = retryCoverLetterGeneration(updatedAnalysis)

                            if (result != null) {
                                // Успешно сгенерировано
                                val successAnalysis = updatedAnalysis.copy(
                                    suggestedCoverLetter = result,
                                    coverLetterGenerationStatus = CoverLetterGenerationStatus.SUCCESS,
                                    coverLetterAttempts = updatedAnalysis.coverLetterAttempts + 1,
                                )
                                vacancyAnalysisRepository.save(successAnalysis)
                                successCount++
                                log.info("✅ [CoverLetterRetry] Successfully generated cover letter for analysis ${analysis.id} (vacancy: ${analysis.vacancyId})")
                            } else {
                                // Не удалось сгенерировать
                                val newAttempts = updatedAnalysis.coverLetterAttempts + 1
                                val newStatus = if (newAttempts >= maxRetries) {
                                    CoverLetterGenerationStatus.FAILED
                                } else {
                                    CoverLetterGenerationStatus.RETRY_QUEUED
                                }

                                val failedAnalysis = updatedAnalysis.copy(
                                    coverLetterGenerationStatus = newStatus,
                                    coverLetterAttempts = newAttempts,
                                )
                                vacancyAnalysisRepository.save(failedAnalysis)
                                failureCount++

                                if (newStatus == CoverLetterGenerationStatus.FAILED) {
                                    log.warn("❌ [CoverLetterRetry] Failed to generate cover letter for analysis ${analysis.id} after $maxRetries attempts. Marking as FAILED.")
                                } else {
                                    log.warn("⚠️ [CoverLetterRetry] Failed to generate cover letter for analysis ${analysis.id} (attempt $newAttempts/$maxRetries). Queued for retry.")
                                }
                            }
                        } catch (e: Exception) {
                            log.error("❌ [CoverLetterRetry] Error processing analysis ${analysis.id}: ${e.message}", e)
                            failureCount++

                            // Возвращаем в очередь или помечаем как FAILED
                            val newAttempts = analysis.coverLetterAttempts + 1
                            val newStatus = if (newAttempts >= maxRetries) {
                                CoverLetterGenerationStatus.FAILED
                            } else {
                                CoverLetterGenerationStatus.RETRY_QUEUED
                            }

                            val errorAnalysis = analysis.copy(
                                coverLetterGenerationStatus = newStatus,
                                coverLetterAttempts = newAttempts,
                                coverLetterLastAttemptAt = LocalDateTime.now(),
                            )
                            vacancyAnalysisRepository.save(errorAnalysis)
                        }
                    }
                }

                log.info("✅ [CoverLetterRetry] Queue processing completed: $successCount successful, $failureCount failed")
            } catch (e: Exception) {
                log.error("❌ [CoverLetterRetry] Error processing retry queue: ${e.message}", e)
            }
        }
    }

    /**
     * Пытается сгенерировать сопроводительное письмо для анализа
     */
    private suspend fun retryCoverLetterGeneration(analysis: VacancyAnalysis): String? {
        try {
            // Получаем вакансию
            val vacancy = vacancyRepository.findById(analysis.vacancyId).orElse(null)
            if (vacancy == null) {
                log.error("❌ [CoverLetterRetry] Vacancy ${analysis.vacancyId} not found for analysis ${analysis.id}")
                return null
            }

            // Получаем резюме
            val resume = resumeService.loadResume()
            val resumeStructure = resumeService.getResumeStructure(resume)

            // Используем метод из VacancyAnalysisService для генерации письма
            // Вызываем analyzeVacancy, который пересоздаст анализ, но обновит письмо
            // Это не идеально, но работает
            val updatedAnalysis = vacancyAnalysisService.analyzeVacancy(vacancy)
            return updatedAnalysis.suggestedCoverLetter
        } catch (e: Exception) {
            log.error("❌ [CoverLetterRetry] Error generating cover letter for analysis ${analysis.id}: ${e.message}", e)
            return null
        }
    }
    
    // Вспомогательный метод для создания AnalysisResult (нужно добавить в VacancyAnalysisService)

    /**
     * Добавляет анализ в очередь ретраев
     */
    fun queueForRetry(analysis: VacancyAnalysis) {
        if (!retryQueueEnabled) {
            log.debug("🔄 [CoverLetterRetry] Retry queue is disabled, not queuing analysis ${analysis.id}")
            return
        }

        val updatedAnalysis = analysis.copy(
            coverLetterGenerationStatus = CoverLetterGenerationStatus.RETRY_QUEUED,
            coverLetterLastAttemptAt = LocalDateTime.now(),
        )
        vacancyAnalysisRepository.save(updatedAnalysis)
        log.info("📋 [CoverLetterRetry] Queued analysis ${analysis.id} (vacancy: ${analysis.vacancyId}) for cover letter retry")
    }
}

