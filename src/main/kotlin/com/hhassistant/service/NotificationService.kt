package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.AppConstants
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlinx.coroutines.runBlocking

/**
 * Сервис для отправки системных уведомлений в Telegram
 */
@Service
class NotificationService(
    private val telegramClient: TelegramClient,
    @Value("\${telegram.enabled:true}") private val telegramEnabled: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Отправляет уведомление о старте приложения
     */
    fun sendStartupNotification() {
        if (!telegramEnabled) {
            log.debug("📱 [Notification] Telegram disabled, skipping startup notification")
            return
        }

        val message = buildString {
            appendLine("✅ <b>HH Assistant запущен!</b>")
            appendLine()
            appendLine("Приложение успешно стартовало и готово к работе.")
            appendLine()
            appendLine("🔍 Проверка вакансий будет выполняться каждые 15 минут")
            appendLine("📊 Статус компонентов:")
            appendLine("   • HH.ru API: проверяется...")
            appendLine("   • Ollama: готов")
            appendLine("   • Telegram: готов")
        }

        runBlocking {
            try {
                val sent = telegramClient.sendMessage(message)
                if (sent) {
                    log.info("✅ [Notification] Startup notification sent to Telegram")
                } else {
                    log.warn("⚠️ [Notification] Failed to send startup notification (Telegram returned false)")
                }
            } catch (e: Exception) {
                log.error("❌ [Notification] Failed to send startup notification: ${e.message}", e)
            }
        }
    }

    /**
     * Отправляет обновленное уведомление о статусе после проверки HH.ru API
     */
    fun sendStatusUpdate(
        hhApiStatus: String,
        searchKeywords: List<String>,
        vacanciesFound: Int,
    ) {
        if (!telegramEnabled) {
            log.debug("📱 [Notification] Telegram disabled, skipping status update")
            return
        }

        val keywordsText = if (searchKeywords.isNotEmpty()) {
            searchKeywords.joinToString(", ") { "'$it'" }
        } else {
            "не настроены"
        }

        val message = buildString {
            appendLine("📊 <b>Статус проверки HH.ru API</b>")
            appendLine()
            appendLine("🔍 <b>Ключевые слова поиска:</b>")
            appendLine("   $keywordsText")
            appendLine()
            appendLine("📊 <b>Результат:</b>")
            appendLine("   • HH.ru API: $hhApiStatus")
            appendLine("   • Найдено новых вакансий: $vacanciesFound")
            appendLine()
            if (hhApiStatus.contains("✅", ignoreCase = true) || hhApiStatus.contains("UP", ignoreCase = true)) {
                appendLine("✅ Всё работает корректно!")
            } else {
                appendLine("⚠️ Проверьте настройки и токен HH.ru")
            }
        }

        runBlocking {
            try {
                val sent = telegramClient.sendMessage(message)
                if (sent) {
                    log.info("✅ [Notification] Status update sent to Telegram")
                } else {
                    log.warn("⚠️ [Notification] Failed to send status update (Telegram returned false)")
                }
            } catch (e: Exception) {
                log.error("❌ [Notification] Failed to send status update: ${e.message}", e)
            }
        }
    }

    /**
     * Отправляет алерт об истечении токена HH.ru или проблеме с правами доступа
     */
    fun sendTokenExpiredAlert(errorMessage: String) {
        if (!telegramEnabled) {
            log.debug("📱 [Notification] Telegram disabled, skipping token expired alert")
            return
        }

        val isForbidden = errorMessage.contains("403", ignoreCase = true) || 
                         errorMessage.contains("Forbidden", ignoreCase = true)
        
        val message = buildString {
            appendLine("🚨 <b>ВНИМАНИЕ: Проблема с токеном HH.ru!</b>")
            appendLine()
            if (isForbidden) {
                appendLine("❌ Access token для HH.ru API недействителен или не имеет необходимых прав доступа.")
                appendLine()
                appendLine("Возможные причины:")
                appendLine("• Токен истек")
                appendLine("• Токен недействителен")
                appendLine("• Токен не имеет прав на поиск вакансий")
                appendLine("• Неправильный формат токена")
            } else {
                appendLine("❌ Access token для HH.ru API истек или недействителен.")
            }
            appendLine()
            appendLine("<b>Ошибка:</b>")
            appendLine("$errorMessage")
            appendLine()
            appendLine("🔧 <b>Что делать:</b>")
            appendLine("1. Откройте в браузере: <a href=\"${AppConstants.Urls.OAUTH_AUTHORIZE}\">${AppConstants.Urls.OAUTH_AUTHORIZE}</a>")
            appendLine("2. Авторизуйтесь на HH.ru")
            appendLine("3. Токен автоматически сохранится в .env файл")
            appendLine("4. Перезапустите приложение")
            appendLine()
            appendLine("💡 <b>Совет:</b> После получения токена он будет автоматически сохранен, вам не нужно копировать его вручную!")
            appendLine()
            appendLine("📖 Подробная инструкция: docs/GET_TOKEN_STEP_BY_STEP.md")
        }

        runBlocking {
            try {
                val sent = telegramClient.sendMessage(message)
                if (sent) {
                    log.info("✅ [Notification] Token expired alert sent to Telegram")
                } else {
                    log.warn("⚠️ [Notification] Failed to send token expired alert (Telegram returned false)")
                }
            } catch (e: Exception) {
                log.error("❌ [Notification] Failed to send token expired alert: ${e.message}", e)
            }
        }
    }
}

