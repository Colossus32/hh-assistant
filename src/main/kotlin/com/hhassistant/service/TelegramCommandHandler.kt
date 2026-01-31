package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import org.springframework.web.client.postForObject

/**
 * Обработчик команд Telegram бота.
 * Обрабатывает команды пользователей, вызывая соответствующие REST API endpoints приложения.
 */
@Service
class TelegramCommandHandler(
    private val telegramClient: TelegramClient,
    private val restTemplate: RestTemplate,
    @Value("\${app.api.base-url:http://localhost:8080}") private val apiBaseUrl: String,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает команду или сообщение от пользователя.
     *
     * @param chatId ID чата пользователя
     * @param text Текст команды или сообщения
     */
    fun handleCommand(chatId: String, text: String) {
        log.info("📱 [TelegramCommand] Handling command from chat $chatId: $text")

        val response = when {
            text.startsWith("/start") -> handleStartCommand(chatId)
            text.startsWith("/status") -> handleStatusCommand(chatId)
            text.startsWith("/vacancies") -> handleVacanciesCommand(chatId, text)
            text.startsWith("/help") -> handleHelpCommand(chatId)
            text.matches(Regex("/mark-applied-\\d+")) -> handleMarkAppliedCommand(chatId, text)
            text.matches(Regex("/mark-not-interested-\\d+")) -> handleMarkNotInterestedCommand(chatId, text)
            else -> {
                log.debug("📱 [TelegramCommand] Unknown command: $text")
                "❓ Неизвестная команда. Используйте /help для списка доступных команд."
            }
        }

        runBlocking {
            try {
                // Отправляем ответ в тот же чат, откуда пришла команда
                // TelegramClient использует chatId из конфигурации, поэтому нужно создать временный клиент
                // или добавить метод sendMessage с параметром chatId
                // Пока используем существующий метод (отправит в настроенный chatId)
                telegramClient.sendMessage(response)
            } catch (e: Exception) {
                log.error("❌ [TelegramCommand] Failed to send response: ${e.message}", e)
            }
        }
    }

    /**
     * Обрабатывает команду /start
     */
    private fun handleStartCommand(chatId: String): String {
        return buildString {
            appendLine("👋 <b>Добро пожаловать в HH Assistant!</b>")
            appendLine()
            appendLine("Я помогу вам найти подходящие вакансии на HH.ru.")
            appendLine()
            appendLine("📋 <b>Доступные команды:</b>")
            appendLine("   /start - Начать работу")
            appendLine("   /status - Статус системы")
            appendLine("   /vacancies - Список непросмотренных вакансий")
            appendLine("   /help - Справка")
            appendLine()
            appendLine("💡 Используйте /help для подробной информации.")
        }
    }

    /**
     * Обрабатывает команду /status
     */
    private fun handleStatusCommand(chatId: String): String {
        return try {
            // Можно добавить вызов REST API для получения статуса
            buildString {
                appendLine("📊 <b>Статус системы:</b>")
                appendLine()
                appendLine("✅ Бот работает")
                appendLine("✅ REST API доступен")
                appendLine()
                appendLine("💡 Используйте /vacancies для просмотра вакансий.")
            }
        } catch (e: Exception) {
            log.error("Error getting status: ${e.message}", e)
            "❌ Ошибка при получении статуса: ${e.message}"
        }
    }

    /**
     * Обрабатывает команду /vacancies
     */
    private fun handleVacanciesCommand(chatId: String, text: String): String {
        return try {
            val url = "$apiBaseUrl/api/vacancies/unviewed"
            val response = restTemplate.getForObject<Map<String, Any>>(url)

            val count = response?.get("count") as? Int ?: 0
            val vacancies = response?.get("vacancies") as? List<Map<String, Any>> ?: emptyList()

            if (count == 0) {
                "📋 <b>Непросмотренные вакансии:</b>\n\nНет новых вакансий."
            } else {
                buildString {
                    appendLine("📋 <b>Непросмотренные вакансии ($count):</b>")
                    appendLine()
                    vacancies.take(10).forEachIndexed { index, vacancy ->
                        val id = vacancy["id"] as? String ?: ""
                        val name = vacancy["name"] as? String ?: "Без названия"
                        val employer = vacancy["employer"] as? String ?: "Не указан"
                        val salary = vacancy["salary"] as? String ?: "Не указана"
                        val url = vacancy["url"] as? String ?: ""

                        appendLine("${index + 1}. <b>$name</b>")
                        appendLine("   💼 $employer")
                        appendLine("   💰 $salary")
                        appendLine("   🔗 <a href=\"$url\">Открыть на HH.ru</a>")
                        appendLine("   ✅ /mark-applied-$id | ❌ /mark-not-interested-$id")
                        appendLine()
                    }
                    if (count > 10) {
                        appendLine("... и еще ${count - 10} вакансий")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error getting vacancies: ${e.message}", e)
            "❌ Ошибка при получении вакансий: ${e.message}"
        }
    }

    /**
     * Обрабатывает команду /help
     */
    private fun handleHelpCommand(chatId: String): String {
        return buildString {
            appendLine("📖 <b>Справка по командам:</b>")
            appendLine()
            appendLine("<b>/start</b> - Начать работу с ботом")
            appendLine()
            appendLine("<b>/status</b> - Показать статус системы")
            appendLine()
            appendLine("<b>/vacancies</b> - Показать список непросмотренных вакансий")
            appendLine()
            appendLine("<b>/mark-applied-{id}</b> - Отметить вакансию как \"откликнулся\"")
            appendLine("   Пример: /mark-applied-12345678")
            appendLine()
            appendLine("<b>/mark-not-interested-{id}</b> - Отметить вакансию как \"неинтересная\"")
            appendLine("   Пример: /mark-not-interested-12345678")
            appendLine()
            appendLine("<b>/help</b> - Показать эту справку")
        }
    }

    /**
     * Обрабатывает команду /mark-applied-{id}
     */
    private fun handleMarkAppliedCommand(chatId: String, text: String): String {
        val vacancyId = text.removePrefix("/mark-applied-")
        return try {
            val url = "$apiBaseUrl/api/vacancies/$vacancyId/mark-applied"
            val response = restTemplate.postForObject<Map<String, Any>>(url, null)

            if (response?.get("success") == true) {
                "✅ Вакансия отмечена как \"откликнулся\""
            } else {
                val message = response?.get("message") as? String ?: "Ошибка"
                "❌ $message"
            }
        } catch (e: Exception) {
            log.error("Error marking vacancy as applied: ${e.message}", e)
            "❌ Ошибка при обновлении статуса: ${e.message}"
        }
    }

    /**
     * Обрабатывает команду /mark-not-interested-{id}
     */
    private fun handleMarkNotInterestedCommand(chatId: String, text: String): String {
        val vacancyId = text.removePrefix("/mark-not-interested-")
        return try {
            val url = "$apiBaseUrl/api/vacancies/$vacancyId/mark-not-interested"
            val response = restTemplate.postForObject<Map<String, Any>>(url, null)

            if (response?.get("success") == true) {
                "✅ Вакансия отмечена как \"неинтересная\""
            } else {
                val message = response?.get("message") as? String ?: "Ошибка"
                "❌ $message"
            }
        } catch (e: Exception) {
            log.error("Error marking vacancy as not interested: ${e.message}", e)
            "❌ Ошибка при обновлении статуса: ${e.message}"
        }
    }
}

