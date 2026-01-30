package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.event.CoverLetterGeneratedEvent
import com.hhassistant.event.CoverLetterGenerationFailedEvent
import com.hhassistant.event.VacancyReadyForTelegramEvent
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory очередь для обработки генерации сопроводительных писем
 *
 * Логика работы:
 * 1. Релевантные вакансии после анализа попадают в очередь
 * 2. Очередь обрабатывается с ограничением параллелизма
 * 3. При неудаче генерации - вакансия помещается в конец очереди
 * 4. После 3 неудачных попыток - вакансия отправляется в Telegram без письма
 * 5. При успешной генерации - вакансия сразу отправляется в Telegram
 */
@Service
class CoverLetterQueueService(
    private val vacancyAnalysisRepository: VacancyAnalysisRepository,
    private val coverLetterGenerationService: CoverLetterGenerationService,
    private val vacancyRepository: VacancyRepository,
    private val vacancyStatusService: VacancyStatusService,
    private val eventPublisher: ApplicationEventPublisher,
    private val resumeService: ResumeService,
    private val objectMapper: ObjectMapper,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    @Value("\${app.analysis.cover-letter.queue.enabled:true}") private val queueEnabled: Boolean,
    @Value("\${app.analysis.cover-letter.max-retries:3}") private val maxRetries: Int,
    @Value("\${app.analysis.cover-letter.queue.max-concurrent:2}") private val maxConcurrent: Int,
) {
    private val log = KotlinLogging.logger {}

    // In-memory очередь для обработки генерации писем
    private val queue = ConcurrentLinkedQueue<QueueItem>()

    // Канал для обработки очереди (для корутин)
    private val queueChannel = Channel<QueueItem>(Channel.UNLIMITED)

    // Флаг работы очереди
    private val isRunning = AtomicBoolean(false)

    // Scope для корутин
    private val queueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Семафор для ограничения параллелизма
    private val processingSemaphore = Semaphore(maxConcurrent)

    /**
     * Элемент очереди
     */
    data class QueueItem(
        val vacancyId: String,
        val analysisId: Long,
        val attemptNumber: Int = 1,
    )

    /**
     * Загружает ожидающие вакансии в очередь при старте приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun loadPendingVacanciesOnStartup() {
        if (!queueEnabled) {
            log.info("ℹ️ [CoverLetterQueue] Queue is disabled, skipping startup load")
            return
        }

        log.info("🔄 [CoverLetterQueue] Loading pending vacancies into queue on startup...")

        runBlocking {
            try {
                // Находим все анализы, которые ждут генерации письма
                val pendingAnalyses = vacancyAnalysisRepository.findByCoverLetterGenerationStatus(
                    CoverLetterGenerationStatus.RETRY_QUEUED,
                )

                // Также загружаем анализы со статусом IN_PROGRESS (на случай, если приложение упало во время обработки)
                val inProgressAnalyses = vacancyAnalysisRepository.findByCoverLetterGenerationStatus(
                    CoverLetterGenerationStatus.IN_PROGRESS,
                )

                val allPending = pendingAnalyses + inProgressAnalyses

                if (allPending.isEmpty()) {
                    log.info("ℹ️ [CoverLetterQueue] No pending vacancies found on startup")
                    return@runBlocking
                }

                log.info("📋 [CoverLetterQueue] Found ${allPending.size} pending analyses on startup")

                // Добавляем в очередь
                for (analysis in allPending) {
                    val vacancy = vacancyRepository.findById(analysis.vacancyId).orElse(null)
                    if (vacancy == null) {
                        log.warn("⚠️ [CoverLetterQueue] Vacancy ${analysis.vacancyId} not found for analysis ${analysis.id}, skipping")
                        continue
                    }

                    // Проверяем, что вакансия еще релевантна
                    if (!analysis.isRelevant) {
                        log.debug("ℹ️ [CoverLetterQueue] Analysis ${analysis.id} is not relevant, skipping")
                        continue
                    }

                    // Проверяем, что не превышен лимит попыток
                    if (analysis.coverLetterAttempts >= maxRetries) {
                        log.warn("⚠️ [CoverLetterQueue] Analysis ${analysis.id} exceeded max attempts (${analysis.coverLetterAttempts}), marking as FAILED and sending without cover letter")
                        queueScope.launch {
                            handleFailedGeneration(analysis, vacancy)
                        }
                        continue
                    }

                    // Добавляем в очередь
                    if (analysis.id != null) {
                        enqueue(analysis.id, analysis.vacancyId, analysis.coverLetterAttempts + 1)
                    }
                }

                log.info("✅ [CoverLetterQueue] Loaded ${queue.size} items into queue on startup")

                // Запускаем обработку очереди
                startQueueProcessing()
            } catch (e: Exception) {
                log.error("❌ [CoverLetterQueue] Error loading pending vacancies on startup: ${e.message}", e)
            }
        }
    }

    /**
     * Добавляет вакансию в очередь генерации сопроводительного письма
     */
    fun enqueue(analysisId: Long, vacancyId: String, attemptNumber: Int = 1) {
        if (!queueEnabled) {
            log.debug("ℹ️ [CoverLetterQueue] Queue is disabled, skipping enqueue")
            return
        }

        val item = QueueItem(vacancyId, analysisId, attemptNumber)
        queue.offer(item)
        queueScope.launch {
            queueChannel.send(item)
        }

        // Обновляем метрику размера очереди
        metricsService.setQueueSize(queue.size)

        log.info("📥 [CoverLetterQueue] Enqueued vacancy $vacancyId (analysis: $analysisId, attempt: $attemptNumber), queue size: ${queue.size}")

        // Запускаем обработку, если еще не запущена
        if (!isRunning.get()) {
            startQueueProcessing()
        }
    }

    /**
     * Запускает обработку очереди
     */
    private fun startQueueProcessing() {
        if (isRunning.getAndSet(true)) {
            log.debug("ℹ️ [CoverLetterQueue] Queue processing already running")
            return
        }

        log.info("🚀 [CoverLetterQueue] Starting queue processing...")

        queueScope.launch {
            try {
                for (item in queueChannel) {
                    launch {
                        processQueueItem(item)
                    }
                }
            } catch (e: Exception) {
                log.error("❌ [CoverLetterQueue] Error in queue processing: ${e.message}", e)
                isRunning.set(false)
            }
        }
    }

    /**
     * Обрабатывает элемент очереди
     */
    private suspend fun processQueueItem(item: QueueItem) {
        processingSemaphore.withPermit {
            try {
                log.info("🔄 [CoverLetterQueue] Processing vacancy ${item.vacancyId} (analysis: ${item.analysisId}, attempt: ${item.attemptNumber})")

                // Получаем анализ и вакансию
                val analysis = vacancyAnalysisRepository.findById(item.analysisId).orElse(null)
                if (analysis == null) {
                    log.warn("⚠️ [CoverLetterQueue] Analysis ${item.analysisId} not found, skipping")
                    return@withPermit
                }

                val vacancy = vacancyRepository.findById(item.vacancyId).orElse(null)
                if (vacancy == null) {
                    log.warn("⚠️ [CoverLetterQueue] Vacancy ${item.vacancyId} not found, skipping")
                    return@withPermit
                }

                // Проверяем, что вакансия еще релевантна
                if (!analysis.isRelevant) {
                    log.debug("ℹ️ [CoverLetterQueue] Vacancy ${item.vacancyId} is not relevant anymore, skipping")
                    return@withPermit
                }

                // Обновляем статус на IN_PROGRESS
                val updatedAnalysis = analysis.withCoverLetterStatus(
                    CoverLetterGenerationStatus.IN_PROGRESS,
                    item.attemptNumber,
                )
                vacancyAnalysisRepository.save(updatedAnalysis)

                // Пытаемся сгенерировать письмо (одна попытка)
                val coverLetter = try {
                    generateCoverLetterOnce(vacancy, updatedAnalysis)
                } catch (e: Exception) {
                    log.error("❌ [CoverLetterQueue] Error generating cover letter for vacancy ${item.vacancyId}: ${e.message}", e)
                    null
                }

                if (coverLetter != null) {
                    // Успешно сгенерировано - отправляем в Telegram
                    handleSuccessfulGeneration(updatedAnalysis, vacancy, coverLetter)
                } else {
                    // Не удалось сгенерировать
                    handleFailedGenerationAttempt(updatedAnalysis, vacancy, item.attemptNumber)
                }
            } catch (e: Exception) {
                log.error("❌ [CoverLetterQueue] Error processing queue item ${item.vacancyId}: ${e.message}", e)
            }
        }
    }

    /**
     * Генерирует сопроводительное письмо (одна попытка)
     */
    private suspend fun generateCoverLetterOnce(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ): String? {
        return try {
            val resume = resumeService.loadResume()
            val resumeStructure = resumeService.getResumeStructure(resume)

            // Восстанавливаем AnalysisResult из сохраненного анализа
            val matchedSkills = try {
                val skillsJson = analysis.matchedSkills
                if (skillsJson != null && skillsJson.isNotBlank()) {
                    @Suppress("UNCHECKED_CAST")
                    (objectMapper.readValue(skillsJson, List::class.java) as List<String>)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                log.warn("⚠️ [CoverLetterQueue] Error parsing matched skills: ${e.message}")
                emptyList()
            }

            val analysisResult = VacancyAnalysisService.AnalysisResult(
                isRelevant = analysis.isRelevant,
                relevanceScore = analysis.relevanceScore,
                reasoning = analysis.reasoning,
                matchedSkills = matchedSkills,
            )

            // Генерируем письмо (одна попытка)
            val startTime = System.currentTimeMillis()
            val result = coverLetterGenerationService.generateCoverLetter(vacancy, resume, resumeStructure, analysisResult)
            val duration = System.currentTimeMillis() - startTime
            metricsService.recordCoverLetterGenerationTime(duration)
            result
        } catch (e: OllamaException) {
            log.warn("⚠️ [CoverLetterQueue] Ollama error generating cover letter: ${e.message}")
            null
        } catch (e: Exception) {
            log.error("❌ [CoverLetterQueue] Unexpected error generating cover letter: ${e.message}", e)
            null
        }
    }

    /**
     * Обрабатывает успешную генерацию письма
     */
    private suspend fun handleSuccessfulGeneration(
        analysis: VacancyAnalysis,
        vacancy: Vacancy,
        coverLetter: String,
    ) {
        log.info("✅ [CoverLetterQueue] Successfully generated cover letter for vacancy ${vacancy.id}")

        // Обновляем метрики
        metricsService.incrementCoverLettersGenerated()
        metricsService.setQueueSize(queue.size)

        // Сохраняем письмо
        val successAnalysis = analysis.withCoverLetter(coverLetter)
        vacancyAnalysisRepository.save(successAnalysis)

        // Публикуем событие успешной генерации
        eventPublisher.publishEvent(CoverLetterGeneratedEvent(this, vacancy, successAnalysis))

        // Публикуем событие готовности к отправке в Telegram
        eventPublisher.publishEvent(VacancyReadyForTelegramEvent(this, vacancy, successAnalysis))
    }

    /**
     * Обрабатывает неудачную попытку генерации
     */
    private suspend fun handleFailedGenerationAttempt(
        analysis: VacancyAnalysis,
        vacancy: Vacancy,
        attemptNumber: Int,
    ) {
        if (attemptNumber >= maxRetries) {
            // Превышен лимит попыток - отправляем без письма
            log.warn("❌ [CoverLetterQueue] Max attempts ($maxRetries) reached for vacancy ${vacancy.id}, sending without cover letter")
            handleFailedGeneration(analysis, vacancy)
        } else {
            // Помещаем в конец очереди для повторной попытки
            log.info("🔄 [CoverLetterQueue] Re-queuing vacancy ${vacancy.id} for retry (attempt ${attemptNumber + 1}/$maxRetries)")

            // Обновляем метрики
            metricsService.incrementCoverLettersRetry()
            metricsService.setQueueSize(queue.size)

            val retryAnalysis = analysis.withCoverLetterStatus(
                CoverLetterGenerationStatus.RETRY_QUEUED,
                attemptNumber,
            )
            vacancyAnalysisRepository.save(retryAnalysis)

            // Добавляем в конец очереди
            if (analysis.id != null) {
                enqueue(analysis.id, vacancy.id, attemptNumber + 1)
            }
        }
    }

    /**
     * Обрабатывает окончательно неудачную генерацию (после всех попыток)
     */
    private suspend fun handleFailedGeneration(
        analysis: VacancyAnalysis,
        vacancy: Vacancy,
    ) {
        // Обновляем метрики
        metricsService.incrementCoverLettersFailed()
        metricsService.setQueueSize(queue.size)

        // Помечаем как FAILED
        val failedAnalysis = analysis.withCoverLetterStatus(
            CoverLetterGenerationStatus.FAILED,
            maxRetries,
        )
        vacancyAnalysisRepository.save(failedAnalysis)

        // Публикуем событие неудачной генерации
        eventPublisher.publishEvent(
            CoverLetterGenerationFailedEvent(
                this,
                vacancy,
                failedAnalysis,
                maxRetries,
            ),
        )

        // Публикуем событие готовности к отправке в Telegram (без письма)
        eventPublisher.publishEvent(VacancyReadyForTelegramEvent(this, vacancy, failedAnalysis))
    }

    /**
     * Получает размер очереди
     */
    fun getQueueSize(): Int = queue.size

    /**
     * Очищает очередь (для тестирования)
     */
    fun clearQueue() {
        queue.clear()
        log.info("🧹 [CoverLetterQueue] Queue cleared")
    }

    @PreDestroy
    fun shutdown() {
        log.info("🛑 [CoverLetterQueue] Shutting down queue...")
        isRunning.set(false)
        queueScope.cancel()
        queueChannel.close()
    }
}
