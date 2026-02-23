package com.hhassistant.notification.service

import com.hhassistant.integration.telegram.TelegramClient
import com.hhassistant.integration.telegram.dto.Update
import com.hhassistant.exception.TelegramException
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Сервис для периодического опроса обновлений от Telegram Bot API (polling).
 * Обрабатывает команды и сообщения от пользователей.
 *
 * Использует long polling для эффективного получения обновлений.
 * Обработка команд выполняется асинхронно через корутины, не блокируя поток.
 */
@Service
class TelegramPollingService(
    private val telegramClient: TelegramClient,
    private val telegramCommandHandler: TelegramCommandHandler,
    @Value("\${telegram.polling.enabled:true}") private val pollingEnabled: Boolean,
    @Value("\${telegram.polling.interval-seconds:5}") private val pollingIntervalSeconds: Long,
    @Value("\${telegram.polling.timeout-seconds:30}") private val pollingTimeoutSeconds: Int,
) {
    private val log = KotlinLogging.logger {}
    private var lastUpdateId: Long? = null
    private var isPolling = false
    private var isShuttingDown = false

    // CoroutineScope для асинхронной обработки polling и команд
    private val pollingScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
            log.error("📱 [TelegramPolling] Unhandled exception in polling coroutine: ${exception.message}", exception)
        },
    )

    /**
     * Запускает polling после старта приложения
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (pollingEnabled) {
            log.info("📱 [TelegramPolling] Polling enabled, will start after application is ready")
        } else {
            log.info("📱 [TelegramPolling] Polling disabled")
        }
    }

    /**
     * Периодически опрашивает Telegram API для получения новых обновлений.
     * Использует long polling для уменьшения количества запросов.
     * Обработка выполняется асинхронно через корутины, не блокируя поток.
     */
    @Scheduled(fixedDelayString = "\${telegram.polling.interval-seconds:5}", initialDelay = 10000)
    fun pollUpdates() {
        if (!pollingEnabled || isPolling || isShuttingDown) {
            return
        }

        isPolling = true
        // Запускаем polling асинхронно, не блокируя поток
        pollingScope.launch {
            try {
                try {
                    val updates = telegramClient.getUpdates(
                        offset = lastUpdateId?.let { it + 1 },
                        limit = 100,
                        timeout = pollingTimeoutSeconds,
                    )

                    if (updates.isNotEmpty()) {
                        log.info("📱 [TelegramPolling] Received ${updates.size} update(s)")
                        processUpdates(updates)
                    }
                } catch (e: TelegramException.RateLimitException) {
                    log.warn("📱 [TelegramPolling] Rate limit exceeded, waiting before retry")
                    delay(60000) // Ждем 1 минуту при rate limit
                } catch (e: TelegramException) {
                    log.error("📱 [TelegramPolling] Error getting updates: ${e.message}", e)
                    delay(5000) // Небольшая задержка перед повтором
                } catch (e: Exception) {
                    log.error("📱 [TelegramPolling] Unexpected error: ${e.message}", e)
                    delay(5000)
                }
            } finally {
                isPolling = false
            }
        }
    }

    /**
     * Обрабатывает полученные обновления.
     * Каждая команда обрабатывается асинхронно, не блокируя обработку других команд.
     */
    private suspend fun processUpdates(updates: List<Update>) {
        for (update in updates) {
            try {
                // Обновляем lastUpdateId для пропуска уже обработанных обновлений
                lastUpdateId = update.updateId

                // Обрабатываем сообщение, если оно есть
                update.message?.let { message ->
                    val chatId = message.chat?.id?.toString()
                    val text = message.text

                    if (chatId != null && text != null) {
                        log.info("📱 [TelegramPolling] Received message from chat $chatId: $text")
                        // Запускаем обработку команды асинхронно, не блокируя обработку других команд
                        pollingScope.launch {
                            try {
                                telegramCommandHandler.handleCommand(chatId, text)
                            } catch (e: Exception) {
                                log.error("📱 [TelegramPolling] Error handling command: ${e.message}", e)
                            }
                        }
                    } else {
                        log.debug("📱 [TelegramPolling] Update ${update.updateId} has no message or text, skipping")
                    }
                }
            } catch (e: Exception) {
                log.error("📱 [TelegramPolling] Error processing update ${update.updateId}: ${e.message}", e)
            }
        }
    }

    /**
     * Корректное завершение работы при остановке приложения.
     * Останавливает прием новых задач, ждет завершения активных с таймаутом, затем отменяет scope.
     */
    @PreDestroy
    fun shutdown() {
        log.info("📱 [TelegramPolling] Shutting down...")
        isShuttingDown = true
        runBlocking {
            withTimeoutOrNull(30_000) {
                pollingScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
            } ?: log.warn("📱 [TelegramPolling] Shutdown timeout, cancelling active tasks")
        }
        pollingScope.coroutineContext.cancel()
        log.info("📱 [TelegramPolling] Shutdown complete")
    }
}
