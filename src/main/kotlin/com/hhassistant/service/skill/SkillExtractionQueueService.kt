package com.hhassistant.service.skill

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.exception.HHAPIException
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory очередь для извлечения навыков из вакансий (низкий приоритет)
 *
 * Логика работы:
 * 1. Релевантные вакансии после анализа попадают в очередь для извлечения навыков
 * 2. Очередь обрабатывается с ограничением параллелизма на корутинах
 * 3. Обработка: получение key_skills из API → извлечение навыков через LLM (если нужно) → сохранение
 * 4. По расписанию необработанные релевантные вакансии из БД добавляются в очередь
 */
@Service
class SkillExtractionQueueService(
    private val vacancyRepository: com.hhassistant.repository.VacancyRepository,
    private val skillExtractionService: SkillExtractionService,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    private val hhVacancyClient: com.hhassistant.client.hh.HHVacancyClient,
    @Value("\${app.skill-extraction.queue.enabled:true}") private val queueEnabled: Boolean,
    @Value("\${app.skill-extraction.queue.max-concurrent:2}") private val maxConcurrent: Int,
) {
    private val log = KotlinLogging.logger {}

    // In-memory очередь для обработки извлечения навыков
    private val queue = ConcurrentLinkedQueue<QueueItem>()

    // Канал для обработки очереди (для корутин)
    private val queueChannel = Channel<QueueItem>(Channel.UNLIMITED)

    // Множество обрабатываемых вакансий (для проверки дубликатов)
    private val processingVacancies = ConcurrentHashMap<String, Boolean>()

    // Флаг работы очереди
    private val isRunning = AtomicBoolean(false)

    // Scope для корутин
    private val queueScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error("❌ [SkillExtractionQueue] Unhandled exception in queue coroutine: ${exception.message}", exception)
        },
    )

    // Семафор для ограничения параллелизма
    private val processingSemaphore = Semaphore(maxConcurrent)

    /**
     * Элемент очереди
     */
    data class QueueItem(
        val vacancyId: String,
        val addedAt: LocalDateTime = LocalDateTime.now(),
    )

    /**
     * Загружает ожидающие вакансии в очередь при старте приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun loadPendingVacanciesOnStartup() {
        if (!queueEnabled) {
            log.info("ℹ️ [SkillExtractionQueue] Queue is disabled, skipping startup load")
            return
        }

        log.info("🔄 [SkillExtractionQueue] Loading pending relevant vacancies without skills into queue on startup...")

        runBlocking {
            try {
                // Находим релевантные вакансии без навыков
                val relevantVacanciesWithoutSkills = vacancyRepository.findRelevantVacanciesWithoutSkills()
                if (relevantVacanciesWithoutSkills.isEmpty()) {
                    log.info("ℹ️ [SkillExtractionQueue] No relevant vacancies without skills found on startup")
                    return@runBlocking
                }

                log.info(
                    "📋 [SkillExtractionQueue] Found ${relevantVacanciesWithoutSkills.size} relevant vacancies without skills on startup",
                )

                // Добавляем в очередь
                for (vacancy in relevantVacanciesWithoutSkills) {
                    enqueue(vacancy.id, checkDuplicate = false) // При старте не проверяем дубликаты
                }

                log.info("✅ [SkillExtractionQueue] Loaded ${queue.size} items into queue on startup")

                // Запускаем обработку очереди
                startQueueProcessing()
            } catch (e: Exception) {
                log.error("❌ [SkillExtractionQueue] Error loading pending vacancies on startup: ${e.message}", e)
            }
        }
    }

    /**
     * Добавляет вакансию в очередь извлечения навыков
     *
     * @param vacancyId ID вакансии
     * @param checkDuplicate Проверять ли на дубликаты (по умолчанию true)
     * @return true если вакансия добавлена, false если уже обрабатывается
     */
    fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
        if (!queueEnabled) {
            log.debug("ℹ️ [SkillExtractionQueue] Queue is disabled, skipping enqueue")
            return false
        }

        // Проверяем на дубликаты
        if (checkDuplicate) {
            // Проверяем, не обрабатывается ли уже
            if (processingVacancies.containsKey(vacancyId)) {
                log.debug("⏭️ [SkillExtractionQueue] Vacancy $vacancyId is already being processed, skipping")
                return false
            }

            // Проверяем в БД, не были ли уже извлечены навыки
            val vacancy = vacancyRepository.findById(vacancyId).orElse(null)
            if (vacancy == null) {
                log.warn("⚠️ [SkillExtractionQueue] Vacancy $vacancyId not found in database, skipping")
                return false
            }

            // Пропускаем если навыки уже извлечены
            if (vacancy.hasSkillsExtracted()) {
                log.debug("⏭️ [SkillExtractionQueue] Vacancy $vacancyId already has skills extracted, skipping")
                return false
            }
        }

        // Добавляем в очередь
        val item = QueueItem(vacancyId)
        queue.offer(item)
        processingVacancies[vacancyId] = true

        queueScope.launch {
            queueChannel.send(item)
        }

        log.info(
            "📥 [SkillExtractionQueue] Enqueued vacancy $vacancyId for skill extraction, queue size: ${queue.size}",
        )

        // Запускаем обработку, если еще не запущена
        if (!isRunning.get()) {
            startQueueProcessing()
        }

        return true
    }

    /**
     * Добавляет несколько вакансий в очередь
     */
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
            log.debug("ℹ️ [SkillExtractionQueue] Queue processing already running")
            return
        }

        log.info("🚀 [SkillExtractionQueue] Starting queue processing...")

        queueScope.launch {
            try {
                for (item in queueChannel) {
                    launch {
                        processQueueItem(item)
                    }
                }
            } catch (e: Exception) {
                log.error("❌ [SkillExtractionQueue] Error in queue processing: ${e.message}", e)
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
                log.info("🔄 [SkillExtractionQueue] Processing skill extraction for vacancy ${item.vacancyId}")

                // Получаем вакансию из БД
                val vacancy = vacancyRepository.findById(item.vacancyId).orElse(null)
                if (vacancy == null) {
                    log.warn("⚠️ [SkillExtractionQueue] Vacancy ${item.vacancyId} not found, skipping")
                    processingVacancies.remove(item.vacancyId)
                    return@withPermit
                }

                // Проверяем, не были ли уже извлечены навыки
                if (vacancy.hasSkillsExtracted()) {
                    log.debug(
                        "ℹ️ [SkillExtractionQueue] Vacancy ${item.vacancyId} already has skills extracted, skipping",
                    )
                    processingVacancies.remove(item.vacancyId)
                    return@withPermit
                }

                // Извлекаем навыки
                extractSkillsForVacancy(vacancy)

                // Удаляем из множества обрабатываемых
                processingVacancies.remove(item.vacancyId)
                queue.remove(item)
            } catch (e: Exception) {
                log.error("❌ [SkillExtractionQueue] Error processing queue item ${item.vacancyId}: ${e.message}", e)
                processingVacancies.remove(item.vacancyId)
                queue.remove(item)
            }
        }
    }

    /**
     * Извлекает навыки для вакансии
     */
    private suspend fun extractSkillsForVacancy(vacancy: Vacancy) {
        log.info("🔍 [SkillExtractionQueue] Extracting skills for vacancy ${vacancy.id}")

        try {
            // Получаем key_skills из API (если доступны)
            val keySkills = try {
                val vacancyDto = hhVacancyClient.getVacancyDetails(vacancy.id)
                vacancyDto.keySkills
            } catch (e: HHAPIException.NotFoundException) {
                // Вакансия не найдена на HH.ru - удаляем из БД
                log.warn("🗑️ [SkillExtractionQueue] Vacancy ${vacancy.id} not found on HH.ru (404), skipping")
                return
            } catch (e: HHAPIException.RateLimitException) {
                log.warn("⏸️ [SkillExtractionQueue] Rate limit exceeded while checking vacancy ${vacancy.id}, skipping")
                return
            } catch (e: Exception) {
                log.debug(
                    "⚠️ [SkillExtractionQueue] Could not fetch key_skills from API for vacancy ${vacancy.id}: ${e.message}",
                )
                null
            }

            // Извлекаем и сохраняем навыки
            skillExtractionService.extractAndSaveSkills(vacancy, keySkills)
            log.info("✅ [SkillExtractionQueue] Successfully extracted skills for vacancy ${vacancy.id}")
        } catch (e: Exception) {
            log.error("❌ [SkillExtractionQueue] Failed to extract skills for vacancy ${vacancy.id}: ${e.message}", e)
            // Не прерываем обработку других вакансий из-за ошибки одной
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
        log.info("🧹 [SkillExtractionQueue] Queue cleared")
    }

    @PreDestroy
    fun shutdown() {
        log.info("🛑 [SkillExtractionQueue] Shutting down queue...")
        isRunning.set(false)
        queueScope.cancel()
        queueChannel.close()
    }
}
