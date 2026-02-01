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
 * AOP aspect for logging Spring Event Bus events
 * Logs event publication and handling for event-driven architecture tracking
 */
@Aspect
@Component
class EventLoggingAspect(
    private val metricsService: MetricsService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Logs event publication via ApplicationEventPublisher.publishEvent()
     * Only logs important events at INFO level, others at DEBUG
     */
    @Before("execution(* org.springframework.context.ApplicationEventPublisher.publishEvent(..)) && args(event)")
    fun logEventPublished(joinPoint: JoinPoint, event: Any) {
        if (event !is ApplicationEvent) return
        val publisher = joinPoint.`this`
        val publisherName = publisher.javaClass.simpleName

        // Update metrics
        val eventType = event.javaClass.simpleName
        metricsService.incrementEventsPublished(eventType)

        when (event) {
            is VacancyFetchedEvent -> {
                log.info("[EventBus] Published VacancyFetchedEvent by $publisherName | vacancies: ${event.vacancies.size}, keywords: '${event.searchKeywords}'")
            }
            is VacancyReadyForTelegramEvent -> {
                log.info("[EventBus] Published VacancyReadyForTelegramEvent by $publisherName | vacancy: ${event.vacancy.id}")
            }
            is VacancyStatusChangedEvent -> {
                log.debug("[EventBus] Published VacancyStatusChangedEvent by $publisherName | vacancy: ${event.vacancy.id}, status: ${event.oldStatus} -> ${event.newStatus}")
            }
            is VacancyAnalyzedEvent,
            is CoverLetterGeneratedEvent,
            is CoverLetterGenerationFailedEvent -> {
                log.debug("[EventBus] Published ${event.javaClass.simpleName} by $publisherName | vacancy: ${(event as? VacancyAnalyzedEvent)?.vacancy?.id ?: (event as? CoverLetterGeneratedEvent)?.vacancy?.id ?: (event as? CoverLetterGenerationFailedEvent)?.vacancy?.id}")
            }
            else -> {
                log.trace("[EventBus] Published ${event.javaClass.simpleName} by $publisherName")
            }
        }
    }

    /**
     * Logs event handling via @EventListener methods
     * Only logs important events at INFO level, others at DEBUG
     */
    @Before("@annotation(org.springframework.context.event.EventListener) && args(event,..)")
    fun logEventReceived(joinPoint: JoinPoint, event: ApplicationEvent) {
        val listener = joinPoint.`this`
        val listenerName = listener.javaClass.simpleName
        val methodName = joinPoint.signature.name

        // Update metrics
        val eventType = event.javaClass.simpleName
        metricsService.incrementEventsReceived(eventType)

        when (event) {
            is VacancyReadyForTelegramEvent -> {
                log.info("[EventBus] Received VacancyReadyForTelegramEvent by $listenerName.$methodName() | vacancy: ${event.vacancy.id}")
            }
            is VacancyFetchedEvent,
            is VacancyAnalyzedEvent,
            is CoverLetterGeneratedEvent,
            is CoverLetterGenerationFailedEvent,
            is VacancyStatusChangedEvent -> {
                log.debug("[EventBus] Received ${event.javaClass.simpleName} by $listenerName.$methodName()")
            }
            else -> {
                log.trace("[EventBus] Received ${event.javaClass.simpleName} by $listenerName.$methodName()")
            }
        }
    }

    /**
     * Logs successful event processing completion
     */
    @After("@annotation(org.springframework.context.event.EventListener) && args(event,..)")
    fun logEventProcessed(joinPoint: JoinPoint, event: ApplicationEvent) {
        log.trace("[EventBus] Processed ${event.javaClass.simpleName} by ${joinPoint.`this`.javaClass.simpleName}.${joinPoint.signature.name}()")
    }
}
