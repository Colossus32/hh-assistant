package com.hhassistant.service.health

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.health.HHAPIHealthIndicator
import com.hhassistant.health.OllamaHealthIndicator
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalTime

/**
 * Сервис для периодической проверки здоровья системы и отправки статуса в Telegram.
 * Проверяет статус Ollama и подключение к HH.ru API.
 * Не отправляет сообщения с 23:00 до 8:00.
 */
@Service
class HealthCheckService(
    private val ollamaHealthIndicator: OllamaHealthIndicator,
    private val hhapiHealthIndicator: HHAPIHealthIndicator,
    private val telegramClient: TelegramClient,
    @Value("\${telegram.enabled:true}") private val telegramEnabled: Boolean,
    @Value("\${app.healthcheck.enabled:true}") private val healthcheckEnabled: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Проверяет здоровье системы и отправляет статус в Telegram.
     * Запускается каждые 15 минут (можно настроить через app.healthcheck.schedule).
     * Не отправляет сообщения с 23:00 до 8:00.
     */
    @Scheduled(cron = "\${app.healthcheck.schedule:0 */15 * * * *}")
    fun performHealthCheck() {
        if (!healthcheckEnabled) {
            log.debug("📊 [HealthCheck] Healthcheck disabled, skipping")
            return
        }

        if (!telegramEnabled) {
            log.debug("📊 [HealthCheck] Telegram disabled, skipping")
            return
        }

        // Проверяем время: не отправляем с 23:00 до 8:00
        val currentTime = LocalTime.now()
        val sleepStart = LocalTime.of(23, 0)
        val sleepEnd = LocalTime.of(8, 0)

        if (isSleepTime(currentTime, sleepStart, sleepEnd)) {
            log.debug("📊 [HealthCheck] Skipping healthcheck - sleep time (23:00-8:00)")
            return
        }

        log.info("📊 [HealthCheck] Performing health check...")

        runBlocking {
            try {
                val ollamaHealth = ollamaHealthIndicator.health()
                val hhapiHealth = hhapiHealthIndicator.health()

                val message = buildHealthCheckMessage(ollamaHealth, hhapiHealth)
                val sent = telegramClient.sendMessage(message)
                
                if (sent) {
                    log.info("✅ [HealthCheck] Health check message sent to Telegram")
                } else {
                    log.warn("⚠️ [HealthCheck] Failed to send health check message (Telegram returned false)")
                }
            } catch (e: Exception) {
                log.error("❌ [HealthCheck] Failed to perform health check: ${e.message}", e)
            }
        }
    }

    /**
     * Проверяет, находится ли текущее время в диапазоне сна (23:00 - 8:00)
     */
    private fun isSleepTime(currentTime: LocalTime, sleepStart: LocalTime, sleepEnd: LocalTime): Boolean {
        return if (sleepStart.isAfter(sleepEnd)) {
            // Сон переходит через полночь (например, 23:00 - 8:00)
            currentTime.isAfter(sleepStart) || currentTime.isBefore(sleepEnd)
        } else {
            // Сон в пределах одного дня
            currentTime.isAfter(sleepStart) && currentTime.isBefore(sleepEnd)
        }
    }

    /**
     * Формирует сообщение о статусе здоровья системы
     */
    private fun buildHealthCheckMessage(ollamaHealth: Health, hhapiHealth: Health): String {
        return buildString {
            appendLine("📊 <b>Health Check</b>")
            appendLine()
            
            // Статус Ollama
            appendLine("<b>Ollama:</b>")
            when (ollamaHealth.status.code) {
                "UP" -> {
                    appendLine("   ✅ Доступен")
                    val models = ollamaHealth.details["models"]
                    if (models != null) {
                        appendLine("   📦 Модели: $models")
                    }
                }
                "DOWN" -> {
                    appendLine("   ❌ Недоступен")
                    val error = ollamaHealth.details["error"]
                    if (error != null) {
                        appendLine("   ⚠️ Ошибка: $error")
                    }
                }
                else -> {
                    appendLine("   ⚠️ Неизвестный статус")
                }
            }
            appendLine()
            
            // Статус HH.ru API
            appendLine("<b>HH.ru API:</b>")
            when (hhapiHealth.status.code) {
                "UP" -> {
                    appendLine("   ✅ Подключение работает")
                    val user = hhapiHealth.details["user"]
                    if (user != null && user != "unknown") {
                        appendLine("   👤 Пользователь: $user")
                    }
                }
                "DOWN" -> {
                    appendLine("   ❌ Подключение не работает")
                    val error = hhapiHealth.details["error"]
                    if (error != null) {
                        appendLine("   ⚠️ Ошибка: $error")
                    }
                }
                "UNKNOWN" -> {
                    appendLine("   ⚠️ Токен не настроен")
                    val reason = hhapiHealth.details["reason"]
                    if (reason != null) {
                        appendLine("   ℹ️ $reason")
                    }
                }
                else -> {
                    appendLine("   ⚠️ Неизвестный статус")
                }
            }
            appendLine()
            
            // Общий статус
            val allUp = ollamaHealth.status.code == "UP" && 
                       (hhapiHealth.status.code == "UP" || hhapiHealth.status.code == "UNKNOWN")
            
            if (allUp) {
                appendLine("✅ <b>Все системы работают</b>")
            } else {
                appendLine("⚠️ <b>Обнаружены проблемы</b>")
            }
        }
    }
}

