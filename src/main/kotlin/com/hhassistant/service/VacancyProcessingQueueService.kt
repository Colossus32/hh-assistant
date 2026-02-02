package com.hhassistant.service

import com.hhassistant.aspect.Loggable
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.VacancyProcessingException
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
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
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory очередь для обработки вакансий (приоритетная очередь для анализа)
 *
 * Логика работы:
 * 1. Вакансии из HH.ru API попадают в очередь (статус QUEUED)
 * 2. При добавлении проверяется, не была ли вакансия уже обработана
 * 3. Очередь обрабатывается с ограничением параллелизма на корутинах
 * 4. Обработка: анализ Ollama на соответствие резюме → если подходит, отправка в Telegram → добавление в очередь навыков
 * 5. По расписанию необработанные QUEUED вакансии из БД добавляются в очередь
 */
@Service
class VacancyProcessingQueueService(
    private val vacancyRepository: com.hhassistant.repository.VacancyRepository,
    private val vacancyStatusService: VacancyStatusService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val vacancyNotificationService: VacancyNotificationService,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    @Value("\${app.vacancy-processing.queue.enabled:true}") private val queueEnabled: Boolean,
    @Value("\${app.vacancy-processing.queue.max-concurrent:3}") private val maxConcurrent: Int,
    @Value("\${app.vacancy-processing.queue.batch-size:10}") private val batchSize: Int,
) {
    private val log = KotlinLogging.logger {}

    // Приоритетная очередь для обработки вакансий (приоритет по дате публикации - более свежие первыми)
    private val queue = PriorityBlockingQueue<QueueItem>(11) { a, b ->
        // Сравниваем по дате публикации (более свежие имеют больший приоритет)
        // Если дата публикации не указана, используем дату добавления в очередь
        val aTime = a.publishedAt ?: a.addedAt
        val bTime = b.publishedAt ?: b.addedAt
        bTime.compareTo(aTime) // Обратный порядок - более свежие первыми
    }

    // Канал для обработки очереди (для корутин)
    private val queueChannel = Channel<QueueItem>(Channel.UNLIMITED)

    // Множество обрабатываемых вакансий (для проверки дубликатов)
    private val processingVacancies = ConcurrentHashMap<String, Boolean>()

    // Флаг работы очереди
    private val isRunning = AtomicBoolean(false)

    // Scope для корутин
    private val queueScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error("❌ [VacancyProcessingQueue] Unhandled exception in queue coroutine: ${exception.message}", exception)
        },
    )

    // Семафор для ограничения параллелизма
    private val processingSemaphore = Semaphore(maxConcurrent)

    /**
     * Элемент очереди с приоритетом
     */
    data class QueueItem(
        val vacancyId: String,
        val addedAt: LocalDateTime = LocalDateTime.now(),
        val publishedAt: LocalDateTime? = null, // Дата публикации для приоритета
    ) : Comparable<QueueItem> {
        override fun compareTo(other: QueueItem): Int {
            // Сравниваем по дате публикации (более свежие имеют больший приоритет)
            val thisTime = publishedAt ?: addedAt
            val otherTime = other.publishedAt ?: other.addedAt
            return otherTime.compareTo(thisTime) // Обратный порядок - более свежие первыми
        }
    }

    /**
     * Загружает ожидающие вакансии в очередь при старте приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun loadPendingVacanciesOnStartup() {
        if (!queueEnabled) {
            log.info("ℹ️ [VacancyProcessingQueue] Queue is disabled, skipping startup load")
            return
        }

        log.info("🔄 [VacancyProcessingQueue] Loading pending QUEUED vacancies into queue on startup...")

        runBlocking {
            try {
                val queuedVacancies = vacancyRepository.findByStatus(VacancyStatus.QUEUED)
                if (queuedVacancies.isEmpty()) {
                    log.info("ℹ️ [VacancyProcessingQueue] No QUEUED vacancies found on startup")
                    return@runBlocking
                }

                log.info("📋 [VacancyProcessingQueue] Found ${queuedVacancies.size} QUEUED vacancies on startup")

                // Добавляем в очередь
                for (vacancy in queuedVacancies) {
                    enqueue(vacancy.id, checkDuplicate = false) // При старте не проверяем дубликаты
                }

                log.info("✅ [VacancyProcessingQueue] Loaded ${queue.size} items into queue on startup")

                // Запускаем обработку очереди
                startQueueProcessing()
            } catch (e: Exception) {
                log.error("❌ [VacancyProcessingQueue] Error loading pending vacancies on startup: ${e.message}", e)
            }
        }
    }

    /**
     * Добавляет вакансию в очередь обработки
     *
     * @param vacancyId ID вакансии
     * @param checkDuplicate Проверять ли на дубликаты (по умолчанию true)
     * @return true если вакансия добавлена, false если уже обрабатывается или была обработана
     */
    @Loggable
    fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
        if (!queueEnabled) {
            log.debug("ℹ️ [VacancyProcessingQueue] Queue is disabled, skipping enqueue")
            return false
        }

        // Получаем вакансию из БД
        var vacancy = vacancyRepository.findById(vacancyId).orElse(null)
        if (vacancy == null) {
            log.warn("⚠️ [VacancyProcessingQueue] Vacancy $vacancyId not found in database, skipping")
            return false
        }

        // Проверяем на дубликаты
        if (checkDuplicate) {
            // Проверяем, не обрабатывается ли уже
            if (processingVacancies.containsKey(vacancyId)) {
                log.debug("⏭️ [VacancyProcessingQueue] Vacancy $vacancyId is already being processed, skipping")
                return false
            }

            // Пропускаем уже обработанные вакансии
            if (vacancy.status in listOf(
                    VacancyStatus.ANALYZED,
                    VacancyStatus.SENT_TO_USER,
                    VacancyStatus.SKIPPED,
                    VacancyStatus.NOT_INTERESTED,
                    VacancyStatus.FAILED,
                )
            ) {
                log.debug("⏭️ [VacancyProcessingQueue] Vacancy $vacancyId already processed (status: ${vacancy.status}), skipping")
                return false
            }

            // Если статус не QUEUED, обновляем его
            if (vacancy.status != VacancyStatus.QUEUED) {
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.QUEUED))
                } catch (e: Exception) {
                    log.warn("⚠️ [VacancyProcessingQueue] Failed to update status for vacancy $vacancyId: ${e.message}")
                }
            }
        }

        // Добавляем в очередь с приоритетом (по дате публикации)
        val item = QueueItem(
            vacancyId = vacancyId,
            publishedAt = vacancy.publishedAt,
        )
        queue.offer(item)
        processingVacancies[vacancyId] = true

        queueScope.launch {
            queueChannel.send(item)
        }

        // Обновляем метрику размера очереди
        metricsService.setQueueSize(queue.size)

        log.info("📥 [VacancyProcessingQueue] Enqueued vacancy $vacancyId, queue size: ${queue.size}")

        // Запускаем обработку, если еще не запущена
        if (!isRunning.get()) {
            startQueueProcessing()
        }

        return true
    }

    /**
     * Добавляет несколько вакансий в очередь
     */
    @Loggable
    fun enqueueBatch(vacancyIds: List<String>): Int {
        var addedCount = 0
        for (vacancyId in vacancyIds) {
            if (enqueue(vacancyId)) {
                addedCount++
            }
        }
        return addedCount
    }

    /**
     * Запускает обработку очереди
     */
    private fun startQueueProcessing() {
        if (isRunning.getAndSet(true)) {
            log.debug("ℹ️ [VacancyProcessingQueue] Queue processing already running")
            return
        }

        log.info("🚀 [VacancyProcessingQueue] Starting queue processing...")

        queueScope.launch {
            try {
                for (item in queueChannel) {
                    launch {
                        processQueueItem(item)
                    }
                }
            } catch (e: Exception) {
                log.error("❌ [VacancyProcessingQueue] Error in queue processing: ${e.message}", e)
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
                log.info("🔄 [VacancyProcessingQueue] Processing vacancy ${item.vacancyId}")

                // Получаем вакансию из БД
                val vacancy = vacancyRepository.findById(item.vacancyId).orElse(null)
                if (vacancy == null) {
                    log.warn("⚠️ [VacancyProcessingQueue] Vacancy ${item.vacancyId} not found, skipping")
                    processingVacancies.remove(item.vacancyId)
                    return@withPermit
                }

                // Проверяем, не была ли уже обработана
                if (vacancy.status !in listOf(VacancyStatus.QUEUED, VacancyStatus.NEW)) {
                    log.debug("ℹ️ [VacancyProcessingQueue] Vacancy ${item.vacancyId} already processed (status: ${vacancy.status}), skipping")
                    processingVacancies.remove(item.vacancyId)
                    return@withPermit
                }

                // Обрабатываем вакансию: извлечение навыков → анализ → генерация письма → отправка в Telegram
                processVacancy(vacancy)

                // Удаляем из множества обрабатываемых
                processingVacancies.remove(item.vacancyId)
                queue.remove(item)

                // Обновляем метрику размера очереди
                metricsService.setQueueSize(queue.size)
            } catch (e: Exception) {
                log.error("❌ [VacancyProcessingQueue] Error processing queue item ${item.vacancyId}: ${e.message}", e)
                processingVacancies.remove(item.vacancyId)
                queue.remove(item)
                metricsService.setQueueSize(queue.size)
            }
        }
    }

    /**
     * Обрабатывает вакансию: анализ на соответствие резюме → если подходит, отправка в Telegram → добавление в очередь навыков
     */
    private suspend fun processVacancy(vacancy: Vacancy) {
        log.info("📋 [VacancyProcessingQueue] Starting analysis pipeline for vacancy ${vacancy.id}")

        try {
            // Шаг 1: Анализ через Ollama на соответствие резюме
            log.debug("🤖 [VacancyProcessingQueue] Analyzing vacancy ${vacancy.id} via Ollama")
            val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

            // Шаг 2: Обновляем статус вакансии
            val newStatus = if (analysis.isRelevant) {
                VacancyStatus.ANALYZED
            } else {
                VacancyStatus.SKIPPED
            }
            vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
            log.debug("📝 [VacancyProcessingQueue] Updated vacancy ${vacancy.id} status to: $newStatus")

            // Шаг 3: Если вакансия релевантна (relevance_score >= minRelevanceScore) - отправляем в Telegram
            // Навыки уже сохранены в БД при анализе, если вакансия релевантна
            if (analysis.isRelevant) {
                log.info("✅ [VacancyProcessingQueue] Vacancy ${vacancy.id} is relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%)")

                // Отправляем в Telegram
                try {
                    val sentSuccessfully = vacancyNotificationService.sendVacancyToTelegram(vacancy, analysis)
                    if (sentSuccessfully) {
                        val sentAt = java.time.LocalDateTime.now()
                        vacancyStatusService.updateVacancyStatus(vacancy.withSentToTelegramAt(sentAt))
                        log.info("📱 [VacancyProcessingQueue] Successfully sent vacancy ${vacancy.id} to Telegram")
                    }
                } catch (e: Exception) {
                    log.error("❌ [VacancyProcessingQueue] Failed to send vacancy ${vacancy.id} to Telegram: ${e.message}", e)
                    // Продолжаем обработку даже если не удалось отправить
                }
            } else {
                log.debug("ℹ️ [VacancyProcessingQueue] Vacancy ${vacancy.id} is not relevant (score: ${String.format("%.2f", analysis.relevanceScore * 100)}%), skipping Telegram")
            }

            log.info("✅ [VacancyProcessingQueue] Completed processing pipeline for vacancy ${vacancy.id} (isRelevant: ${analysis.isRelevant})")
        } catch (e: OllamaException) {
            log.error("❌ [VacancyProcessingQueue] Ollama error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Помечаем как FAILED для критических ошибок
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after error", updateError)
            }
        } catch (e: VacancyProcessingException) {
            log.error("❌ [VacancyProcessingQueue] Error processing vacancy ${vacancy.id}: ${e.message}", e)
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after processing error", updateError)
            }
        } catch (e: Exception) {
            log.error("❌ [VacancyProcessingQueue] Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.FAILED))
                metricsService.incrementVacanciesFailed()
            } catch (updateError: Exception) {
                log.error("❌ [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after unexpected error", updateError)
            }
        }
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
        processingVacancies.clear()
        log.info("🧹 [VacancyProcessingQueue] Queue cleared")
    }

    @PreDestroy
    fun shutdown() {
        log.info("🛑 [VacancyProcessingQueue] Shutting down queue...")
        isRunning.set(false)
        queueScope.cancel()
        queueChannel.close()
    }
}