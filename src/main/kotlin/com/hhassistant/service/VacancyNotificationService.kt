package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.event.VacancyReadyForTelegramEvent
import com.hhassistant.exception.TelegramException
import mu.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Сервис для отправки уведомлений о вакансиях в Telegram
 * Слушает VacancyReadyForTelegramEvent и отправляет сообщения
 */
@Service
class VacancyNotificationService(
    private val telegramClient: TelegramClient,
    private val vacancyStatusService: VacancyStatusService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает событие готовности вакансии к отправке в Telegram
     */
    @EventListener
    @Async
    fun handleVacancyReadyForTelegram(event: VacancyReadyForTelegramEvent) {
        val vacancy = event.vacancy
        val analysis = event.analysis
        
        log.info("📱 [Notification] Processing VacancyReadyForTelegramEvent for vacancy ${vacancy.id}")
        
        try {
            runBlocking {
                sendVacancyToTelegram(vacancy, analysis)
            }
            vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SENT_TO_USER))
            log.info("✅ [Notification] Successfully sent vacancy ${vacancy.id} to Telegram")
        } catch (e: TelegramException.RateLimitException) {
            log.warn("⚠️ [Notification] Rate limit exceeded for Telegram, skipping vacancy ${vacancy.id} (will retry later)")
            // Не обновляем статус, попробуем отправить в следующий раз
        } catch (e: TelegramException) {
            log.error("❌ [Notification] Telegram error for vacancy ${vacancy.id}: ${e.message}", e)
            // Вакансия уже проанализирована, но не отправлена
        } catch (e: Exception) {
            log.error("❌ [Notification] Unexpected error sending vacancy ${vacancy.id} to Telegram: ${e.message}", e)
        }
    }

    /**
     * Отправляет вакансию в Telegram
     */
    private suspend fun sendVacancyToTelegram(
        vacancy: Vacancy,
        analysis: VacancyAnalysis,
    ) {
        val message = buildTelegramMessage(vacancy, analysis)
        telegramClient.sendMessage(message)
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
        sb.appendLine("🔗 <a href=\"${vacancy.url}\">Открыть вакансию на HH.ru</a>")
        sb.appendLine()
        sb.appendLine("⚡ <b>Быстрые действия:</b>")
        sb.appendLine("   ✅ <a href=\"${AppConstants.Urls.vacancyMarkApplied(vacancy.id)}\">Откликнулся</a>")
        sb.appendLine("   ❌ <a href=\"${AppConstants.Urls.vacancyMarkNotInterested(vacancy.id)}\">Неинтересная</a>")
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
        
        if (analysis.hasCoverLetter() && analysis.suggestedCoverLetter != null) {
            sb.appendLine("<b>💌 Сгенерированное сопроводительное письмо:</b>")
            sb.appendLine()
            sb.appendLine(escapeHtml(analysis.suggestedCoverLetter))
            sb.appendLine()
        } else {
            sb.appendLine("ℹ️ <i>Сопроводительное письмо не было сгенерировано</i>")
        }
        
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

