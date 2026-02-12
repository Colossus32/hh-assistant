package com.hhassistant.service.vacancy

import com.hhassistant.aspect.Loggable
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.service.monitoring.CircuitBreakerStateService
import com.hhassistant.util.TraceContext
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory очередь для обработки вакансий (приоритетная очередь для анализа)
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
    private val circuitBreakerStateService: CircuitBreakerStateService,
    private val processedVacancyCacheService: ProcessedVacancyCacheService,
    private val vacancyProcessingControlService: VacancyProcessingControlService,
    @Autowired(required = false) private val ollamaMonitoringService:
    com.hhassistant.service.monitoring.OllamaMonitoringService?,
    @Value("\${app.vacancy-processing.queue.enabled:true}") private val queueEnabled: Boolean,
    @Value("\${app.vacancy-processing.queue.max-concurrent:3}") private val maxConcurrent: Int,
    @Value("\${app.vacancy-processing.queue.batch-size:10}") private val batchSize: Int,
    @Value(
        "\${app.vacancy-processing.queue.circuit-breaker-open-wait-timeout-seconds:120}",
    ) private val circuitBreakerOpenWaitTimeoutSeconds:
    Long,
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

    // Множество вакансий, уже добавленных в очередь (для предотвращения дубликатов)
    private val queuedVacancies = ConcurrentHashMap<String, Boolean>()

    // Флаг работы очереди
    private val isRunning = AtomicBoolean(false)

    // Scope для корутин
    private val queueScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error(" [VacancyProcessingQueue] Unhandled exception in queue coroutine: ${exception.message}", exception)
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
     * Загружается асинхронно, не блокируя старт приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun loadPendingVacanciesOnStartup() {
        if (!queueEnabled) {
            log.info("ℹ️ [VacancyProcessingQueue] Queue is disabled, skipping startup load")
            return
        }

        log.info(" [VacancyProcessingQueue] Loading pending QUEUED vacancies into queue on startup...")

        queueScope.launch {
            try {
                val queuedVacancies = vacancyRepository.findByStatus(VacancyStatus.QUEUED)
                if (queuedVacancies.isEmpty()) {
                    log.info("ℹ️ [VacancyProcessingQueue] No QUEUED vacancies found on startup")
                    return@launch
                }

                log.info(" [VacancyProcessingQueue] Found ${queuedVacancies.size} QUEUED vacancies on startup")

                // Добавляем в очередь
                for (vacancy in queuedVacancies) {
                    enqueue(vacancy.id, checkDuplicate = false) // При старте не проверяем дубликаты
                }

                log.info(" [VacancyProcessingQueue] Loaded ${queue.size} items into queue on startup")

                // Запускаем обработку очереди
                startQueueProcessing()
            } catch (e: Exception) {
                log.error(" [VacancyProcessingQueue] Error loading pending vacancies on startup: ${e.message}", e)
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
            log.warn(" [VacancyProcessingQueue] Vacancy $vacancyId not found in database, skipping")
            return false
        }

        // Проверяем на дубликаты
        if (checkDuplicate) {
            // Проверяем, не обрабатывается ли уже
            if (processingVacancies.containsKey(vacancyId)) {
                log.debug(" [VacancyProcessingQueue] Vacancy $vacancyId is already being processed, skipping")
                return false
            }

            // Проверяем, не добавлена ли уже в очередь (атомарная проверка и добавление)
            if (queuedVacancies.putIfAbsent(vacancyId, true) != null) {
                log.debug(" [VacancyProcessingQueue] Vacancy $vacancyId is already in queue, skipping")
                return false
            }

            // ВАЖНО: Проверяем, не была ли вакансия уже проанализирована (даже если статус QUEUED)
            // Это может произойти, если статус не обновился из-за ошибки или перезапуска приложения
            // Используем кэш для быстрой проверки
            if (processedVacancyCacheService.isProcessed(vacancyId)) {
                // Запускаем асинхронное обновление статуса, не блокируя поток
                queueScope.launch {
                    updateStatusIfAnalysisExists(vacancyId, vacancy, checkDuplicate = true)
                }
                queuedVacancies.remove(vacancyId)
                return false
            }

            // Пропускаем уже обработанные вакансии
            if (vacancy.status in listOf(
                    VacancyStatus.ANALYZED,
                    VacancyStatus.SENT_TO_USER,
                    VacancyStatus.SKIPPED,
                    VacancyStatus.NOT_SUITABLE,
                    VacancyStatus.IN_ARCHIVE,
                    VacancyStatus.NOT_INTERESTED,
                    VacancyStatus.REJECTED_BY_VALIDATOR,
                )
            ) {
                log.debug(
                    " [VacancyProcessingQueue] Vacancy $vacancyId already processed (status: ${vacancy.status}), skipping",
                )
                // Удаляем из queuedVacancies, так как мы не будем добавлять в очередь
                queuedVacancies.remove(vacancyId)
                return false
            }

            // Если статус не QUEUED, обновляем его
            if (vacancy.status != VacancyStatus.QUEUED) {
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.QUEUED))
                } catch (e: Exception) {
                    log.warn(" [VacancyProcessingQueue] Failed to update status for vacancy $vacancyId: ${e.message}")
                    // Удаляем из queuedVacancies при ошибке
                    queuedVacancies.remove(vacancyId)
                    return false
                }
            }
        } else {
            // Даже если checkDuplicate = false, проверяем queuedVacancies для предотвращения дубликатов
            if (queuedVacancies.putIfAbsent(vacancyId, true) != null) {
                log.debug(" [VacancyProcessingQueue] Vacancy $vacancyId is already in queue (checkDuplicate=false but duplicate detected), skipping")
                return false
            }

            // ВАЖНО: Даже при checkDuplicate=false проверяем, не была ли вакансия уже проанализирована
            // Это предотвращает повторную обработку при перезапуске приложения
            // Используем кэш для быстрой проверки
            if (processedVacancyCacheService.isProcessed(vacancyId)) {
                // Запускаем асинхронное обновление статуса, не блокируя поток
                queueScope.launch {
                    updateStatusIfAnalysisExists(vacancyId, vacancy, checkDuplicate = false)
                }
                queuedVacancies.remove(vacancyId)
                return false
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

        log.info(" [VacancyProcessingQueue] Enqueued vacancy $vacancyId, queue size: ${queue.size}")

        // Запускаем обработку, если еще не запущена
        if (!isRunning.get()) {
            startQueueProcessing()
        }

        return true
    }

    /**
     * Добавляет несколько вакансий в очередь
     * Использует батчевую проверку обработанных вакансий и параллельную обработку
     */
    @Loggable
    suspend fun enqueueBatch(vacancyIds: List<String>): Int {
        if (vacancyIds.isEmpty()) {
            return 0
        }

        if (!queueEnabled) {
            log.debug("ℹ️ [VacancyProcessingQueue] Queue is disabled, skipping batch enqueue")
            return 0
        }

        // Батчевая проверка обработанных вакансий
        val processedIds = processedVacancyCacheService.areProcessed(vacancyIds)
        val unprocessedIds = vacancyIds.filter { it !in processedIds }

        if (unprocessedIds.isEmpty()) {
            log.debug(" [VacancyProcessingQueue] All ${vacancyIds.size} vacancies already processed, skipping")
            return 0
        }

        log.debug(
            " [VacancyProcessingQueue] Batch enqueue: ${processedIds.size} already processed, " +
                "${unprocessedIds.size} to process",
        )

        // Параллельная обработка оставшихся вакансий
        return supervisorScope {
            val results = unprocessedIds.map { vacancyId ->
                async(Dispatchers.Default) {
                    try {
                        if (enqueue(vacancyId, checkDuplicate = true)) {
                            1
                        } else {
                            0
                        }
                    } catch (e: Exception) {
                        log.error(
                            " [VacancyProcessingQueue] Error enqueueing vacancy $vacancyId in batch: ${e.message}",
                            e,
                        )
                        0
                    }
                }
            }.awaitAll()

            results.sum()
        }
    }

    /**
     * Асинхронно обновляет статус вакансии, если анализ уже существует в БД.
     * Вызывается когда кэш указывает, что вакансия уже обработана.
     * @param vacancyId ID вакансии
     * @param vacancy Вакансия из БД
     * @param checkDuplicate Флаг, влияющий на выбор статуса при нерелевантной вакансии
     */
    private suspend fun updateStatusIfAnalysisExists(
        vacancyId: String,
        vacancy: Vacancy,
        checkDuplicate: Boolean,
    ) {
        try {
            log.debug("📊 [VacancyProcessingQueue] Cache hit for vacancy $vacancyId, fetching analysis from DB for status update")
            val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancyId)

            if (existingAnalysis != null) {
                log.warn(
                    "⚠️ [VacancyProcessingQueue] Vacancy $vacancyId already has analysis (analyzed at ${existingAnalysis.analyzedAt}), " +
                        "but status is ${vacancy.status}. Updating status and skipping.",
                )

                // Обновляем статус на основе существующего анализа
                val correctStatus = if (existingAnalysis.isRelevant) {
                    VacancyStatus.ANALYZED
                } else {
                    // Разница в статусах для checkDuplicate = true/false
                    if (checkDuplicate) {
                        VacancyStatus.NOT_SUITABLE
                    } else {
                        VacancyStatus.SKIPPED
                    }
                }

                if (vacancy.status != correctStatus) {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(correctStatus))
                    log.info(" [VacancyProcessingQueue] Updated vacancy $vacancyId status from ${vacancy.status} to $correctStatus")
                }
            } else {
                // Кэш говорит, что обработана, но анализа нет
                // Не удаляем из кэша - кэш пересобирается раз в день в полночь
                log.warn(
                    "⚠️ [VacancyProcessingQueue] Vacancy $vacancyId marked as processed in cache, but analysis not found. " +
                        "Cache will be rebuilt at midnight.",
                )
            }
        } catch (e: Exception) {
            log.error(" [VacancyProcessingQueue] Failed to update status for vacancy $vacancyId: ${e.message}", e)
        }
    }

    /**
     * Запускает обработку очереди
     */
    private fun startQueueProcessing() {
        if (isRunning.getAndSet(true)) {
            log.debug("ℹ️ [VacancyProcessingQueue] Queue processing already running")
            return
        }

        log.info(" [VacancyProcessingQueue] Starting queue processing...")

        queueScope.launch {
            try {
                for (item in queueChannel) {
                    launch {
                        processQueueItem(item)
                    }
                }
            } catch (e: Exception) {
                log.error(" [VacancyProcessingQueue] Error in queue processing: ${e.message}", e)
                isRunning.set(false)
            }
        }
    }

    /**
     * Обрабатывает элемент очереди
     */
    private suspend fun processQueueItem(item: QueueItem) {
        processingSemaphore.withPermit {
            // Устанавливаем trace ID для трассировки вакансии через все логи
            TraceContext.withTraceIdSuspend(
                traceId = TraceContext.generateTraceId(item.vacancyId),
                vacancyId = item.vacancyId,
            ) {
                try {
                    log.info(" [VacancyProcessingQueue] Processing vacancy ${item.vacancyId}")

                    // Получаем вакансию из БД
                    val vacancy = vacancyRepository.findById(item.vacancyId).orElse(null)
                    if (vacancy == null) {
                        log.warn(" [VacancyProcessingQueue] Vacancy ${item.vacancyId} not found, skipping")
                        processingVacancies.remove(item.vacancyId)
                        queuedVacancies.remove(item.vacancyId)
                        return@withTraceIdSuspend
                    }

                    // Проверяем, не была ли уже обработана
                    if (vacancy.status !in listOf(VacancyStatus.QUEUED, VacancyStatus.NEW)) {
                        log.debug(
                            "ℹ️ [VacancyProcessingQueue] Vacancy ${item.vacancyId} already processed (status: ${vacancy.status}), skipping",
                        )
                        processingVacancies.remove(item.vacancyId)
                        queuedVacancies.remove(item.vacancyId)
                        return@withTraceIdSuspend
                    }

                    // Дополнительная проверка: если анализ уже существует, но статус не обновлен
                    // Используем кэш для быстрой проверки
                    if (processedVacancyCacheService.isProcessed(item.vacancyId)) {
                        // Кэш-хит, получаем анализ для обновления статуса (запрос к БД)
                        log.debug("📊 [VacancyProcessingQueue] Cache hit for vacancy ${item.vacancyId}, fetching analysis from DB for status update")
                        val existingAnalysis = vacancyAnalysisService.findByVacancyId(item.vacancyId)
                        if (existingAnalysis != null) {
                            log.warn(
                                "⚠️ [VacancyProcessingQueue] Vacancy ${item.vacancyId} already has analysis (analyzed at ${existingAnalysis.analyzedAt}), " +
                                    "but status is ${vacancy.status}. Updating status and skipping processing.",
                            )
                            // Обновляем статус на основе существующего анализа
                            val correctStatus = if (existingAnalysis.isRelevant) {
                                VacancyStatus.ANALYZED
                            } else {
                                VacancyStatus.NOT_SUITABLE
                            }
                            try {
                                if (vacancy.status != correctStatus) {
                                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(correctStatus))
                                    log.info(" [VacancyProcessingQueue] Updated vacancy ${item.vacancyId} status from ${vacancy.status} to $correctStatus")
                                }
                            } catch (e: Exception) {
                                log.error(" [VacancyProcessingQueue] Failed to update status for vacancy ${item.vacancyId}: ${e.message}", e)
                            }
                        } else {
                            // Кэш говорит, что обработана, но анализа нет - возможно кэш устарел, удаляем из кэша
                            log.warn(
                                "⚠️ [VacancyProcessingQueue] Vacancy ${item.vacancyId} marked as processed in cache, but analysis not found. Removing from cache.",
                            )
                            processedVacancyCacheService.removeFromCache(item.vacancyId)
                        }
                        processingVacancies.remove(item.vacancyId)
                        queuedVacancies.remove(item.vacancyId)
                        queue.remove(item)
                        metricsService.setQueueSize(queue.size)
                        return@withTraceIdSuspend
                    }

                    // Проверяем, не приостановлена ли обработка
                    if (vacancyProcessingControlService.isProcessingPaused()) {
                        log.info(
                            "⏸️ [VacancyProcessingQueue] Processing is paused, marking vacancy ${item.vacancyId} as SKIPPED for retry later",
                        )
                        try {
                            vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                        } catch (updateError: Exception) {
                            log.error(
                                " [VacancyProcessingQueue] Failed to update status for vacancy ${item.vacancyId} after pause",
                                updateError,
                            )
                        }
                        processingVacancies.remove(item.vacancyId)
                        queuedVacancies.remove(item.vacancyId)
                        queue.remove(item)
                        metricsService.setQueueSize(queue.size)
                        return@withTraceIdSuspend
                    }

                    // Проверяем состояние Circuit Breaker перед обработкой
                    val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()
                    if (circuitBreakerState == "OPEN") {
                        // Если Circuit Breaker OPEN, проверяем активные запросы
                        var activeRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
                        if (activeRequests > 0) {
                            // Есть активные запросы - ждем их завершения с таймаутом
                            log.info(
                                " [VacancyProcessingQueue] Circuit Breaker is OPEN, " +
                                    "but there are $activeRequests active requests. " +
                                    "Waiting for completion (timeout: ${circuitBreakerOpenWaitTimeoutSeconds}s)...",
                            )
                            val waitStartTime = System.currentTimeMillis()
                            val timeoutMillis = circuitBreakerOpenWaitTimeoutSeconds * 1000L
                            while (activeRequests > 0 && (System.currentTimeMillis() - waitStartTime) < timeoutMillis) {
                                delay(1000) // Проверяем каждую секунду
                                val currentActiveRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
                                if (currentActiveRequests == 0) {
                                    log.info(
                                        " [VacancyProcessingQueue] All active requests completed, proceeding with vacancy ${item.vacancyId}",
                                    )
                                    break
                                }
                                if (currentActiveRequests != activeRequests) {
                                    log.debug(
                                        " [VacancyProcessingQueue] Active requests changed: $activeRequests -> $currentActiveRequests",
                                    )
                                    activeRequests = currentActiveRequests
                                }
                            }
                            val waitDuration = System.currentTimeMillis() - waitStartTime
                            val finalActiveRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
                            if (finalActiveRequests > 0) {
                                log.warn(
                                    " [VacancyProcessingQueue] Timeout waiting for active requests to complete " +
                                        "(waited ${waitDuration}ms, still $finalActiveRequests active). " +
                                        "Marking vacancy ${item.vacancyId} as SKIPPED",
                                )
                                try {
                                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                                } catch (updateError: Exception) {
                                    log.error(
                                        " [VacancyProcessingQueue] Failed to update status for vacancy ${item.vacancyId} after timeout",
                                        updateError,
                                    )
                                }
                                processingVacancies.remove(item.vacancyId)
                                queuedVacancies.remove(item.vacancyId)
                                queue.remove(item)
                                metricsService.setQueueSize(queue.size)
                                return@withTraceIdSuspend
                            } else {
                                log.info(
                                    " [VacancyProcessingQueue] All active requests completed after ${waitDuration}ms, proceeding with vacancy ${item.vacancyId}",
                                )
                            }
                        } else {
                            // Нет активных запросов - сразу помечаем как SKIPPED
                            log.warn(
                                " [VacancyProcessingQueue] Circuit Breaker is OPEN and no active requests, marking vacancy ${item.vacancyId} as SKIPPED",
                            )
                            try {
                                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                            } catch (updateError: Exception) {
                                log.error(
                                    " [VacancyProcessingQueue] Failed to update status for vacancy ${item.vacancyId}",
                                    updateError,
                                )
                            }
                            processingVacancies.remove(item.vacancyId)
                            queuedVacancies.remove(item.vacancyId)
                            queue.remove(item)
                            metricsService.setQueueSize(queue.size)
                            return@withTraceIdSuspend
                        }
                    }

                    // Обрабатываем вакансию: извлечение навыков → анализ → генерация письма → отправка в Telegram
                    processVacancy(vacancy)

                    // Удаляем из множества обрабатываемых и из очереди
                    processingVacancies.remove(item.vacancyId)
                    queuedVacancies.remove(item.vacancyId)
                    queue.remove(item)

                    // Обновляем метрику размера очереди
                    metricsService.setQueueSize(queue.size)
                } catch (e: Exception) {
                    log.error(" [VacancyProcessingQueue] Error processing queue item ${item.vacancyId}: ${e.message}", e)
                    processingVacancies.remove(item.vacancyId)
                    queuedVacancies.remove(item.vacancyId)
                    queue.remove(item)
                    metricsService.setQueueSize(queue.size)
                }
            }
        }
    }

    /**
     * Обрабатывает вакансию: анализ на соответствие резюме → если подходит, отправка в Telegram → добавление в очередь навыков
     */
    private suspend fun processVacancy(vacancy: Vacancy) {
        log.info(" [VacancyProcessingQueue] Starting analysis pipeline for vacancy ${vacancy.id}")

        // Проверяем, не приостановлена ли обработка перед отправкой в LLM
        if (vacancyProcessingControlService.isProcessingPaused()) {
            log.info(
                "⏸️ [VacancyProcessingQueue] Processing is paused, skipping LLM analysis for vacancy ${vacancy.id}",
            )
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
            } catch (updateError: Exception) {
                log.error(
                    " [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after pause",
                    updateError,
                )
            }
            return
        }

        try {
            // Шаг 1: Анализ через Ollama на соответствие резюме
            // Внутри analyzeVacancy сначала проверяется URL (IN_ARCHIVE при 404),
            // затем валидация контента (удаление при бан-словах)
            log.debug("🤖 [VacancyProcessingQueue] Analyzing vacancy ${vacancy.id} via Ollama")
            val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

            // Если анализ вернул null - вакансия была:
            // 1. Помечена как IN_ARCHIVE (404 на HH.ru)
            // 2. Отклонена валидатором и помечена как REJECTED_BY_VALIDATOR (бан-слова)
            if (analysis == null) {
                log.info(
                    " [VacancyProcessingQueue] Vacancy ${vacancy.id} was rejected (IN_ARCHIVE or REJECTED_BY_VALIDATOR)",
                )
                return
            }

            // Шаг 2: Обновляем статус вакансии
            val newStatus = if (analysis.isRelevant) {
                VacancyStatus.ANALYZED
            } else {
                VacancyStatus.NOT_SUITABLE
            }
            vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
            log.debug("📝 [VacancyProcessingQueue] Updated vacancy ${vacancy.id} status to: $newStatus")

            // Шаг 3: Если вакансия релевантна (relevance_score >= minRelevanceScore) - отправляем в Telegram
            // Навыки уже сохранены в БД при анализе, если вакансия релевантна
            if (analysis.isRelevant) {
                log.info(
                    " [VacancyProcessingQueue] Vacancy ${vacancy.id} is relevant (score: ${String.format(
                        "%.2f",
                        analysis.relevanceScore * 100,
                    )}%)",
                )

                // Отправляем в Telegram
                try {
                    val sentSuccessfully = vacancyNotificationService.sendVacancyToTelegram(vacancy, analysis)
                    if (sentSuccessfully) {
                        val sentAt = java.time.LocalDateTime.now()
                        vacancyStatusService.updateVacancyStatus(vacancy.withSentToTelegramAt(sentAt))
                        log.info("📱 [VacancyProcessingQueue] Successfully sent vacancy ${vacancy.id} to Telegram")
                    } else {
                        log.warn(
                            "⚠️ [VacancyProcessingQueue] Vacancy ${vacancy.id} was not sent to Telegram (Telegram may be disabled or not configured)",
                        )
                    }
                } catch (e: Exception) {
                    log.error(
                        " [VacancyProcessingQueue] Failed to send vacancy ${vacancy.id} to Telegram: ${e.message}",
                        e,
                    )
                    // Продолжаем обработку даже если не удалось отправить
                }
            } else {
                val reason = when {
                    analysis.relevanceScore == 0.0 && analysis.reasoning.contains("отклонена") ->
                        "rejected by exclusion rules: ${analysis.reasoning}"
                    analysis.relevanceScore == 0.0 ->
                        "relevance score is 0%"
                    else ->
                        "relevance score ${String.format("%.2f", analysis.relevanceScore * 100)}% is below threshold"
                }
                log.info(
                    "ℹ️ [VacancyProcessingQueue] Vacancy ${vacancy.id} ('${vacancy.name}') is not relevant ($reason), skipping Telegram",
                )
            }

            log.info(
                " [VacancyProcessingQueue] Completed processing pipeline for vacancy ${vacancy.id} (isRelevant: ${analysis.isRelevant})",
            )
        } catch (e: OllamaException) {
            log.error(" [VacancyProcessingQueue] Ollama error processing vacancy ${vacancy.id}: ${e.message}", e)
            // Проверяем, является ли это ошибкой Circuit Breaker OPEN
            val isCircuitBreakerOpen = e.message?.contains("Circuit Breaker is OPEN") == true
            // Проверяем, является ли это ошибкой rate limit
            val isRateLimit = e.message?.contains("Rate limit exceeded") == true ||
                e.message?.contains("marked as SKIPPED for retry later") == true
            val circuitBreakerState = circuitBreakerStateService.getCircuitBreakerState()

            if (isCircuitBreakerOpen || circuitBreakerState == "OPEN") {
                // Если Circuit Breaker OPEN, помечаем как SKIPPED для повторной обработки позже
                log.warn(
                    " [VacancyProcessingQueue] Circuit Breaker is OPEN, marking vacancy ${vacancy.id} as SKIPPED for retry later",
                )
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                } catch (updateError: Exception) {
                    log.error(
                        " [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after Circuit Breaker error",
                        updateError,
                    )
                }
            } else if (isRateLimit) {
                // Rate limit - уже помечено как SKIPPED в VacancyAnalysisService, просто логируем
                log.info(
                    " [VacancyProcessingQueue] Rate limit error for vacancy ${vacancy.id}, " +
                        "already marked as SKIPPED for retry later",
                )
            } else {
                // Для других ошибок Ollama помечаем как SKIPPED для повторной обработки
                try {
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                    metricsService.incrementVacanciesSkipped()
                } catch (updateError: Exception) {
                    log.error(
                        " [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after error",
                        updateError,
                    )
                }
            }
        } catch (e: VacancyProcessingException) {
            log.error(" [VacancyProcessingQueue] Error processing vacancy ${vacancy.id}: ${e.message}", e)
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                metricsService.incrementVacanciesSkipped()
            } catch (updateError: Exception) {
                log.error(
                    " [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after processing error",
                    updateError,
                )
            }
        } catch (e: Exception) {
            log.error(" [VacancyProcessingQueue] Unexpected error processing vacancy ${vacancy.id}: ${e.message}", e)
            try {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                metricsService.incrementVacanciesSkipped()
            } catch (updateError: Exception) {
                log.error(
                    " [VacancyProcessingQueue] Failed to update status for vacancy ${vacancy.id} after unexpected error",
                    updateError,
                )
            }
        }
    }

    /**
     * Получает размер очереди
     */
    fun getQueueSize(): Int = queue.size

    /**
     * Получает информацию о вакансиях в очереди на обработку
     * @return Список вакансий с их названиями и ссылками
     */
    fun getQueueItems(): List<Map<String, Any>> {
        val items = mutableListOf<Map<String, Any>>()

        // Получаем все элементы из очереди
        val queueSnapshot = queue.toList()

        for (item in queueSnapshot) {
            try {
                val vacancy = vacancyRepository.findById(item.vacancyId).orElse(null)
                if (vacancy != null) {
                    items.add(
                        mapOf(
                            "id" to vacancy.id,
                            "name" to vacancy.name,
                            "employer" to vacancy.employer,
                            "url" to vacancy.url,
                            "status" to vacancy.status.name,
                            "addedAt" to item.addedAt.toString(),
                            "publishedAt" to (item.publishedAt?.toString() ?: "Не указано"),
                        ),
                    )
                } else {
                    // Вакансия не найдена в БД, но есть в очереди
                    items.add(
                        mapOf(
                            "id" to item.vacancyId,
                            "name" to "Вакансия не найдена в БД",
                            "employer" to "N/A",
                            "url" to "N/A",
                            "status" to "NOT_FOUND",
                            "addedAt" to item.addedAt.toString(),
                            "publishedAt" to (item.publishedAt?.toString() ?: "Не указано"),
                        ),
                    )
                }
            } catch (e: Exception) {
                log.warn(" [VacancyProcessingQueue] Error getting info for queue item ${item.vacancyId}: ${e.message}")
            }
        }

        return items
    }

    /**
     * Проверяет, пуста ли очередь обработки новых вакансий.
     * Очередь считается пустой, если в ней нет элементов и нет обрабатываемых вакансий.
     *
     * @return true если очередь пуста, false если есть вакансии в очереди или обрабатываются
     */
    fun isQueueEmpty(): Boolean {
        return queue.isEmpty() && processingVacancies.isEmpty() && queuedVacancies.isEmpty()
    }

    /**
     * Очищает очередь (для тестирования)
     */
    fun clearQueue() {
        queue.clear()
        processingVacancies.clear()
        queuedVacancies.clear()
        log.info(" [VacancyProcessingQueue] Queue cleared")
    }

    /**
     * Периодически очищает старые записи из queuedVacancies для предотвращения утечки памяти.
     * Удаляет записи, если размер кэша превышает лимит (5000 записей).
     * Это предотвращает неограниченный рост памяти при большом количестве пропущенных вакансий.
     */
    @Scheduled(fixedDelay = 3600000) // Каждый час
    fun cleanupQueuedVacanciesCache() {
        val maxSize = 5000 // Максимальный размер кэша
        if (queuedVacancies.size > maxSize) {
            val beforeSize = queuedVacancies.size
            // Очищаем половину старых записей (FIFO - удаляем первые добавленные)
            val keysToRemove = queuedVacancies.keys.take(queuedVacancies.size / 2)
            keysToRemove.forEach { queuedVacancies.remove(it) }
            val afterSize = queuedVacancies.size
            log.info(
                "[VacancyProcessingQueue] Cleaned up queuedVacancies cache: " +
                    "$beforeSize -> $afterSize entries (removed ${beforeSize - afterSize})",
            )
        }
    }

    @PreDestroy
    fun shutdown() {
        log.info(" [VacancyProcessingQueue] Shutting down queue...")
        isRunning.set(false)

        // Проверяем, есть ли активные запросы к LLM
        val activeRequests = ollamaMonitoringService?.getActiveRequestsCount() ?: 0
        if (activeRequests > 0) {
            log.warn(
                "[VacancyProcessingQueue] Shutting down with $activeRequests active LLM requests. " +
                    "Marking processing vacancies as SKIPPED for recovery on next startup",
            )

            // Помечаем все вакансии в процессе обработки как SKIPPED
            markProcessingVacanciesAsSkipped()
        } else {
            log.info("[VacancyProcessingQueue] No active LLM requests, safe shutdown")
        }

        queueScope.cancel()
        queueChannel.close()
    }

    /**
     * Помечает все вакансии в процессе обработки как SKIPPED
     * Вызывается при закрытии приложения, если есть активные запросы к LLM
     * Все операции синхронные, runBlocking не требуется
     */
    private fun markProcessingVacanciesAsSkipped() {
        try {
            // Получаем все вакансии, которые сейчас обрабатываются или в очереди
            val processingVacancyIds = processingVacancies.keys.toList()
            val queuedVacancyIds = queue.map { it.vacancyId }
            val trackedQueuedIds = queuedVacancies.keys.toList()

            // Объединяем списки и убираем дубликаты
            val allVacancyIds = (processingVacancyIds + queuedVacancyIds + trackedQueuedIds).distinct()

            if (allVacancyIds.isEmpty()) {
                log.info("[VacancyProcessingQueue] No vacancies to mark as SKIPPED")
                return
            }

            log.info(
                "[VacancyProcessingQueue] Marking ${allVacancyIds.size} vacancies as SKIPPED " +
                    "(processing: ${processingVacancyIds.size}, queued: ${queuedVacancyIds.size})",
            )

            var markedCount = 0
            var errorCount = 0

            // Помечаем каждую вакансию как SKIPPED
            for (vacancyId in allVacancyIds) {
                try {
                    val vacancy = vacancyRepository.findById(vacancyId).orElse(null)
                    if (vacancy == null) {
                        log.debug("[VacancyProcessingQueue] Vacancy $vacancyId not found, skipping")
                        continue
                    }

                    // Помечаем только NEW и QUEUED вакансии как SKIPPED
                    if (vacancy.status in listOf(VacancyStatus.NEW, VacancyStatus.QUEUED)) {
                        vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
                        markedCount++
                        log.debug(
                            "[VacancyProcessingQueue] Marked vacancy $vacancyId as SKIPPED " +
                                "(was: ${vacancy.status})",
                        )
                    } else {
                        log.debug(
                            "[VacancyProcessingQueue] Vacancy $vacancyId already has status ${vacancy.status}, " +
                                "not marking as SKIPPED",
                        )
                    }
                } catch (e: Exception) {
                    errorCount++
                    log.error(
                        "[VacancyProcessingQueue] Failed to mark vacancy $vacancyId as SKIPPED: ${e.message}",
                        e,
                    )
                }
            }

            log.info(
                "[VacancyProcessingQueue] Shutdown complete: marked $markedCount vacancies as SKIPPED, " +
                    "$errorCount errors",
            )
        } catch (e: Exception) {
            log.error(
                "[VacancyProcessingQueue] Error marking vacancies as SKIPPED during shutdown: ${e.message}",
                e,
            )
        }
    }
}
