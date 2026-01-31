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

    private val vacanciesFailedCounter: Counter = Counter.builder("vacancies.failed")
        .description("Total number of vacancies that failed processing (dead letter queue)")
        .tag("status", "failed")
        .register(meterRegistry)

    // ========== Счетчики событий ==========
    private val eventsPublishedCounter: Counter = Counter.builder("events.published")
        .description("Total number of events published")
        .register(meterRegistry)

    private val eventsReceivedCounter: Counter = Counter.builder("events.received")
        .description("Total number of events received")
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

    fun incrementVacanciesFailed() {
        vacanciesFailedCounter.increment()
    }

    fun incrementEventsPublished(eventType: String) {
        eventsPublishedCounter.increment()
        Counter.builder("events.published.by_type")
            .description("Events published by type")
            .tag("type", eventType)
            .register(meterRegistry)
            .increment()
    }

    fun incrementEventsReceived(eventType: String) {
        eventsReceivedCounter.increment()
        Counter.builder("events.received.by_type")
            .description("Events received by type")
            .tag("type", eventType)
            .register(meterRegistry)
            .increment()
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
