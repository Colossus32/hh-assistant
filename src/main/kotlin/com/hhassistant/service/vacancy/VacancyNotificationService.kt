package com.hhassistant.service.vacancy

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.exception.TelegramException
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Сервис для отправки уведомлений о вакансиях в Telegram
 * Использует прямые вызовы методов вместо событий
 */
@Service
class VacancyNotificationService(
    private val telegramClient: TelegramClient,
    private val vacancyStatusService: VacancyStatusService,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    @Value("\${app.api.base-url:${AppConstants.Urls.LOCALHOST_BASE}}") private val apiBaseUrl: String,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Отправляет вакансию в Telegram и обновляет статус
     * Заменяет event-driven подход на прямой вызов
     *
     * @param vacancy Вакансия для отправки
     * @param analysis Анализ вакансии
     * @return true если сообщение успешно отправлено, false если Telegram отключен или не настроен
     */
    @Loggable
    suspend fun sendVacancyToTelegram(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ): Boolean {
        log.info("📱 [Notification] Sending vacancy ${vacancy.id} to Telegram")

        try {
            // Fix vacancy URL if it's in wrong format (API URL instead of browser URL)
            val correctedVacancy = vacancy.copy(url = normalizeVacancyUrl(vacancy.url, vacancy.id))
            val message = buildTelegramMessage(correctedVacancy, analysis)

            // Send message and return result (true if sent, false if disabled/not configured)
            val sentSuccessfully = telegramClient.sendMessage(message, null)

            // Update status and sent timestamp only if message was actually sent
            if (sentSuccessfully) {
                val sentAt = java.time.LocalDateTime.now()
                vacancyStatusService.updateVacancyStatus(vacancy.withSentToTelegramAt(sentAt))
                metricsService.incrementNotificationsSent()
                log.info("[Notification] Successfully sent vacancy ${vacancy.id} to Telegram at $sentAt")
            } else {
                log.warn("[Notification] Message sending returned false for vacancy ${vacancy.id} (Telegram may be disabled or not configured)")
                // Don't update status - vacancy remains in ANALYZED state
            }

            return sentSuccessfully
        } catch (e: TelegramException.RateLimitException) {
            metricsService.incrementNotificationsFailed()
            log.warn("⚠️ [Notification] Rate limit exceeded for Telegram, skipping vacancy ${vacancy.id} (will retry later)")
            // Не обновляем статус, попробуем отправить в следующий раз
            throw e
        } catch (e: TelegramException) {
            metricsService.incrementNotificationsFailed()
            log.error("❌ [Notification] Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
            // Вакансия уже проанализирована, но не отправлена
            throw e
        } catch (e: Exception) {
            metricsService.incrementNotificationsFailed()
            log.error("❌ [Notification] Unexpected error sending vacancy ${vacancy.id} to Telegram: ${e.message}", e)
            throw e
        }
    }

    /**
     * Нормализует URL вакансии, преобразуя API URL в браузерный формат
     */
    private fun normalizeVacancyUrl(url: String, vacancyId: String): String {
        return when {
            // Если уже правильный формат (hh.ru/vacancy/...)
            url.contains("hh.ru/vacancy/") && !url.contains("api.hh.ru") -> {
                // Убираем query параметры если есть
                url.substringBefore("?")
            }
            // Если это API URL, преобразуем в браузерный
            url.contains("/vacancies/") || url.contains("api.hh.ru") -> {
                // Извлекаем ID из URL или используем переданный ID
                val id = if (url.contains("/vacancies/")) {
                    url.substringAfter("/vacancies/").substringBefore("?")
                } else {
                    vacancyId
                }
                "https://hh.ru/vacancy/$id"
            }
            // Если формат неизвестен, используем ID
            else -> {
                "https://hh.ru/vacancy/$vacancyId"
            }
        }
    }

    /**
     * Формирует сообщение для Telegram
     */
    private fun buildTelegramMessage(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("🎯 <b>Новая релевантная вакансия!</b>")
        sb.appendLine()
        sb.appendLine("<b>${escapeHtml(vacancy.name)}</b>")
        sb.appendLine("🏢 ${escapeHtml(vacancy.employer)}")
        if (vacancy.salary != null) {
            sb.appendLine("💰 ${escapeHtml(vacancy.salary)}")
        }
        sb.appendLine("📍 ${escapeHtml(vacancy.area)}")
        if (vacancy.experience != null) {
            sb.appendLine("💼 ${escapeHtml(vacancy.experience)}")
        }
        sb.appendLine()
        // URL в href не нужно экранировать, только текст ссылки
        sb.appendLine("🔗 <a href=\"${vacancy.url}\">Открыть вакансию на HH.ru</a>")
        sb.appendLine()

        if (!vacancy.description.isNullOrBlank()) {
            sb.appendLine("<b>📋 Описание вакансии:</b>")
            val description = if (vacancy.description.length > AppConstants.TextLimits.TELEGRAM_DESCRIPTION_MAX_LENGTH) {
                vacancy.description.take(AppConstants.TextLimits.TELEGRAM_DESCRIPTION_MAX_LENGTH) + "..."
            } else {
                vacancy.description
            }
            sb.appendLine(escapeHtml(description))
            sb.appendLine()
        }

        sb.appendLine("<b>📊 Оценка релевантности:</b> ${(analysis.relevanceScore * AppConstants.Formatting.PERCENTAGE_MULTIPLIER).toInt()}%")
        sb.appendLine()
        sb.appendLine("<b>💡 Обоснование:</b>")
        sb.appendLine(escapeHtml(analysis.reasoning))
        sb.appendLine()

        // Добавляем команду для пометки вакансии как неинтересной
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("❌ <code>/mark-not-interested-${vacancy.id}</code>")
        sb.appendLine("   Отметить как неинтересную")

        return sb.toString()
    }

    /**
     * Экранирует HTML-специальные символы
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
