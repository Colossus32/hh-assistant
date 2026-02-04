package com.hhassistant.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сервис для управления метриками приложения
 * Централизованное место для всех кастомных метрик
 */
@Service
class MetricsService(
    private val meterRegistry: MeterRegistry,
) {
    // ========== Счетчики вакансий ==========
    private val vacanciesFetchedCounter: Counter = Counter.builder("vacancies.fetched")
        .description("Total number of vacancies fetched from HH.ru")
        .register(meterRegistry)

    private val vacanciesAnalyzedCounter: Counter = Counter.builder("vacancies.analyzed")
        .description("Total number of vacancies analyzed")
        .register(meterRegistry)

    private val vacanciesRelevantCounter: Counter = Counter.builder("vacancies.relevant")
        .description("Total number of relevant vacancies")
        .tag("status", "relevant")
        .register(meterRegistry)

    private val vacanciesSkippedCounter: Counter = Counter.builder("vacancies.skipped")
        .description("Total number of skipped (not relevant) vacancies")
        .tag("status", "skipped")
        .register(meterRegistry)

    private val vacanciesRejectedByValidatorCounter: Counter = Counter.builder("vacancies.rejected.validator")
        .description("Total number of vacancies rejected by content validator")
        .register(meterRegistry)

    // ========== Счетчики recovery ==========
    private val recoveryAttemptsCounter: Counter = Counter.builder("vacancies.recovery.attempts")
        .description("Total number of recovery attempts for skipped vacancies")
        .register(meterRegistry)

    private val recoveryRecoveredCounter: Counter = Counter.builder("vacancies.recovery.recovered")
        .description("Total number of vacancies successfully recovered (reset to NEW)")
        .tag("status", "recovered")
        .register(meterRegistry)

    private val recoveryDeletedCounter: Counter = Counter.builder("vacancies.recovery.deleted")
        .description("Total number of vacancies deleted during recovery (exclusion rules)")
        .tag("status", "deleted")
        .register(meterRegistry)

    private val recoverySkippedCounter: Counter = Counter.builder("vacancies.recovery.skipped")
        .description("Total number of vacancies skipped during recovery (already analyzed and not relevant)")
        .tag("status", "skipped")
        .register(meterRegistry)

    // ========== Счетчики сопроводительных писем ==========
    private val coverLettersGeneratedCounter: Counter = Counter.builder("cover_letters.generated")
        .description("Total number of cover letters successfully generated")
        .tag("status", "success")
        .register(meterRegistry)

    private val coverLettersFailedCounter: Counter = Counter.builder("cover_letters.failed")
        .description("Total number of cover letter generation failures")
        .tag("status", "failed")
        .register(meterRegistry)

    private val coverLettersRetryCounter: Counter = Counter.builder("cover_letters.retry")
        .description("Total number of cover letter generation retries")
        .register(meterRegistry)

    // ========== Счетчики уведомлений ==========
    private val notificationsSentCounter: Counter = Counter.builder("notifications.sent")
        .description("Total number of notifications sent to Telegram")
        .tag("channel", "telegram")
        .register(meterRegistry)

    private val notificationsFailedCounter: Counter = Counter.builder("notifications.failed")
        .description("Total number of failed notifications")
        .tag("channel", "telegram")
        .register(meterRegistry)

    // ========== Таймеры ==========
    private val vacancyAnalysisTimer: Timer = Timer.builder("vacancy.analysis.duration")
        .description("Time taken to analyze a vacancy")
        .register(meterRegistry)

    private val coverLetterGenerationTimer: Timer = Timer.builder("cover_letter.generation.duration")
        .description("Time taken to generate a cover letter")
        .register(meterRegistry)

    private val vacancyFetchTimer: Timer = Timer.builder("vacancy.fetch.duration")
        .description("Time taken to fetch vacancies from HH.ru")
        .register(meterRegistry)

    // ========== Gauge метрики (текущие значения) ==========
    private val queueSize = AtomicInteger(0)
    private val activeResume = AtomicInteger(0)

    init {
        // Регистрируем gauge метрики
        Gauge.builder("cover_letter.queue.size", queueSize) { it.get().toDouble() }
            .description("Current size of cover letter generation queue")
            .register(meterRegistry)

        Gauge.builder("resume.active", activeResume) { it.get().toDouble() }
            .description("Whether there is an active resume (1 = yes, 0 = no)")
            .register(meterRegistry)
    }

    // ========== Методы для обновления метрик ==========

    fun incrementVacanciesFetched(count: Int = 1) {
        vacanciesFetchedCounter.increment(count.toDouble())
    }

    fun incrementVacanciesAnalyzed() {
        vacanciesAnalyzedCounter.increment()
    }

    fun incrementVacanciesRelevant() {
        vacanciesRelevantCounter.increment()
    }

    fun incrementVacanciesSkipped() {
        vacanciesSkippedCounter.increment()
    }

    fun incrementVacanciesRejectedByValidator() {
        vacanciesRejectedByValidatorCounter.increment()
    }

    fun incrementRecoveryAttempts() {
        recoveryAttemptsCounter.increment()
    }

    fun incrementRecoveryRecovered(count: Int = 1) {
        recoveryRecoveredCounter.increment(count.toDouble())
    }

    fun incrementRecoveryDeleted(count: Int = 1) {
        recoveryDeletedCounter.increment(count.toDouble())
    }

    fun incrementRecoverySkipped(count: Int = 1) {
        recoverySkippedCounter.increment(count.toDouble())
    }

    fun incrementCoverLettersGenerated() {
        coverLettersGeneratedCounter.increment()
    }

    fun incrementCoverLettersFailed() {
        coverLettersFailedCounter.increment()
    }

    fun incrementCoverLettersRetry() {
        coverLettersRetryCounter.increment()
    }

    fun incrementNotificationsSent() {
        notificationsSentCounter.increment()
    }

    fun incrementNotificationsFailed() {
        notificationsFailedCounter.increment()
    }

    fun recordVacancyAnalysisTime(durationMs: Long) {
        vacancyAnalysisTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun recordCoverLetterGenerationTime(durationMs: Long) {
        coverLetterGenerationTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun recordVacancyFetchTime(durationMs: Long) {
        vacancyFetchTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun setQueueSize(size: Int) {
        queueSize.set(size)
    }

    fun setActiveResume(hasActive: Boolean) {
        activeResume.set(if (hasActive) 1 else 0)
    }

    /**
     * Получает MeterRegistry для регистрации кастомных метрик.
     */
    fun getMeterRegistry(): MeterRegistry {
        return meterRegistry
    }
}
