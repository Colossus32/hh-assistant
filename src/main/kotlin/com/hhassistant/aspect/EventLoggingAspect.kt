package com.hhassistant.aspect

import com.hhassistant.event.CoverLetterGeneratedEvent
import com.hhassistant.event.CoverLetterGenerationFailedEvent
import com.hhassistant.event.VacancyAnalyzedEvent
import com.hhassistant.event.VacancyFetchedEvent
import com.hhassistant.event.VacancyReadyForTelegramEvent
import com.hhassistant.event.VacancyStatusChangedEvent
import com.hhassistant.metrics.MetricsService
import mu.KotlinLogging
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.After
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.context.ApplicationEvent
import org.springframework.stereotype.Component

/**
 * AOP аспект для логирования событий Spring Event Bus
 * Логирует публикацию и обработку всех событий для отслеживания event-driven архитектуры
 */
@Aspect
@Component
class EventLoggingAspect(
    private val metricsService: MetricsService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Логирует публикацию событий через ApplicationEventPublisher.publishEvent()
     */
    @Before("execution(* org.springframework.context.ApplicationEventPublisher.publishEvent(..)) && args(event)")
    fun logEventPublished(joinPoint: JoinPoint, event: Any) {
        if (event !is ApplicationEvent) return
        val publisher = joinPoint.`this`
        val publisherName = publisher.javaClass.simpleName

        // Обновляем метрики
        val eventType = event.javaClass.simpleName
        metricsService.incrementEventsPublished(eventType)

        when (event) {
            is VacancyFetchedEvent -> {
                log.info("📤 [EventBus] PUBLISHED: VacancyFetchedEvent by $publisherName | vacancies: ${event.vacancies.size}, keywords: '${event.searchKeywords}'")
            }
            is VacancyAnalyzedEvent -> {
                log.info("📤 [EventBus] PUBLISHED: VacancyAnalyzedEvent by $publisherName | vacancy: ${event.vacancy.id} ('${event.vacancy.name}'), relevant: ${event.analysis.isRelevant}, score: ${String.format("%.2f", event.analysis.relevanceScore * 100)}%")
            }
            is CoverLetterGeneratedEvent -> {
                log.info("📤 [EventBus] PUBLISHED: CoverLetterGeneratedEvent by $publisherName | vacancy: ${event.vacancy.id} ('${event.vacancy.name}'), coverLetter length: ${event.analysis.suggestedCoverLetter?.length ?: 0}")
            }
            is CoverLetterGenerationFailedEvent -> {
                log.info("📤 [EventBus] PUBLISHED: CoverLetterGenerationFailedEvent by $publisherName | vacancy: ${event.vacancy.id} ('${event.vacancy.name}'), attempts: ${event.attempts}")
            }
            is VacancyReadyForTelegramEvent -> {
                log.info("📤 [EventBus] PUBLISHED: VacancyReadyForTelegramEvent by $publisherName | vacancy: ${event.vacancy.id} ('${event.vacancy.name}'), hasCoverLetter: ${event.analysis.hasCoverLetter()}")
            }
            is VacancyStatusChangedEvent -> {
                log.info("📤 [EventBus] PUBLISHED: VacancyStatusChangedEvent by $publisherName | vacancy: ${event.vacancy.id} ('${event.vacancy.name}'), status: ${event.oldStatus} -> ${event.newStatus}")
            }
            else -> {
                log.debug("📤 [EventBus] PUBLISHED: ${event.javaClass.simpleName} by $publisherName")
            }
        }
    }

    /**
     * Логирует обработку событий через @EventListener методы
     */
    @Before("@annotation(org.springframework.context.event.EventListener) && args(event,..)")
    fun logEventReceived(joinPoint: JoinPoint, event: ApplicationEvent) {
        val listener = joinPoint.`this`
        val listenerName = listener.javaClass.simpleName
        val methodName = joinPoint.signature.name

        // Обновляем метрики
        val eventType = event.javaClass.simpleName
        metricsService.incrementEventsReceived(eventType)

        when (event) {
            is VacancyFetchedEvent -> {
                log.info("📥 [EventBus] RECEIVED: VacancyFetchedEvent by $listenerName.$methodName() | vacancies: ${event.vacancies.size}")
            }
            is VacancyAnalyzedEvent -> {
                log.info("📥 [EventBus] RECEIVED: VacancyAnalyzedEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}")
            }
            is CoverLetterGeneratedEvent -> {
                log.info("📥 [EventBus] RECEIVED: CoverLetterGeneratedEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}")
            }
            is CoverLetterGenerationFailedEvent -> {
                log.info("📥 [EventBus] RECEIVED: CoverLetterGenerationFailedEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}")
            }
            is VacancyReadyForTelegramEvent -> {
                log.info("📥 [EventBus] RECEIVED: VacancyReadyForTelegramEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}")
            }
            is VacancyStatusChangedEvent -> {
                log.info("📥 [EventBus] RECEIVED: VacancyStatusChangedEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}, status: ${event.oldStatus} -> ${event.newStatus}")
            }
            else -> {
                log.debug("📥 [EventBus] RECEIVED: ${event.javaClass.simpleName} by $listenerName.$methodName()")
            }
        }
    }

    /**
     * Логирует успешное завершение обработки события
     */
    @After("@annotation(org.springframework.context.event.EventListener) && args(event,..)")
    fun logEventProcessed(joinPoint: JoinPoint, event: ApplicationEvent) {
        val listener = joinPoint.`this`
        val listenerName = listener.javaClass.simpleName
        val methodName = joinPoint.signature.name

        log.debug("✅ [EventBus] PROCESSED: ${event.javaClass.simpleName} by $listenerName.$methodName()")
    }
}
