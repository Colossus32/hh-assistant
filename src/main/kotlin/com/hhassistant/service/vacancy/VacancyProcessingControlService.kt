package com.hhassistant.service.vacancy

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сервис для управления паузой/возобновлением обработки вакансий.
 * Позволяет временно останавливать отправку вакансий на обработку в LLM,
 * чтобы освободить ресурсы компьютера.
 */
@Service
class VacancyProcessingControlService {
    private val log = KotlinLogging.logger {}

    // Флаг паузы обработки вакансий
    private val isPaused = AtomicBoolean(false)

    // Время последнего изменения состояния
    private var pausedAt: LocalDateTime? = null
    private var resumedAt: LocalDateTime? = null

    /**
     * Проверяет, находится ли обработка вакансий на паузе
     * @return true если обработка приостановлена, false если активна
     */
    fun isProcessingPaused(): Boolean {
        return isPaused.get()
    }

    /**
     * Приостанавливает обработку вакансий (запросы в LLM не будут отправляться)
     * @return true если пауза успешно установлена, false если уже была на паузе
     */
    fun pauseProcessing(): Boolean {
        val wasPaused = isPaused.getAndSet(true)
        if (!wasPaused) {
            pausedAt = LocalDateTime.now()
            log.info("⏸️ [VacancyProcessingControl] Processing paused at $pausedAt")
        }
        return !wasPaused
    }

    /**
     * Возобновляет обработку вакансий
     * @return true если обработка успешно возобновлена, false если уже была активна
     */
    fun resumeProcessing(): Boolean {
        val wasPaused = isPaused.getAndSet(false)
        if (wasPaused) {
            resumedAt = LocalDateTime.now()
            log.info("▶️ [VacancyProcessingControl] Processing resumed at $resumedAt")
        }
        return wasPaused
    }

    /**
     * Получает статус обработки вакансий
     * @return Map с информацией о текущем состоянии
     */
    fun getStatus(): Map<String, Any> {
        val isPausedNow = isPaused.get()
        return mapOf(
            "isPaused" to isPausedNow,
            "pausedAt" to (pausedAt?.toString() ?: "N/A"),
            "resumedAt" to (resumedAt?.toString() ?: "N/A"),
        )
    }
}
