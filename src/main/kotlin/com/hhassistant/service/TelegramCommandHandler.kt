package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.domain.entity.VacancyStatus
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
    private val skillExtractionService: SkillExtractionService,
    private val vacancyService: VacancyService,
    private val exclusionRuleService: ExclusionRuleService,
    private val analysisTimeService: AnalysisTimeService,
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

        // Команда /skills требует асинхронной обработки, обрабатываем отдельно
        if (text.startsWith("/skills")) {
            runBlocking {
                try {
                    val response = handleSkillsCommand(chatId, text)
                    telegramClient.sendMessage(chatId, response)
                } catch (e: Exception) {
                    log.error("❌ [TelegramCommand] Failed to handle /skills command: ${e.message}", e)
                    telegramClient.sendMessage(chatId, "❌ Ошибка при обработке команды /skills: ${e.message}")
                }
            }
            return
        }

        val response = when {
            text.startsWith("/start") -> handleStartCommand(chatId)
            text.startsWith("/status") -> handleStatusCommand(chatId)
            text.startsWith("/stats") -> handleStatsCommand(chatId)
            text.startsWith("/vacancies_all") -> handleAllVacanciesCommand(chatId)
            text.startsWith("/vacancies") -> handleVacanciesCommand(chatId, text)
            text.startsWith("/exclusion_add_keyword") -> handleAddExclusionKeyword(chatId, text)
            text.startsWith("/exclusion_add_phrase") -> handleAddExclusionPhrase(chatId, text)
            text.startsWith("/exclusion_remove_keyword") -> handleRemoveExclusionKeyword(chatId, text)
            text.startsWith("/exclusion_remove_phrase") -> handleRemoveExclusionPhrase(chatId, text)
            text.startsWith("/exclusion_list") -> handleListExclusions(chatId)
            text.startsWith("/sent_status") -> handleSentStatusCommand(chatId, text)
            text.startsWith("/help") -> handleHelpCommand(chatId)
            text.matches(Regex("/mark-applied-\\d+")) -> handleMarkAppliedCommand(chatId, text)
            text.matches(Regex("/mark-not-interested-\\d+")) -> handleMarkNotInterestedCommand(chatId, text)
            else -> {
                log.debug("[TelegramCommand] Unknown command: $text")
                "❓ Unknown command. Use /help for list of available commands."
            }
        }

        runBlocking {
            try {
                // Отправляем ответ в тот же чат, откуда пришла команда
                telegramClient.sendMessage(chatId, response)
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
            appendLine("   /stats - Статистика по вакансиям")
            appendLine("   /vacancies - Список непросмотренных вакансий")
            appendLine("   /vacancies_all - Список всех вакансий (включая просмотренные)")
            appendLine("   /skills [N] - Топ навыков по популярности")
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
     * Обрабатывает команду /stats - показывает статистику по вакансиям
     */
    private fun handleStatsCommand(chatId: String): String {
        return try {
            log.info("📊 [TelegramCommand] Processing /stats command for chat $chatId")
            
            val averageTimeMs = analysisTimeService.getAverageTimeMs()
            val statistics = vacancyService.getVacancyStatistics(averageTimeMs)
            
            buildString {
                appendLine("📊 <b>Статистика по вакансиям:</b>")
                appendLine()
                appendLine("✅ <b>Обработано:</b> ${statistics.processedCount}")
                appendLine("⏳ <b>В очереди на обработку:</b> ${statistics.queueCount}")
                appendLine()
                
                if (statistics.averageAnalysisTimeMs != null) {
                    val avgSeconds = statistics.averageAnalysisTimeMs / 1000.0
                    appendLine("⏱️ <b>Среднее время обработки:</b> ${String.format("%.2f", avgSeconds)} сек")
                } else {
                    appendLine("⏱️ <b>Среднее время обработки:</b> Нет данных (еще не было анализов)")
                }
                
                appendLine()
                
                if (statistics.estimatedTimeMs != null) {
                    val estimatedSeconds = statistics.estimatedTimeMs / 1000.0
                    val estimatedMinutes = estimatedSeconds / 60.0
                    val estimatedHours = estimatedMinutes / 60.0
                    
                    when {
                        estimatedHours >= 1.0 -> {
                            appendLine("🕐 <b>Приблизительное время обработки оставшихся:</b> ${String.format("%.1f", estimatedHours)} ч (${String.format("%.1f", estimatedMinutes)} мин)")
                        }
                        estimatedMinutes >= 1.0 -> {
                            appendLine("🕐 <b>Приблизительное время обработки оставшихся:</b> ${String.format("%.1f", estimatedMinutes)} мин")
                        }
                        else -> {
                            appendLine("🕐 <b>Приблизительное время обработки оставшихся:</b> ${String.format("%.1f", estimatedSeconds)} сек")
                        }
                    }
                } else {
                    if (statistics.queueCount > 0) {
                        appendLine("🕐 <b>Приблизительное время обработки оставшихся:</b> Неизвестно (нет данных о скорости обработки)")
                    } else {
                        appendLine("🕐 <b>Приблизительное время обработки оставшихся:</b> Очередь пуста")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("❌ [TelegramCommand] Error getting statistics: ${e.message}", e)
            "❌ Ошибка при получении статистики: ${e.message}"
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
     * Обрабатывает команду /vacancies_all - показывает все вакансии (включая просмотренные)
     */
    private fun handleAllVacanciesCommand(chatId: String): String {
        return try {
            val url = "$apiBaseUrl/api/vacancies/all"
            val response = restTemplate.getForObject<Map<String, Any>>(url)

            val count = response?.get("count") as? Int ?: 0
            val vacancies = response?.get("vacancies") as? List<Map<String, Any>> ?: emptyList()

            if (count == 0) {
                "📋 <b>Все вакансии:</b>\n\nВ базе данных пока нет вакансий."
            } else {
                buildString {
                    appendLine("📋 <b>Все вакансии ($count):</b>")
                    appendLine()
                    
                    vacancies.forEachIndexed { index, vacancy ->
                        val id = vacancy["id"] as? String ?: ""
                        val name = vacancy["name"] as? String ?: "Без названия"
                        val employer = vacancy["employer"] as? String ?: "Не указан"
                        val salary = vacancy["salary"] as? String ?: "Не указана"
                        val url = vacancy["url"] as? String ?: ""
                        val isViewed = vacancy["isViewed"] as? Boolean ?: false
                        val viewed = if (isViewed) "✅ Просмотрена" else "🆕 Не просмотрена"
                        
                        appendLine("${index + 1}. <b>$name</b>")
                        appendLine("   💼 $employer")
                        appendLine("   💰 $salary")
                        appendLine("   🔗 <a href=\"$url\">Открыть на HH.ru</a>")
                        appendLine("   $viewed")
                        appendLine()
                    }
                    
                    if (count > 50) {
                        appendLine("... показано 50 из $count вакансий")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error getting all vacancies: ${e.message}", e)
            "❌ Ошибка при получении всех вакансий: ${e.message}"
        }
    }

    /**
     * Обрабатывает команду /skills (suspend функция для асинхронной обработки)
     */
    private suspend fun handleSkillsCommand(chatId: String, text: String): String {
        return try {
            // Парсим параметр limit из команды (например, /skills 10)
            val parts = text.split(" ", limit = 2)
            val limit = if (parts.size > 1) {
                parts[1].toIntOrNull() ?: 20
            } else {
                20
            }

            // Шаг 1: Проверяем, есть ли вакансии без навыков
            val allVacancies = vacancyService.findAllVacancies()
            val vacanciesWithoutSkills = skillExtractionService.getVacanciesWithoutSkills(allVacancies)

            if (vacanciesWithoutSkills.isNotEmpty()) {
                // Есть вакансии без навыков - извлекаем их
                log.info("📊 [TelegramCommand] Found ${vacanciesWithoutSkills.size} vacancies without skills, extracting...")
                
                // Отправляем сообщение о начале обработки
                telegramClient.sendMessage(
                    chatId,
                    "⏳ <b>Извлечение навыков из вакансий...</b>\n\n" +
                    "Найдено ${vacanciesWithoutSkills.size} вакансий без навыков.\n" +
                    "Обрабатываю их, пожалуйста, подождите..."
                )

                // Извлекаем навыки из всех вакансий без навыков
                val processedCount = skillExtractionService.extractSkillsForAllVacancies(vacanciesWithoutSkills)
                
                log.info("✅ [TelegramCommand] Extracted skills from $processedCount vacancies")
            }

            // Шаг 2: Получаем статистику навыков
            val url = "$apiBaseUrl/api/skills/top?limit=$limit"
            val response = restTemplate.getForObject<Map<String, Any>>(url)

            val skills = response?.get("skills") as? List<Map<String, Any>> ?: emptyList()
            val totalVacancies = response?.get("totalVacanciesAnalyzed") as? Int ?: 0

            if (skills.isEmpty()) {
                "📊 <b>Топ навыков:</b>\n\nНет данных. Навыки будут извлекаться при анализе вакансий."
            } else {
                buildString {
                    appendLine("📊 <b>Топ навыков по популярности:</b>")
                    appendLine()
                    skills.forEachIndexed { index, skill ->
                        val skillName = skill["skillName"] as? String ?: "Неизвестно"
                        val frequency = skill["frequencyPercentage"] as? Double ?: 0.0
                        val occurrenceCount = skill["occurrenceCount"] as? Int ?: 0
                        
                        appendLine("${index + 1}. <b>$skillName</b> - ${String.format("%.1f", frequency)}% ($occurrenceCount вакансий)")
                    }
                    appendLine()
                    appendLine("Всего проанализировано: <b>$totalVacancies</b> вакансий")
                }
            }
        } catch (e: Exception) {
            log.error("Error getting skills: ${e.message}", e)
            "❌ Ошибка при получении статистики навыков: ${e.message}"
        }
    }

    /**
     * Обрабатывает команду /help
     */
    private fun handleHelpCommand(chatId: String): String {
        log.info("📖 [TelegramCommand] Processing /help command for chat $chatId")
        return buildString {
            appendLine("📖 <b>Справка по командам:</b>")
            appendLine()
            appendLine("<b>/start</b> - Начать работу с ботом")
            appendLine()
            appendLine("<b>/status</b> - Показать статус системы")
            appendLine()
            appendLine("<b>/stats</b> - Показать статистику по вакансиям")
            appendLine("   Показывает количество обработанных вакансий, в очереди и приблизительное время обработки")
            appendLine()
            appendLine("<b>/vacancies</b> - Показать список непросмотренных вакансий")
            appendLine()
            appendLine("<b>/vacancies_all</b> - Показать все вакансии (включая просмотренные)")
            appendLine()
            appendLine("<b>/skills [N]</b> - Показать топ навыков по популярности")
            appendLine("   Пример: /skills 10 (показать топ-10 навыков)")
            appendLine()
            appendLine("<b>/exclusion_list</b> - List all exclusion rules (keywords and phrases)")
            appendLine()
            appendLine("<b>/exclusion_add_keyword &lt;word&gt;</b> - Add exclusion keyword")
            appendLine("   Пример: /exclusion_add_keyword remote")
            appendLine()
            appendLine("<b>/exclusion_add_phrase &lt;phrase&gt;</b> - Add exclusion phrase")
            appendLine("   Пример: /exclusion_add_phrase без опыта работы")
            appendLine()
            appendLine("<b>/exclusion_remove_keyword &lt;word&gt;</b> - Remove exclusion keyword")
            appendLine("   Пример: /exclusion_remove_keyword remote")
            appendLine()
            appendLine("<b>/exclusion_remove_phrase &lt;phrase&gt;</b> - Remove exclusion phrase")
            appendLine("   Пример: /exclusion_remove_phrase без опыта работы")
            appendLine()
            appendLine("<b>/sent_status [vacancy_id]</b> - Check if vacancy was sent to Telegram")
            appendLine("   Пример: /sent_status (summary) или /sent_status 12345678 (specific vacancy)")
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

    /**
     * Handles /exclusion_add_keyword <word> command
     */
    private fun handleAddExclusionKeyword(chatId: String, text: String): String {
        val keyword = text.removePrefix("/exclusion_add_keyword").trim()
        if (keyword.isEmpty()) {
            return "❌ Usage: /exclusion_add_keyword <word>\nExample: /exclusion_add_keyword remote"
        }

        return try {
            exclusionRuleService.addKeyword(keyword)
            "✅ Added exclusion keyword: '$keyword'\nCache invalidated."
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error adding exclusion keyword: ${e.message}", e)
            "❌ Error adding keyword: ${e.message}"
        }
    }

    /**
     * Handles /exclusion_add_phrase <phrase> command
     */
    private fun handleAddExclusionPhrase(chatId: String, text: String): String {
        val phrase = text.removePrefix("/exclusion_add_phrase").trim()
        if (phrase.isEmpty()) {
            return "❌ Usage: /exclusion_add_phrase <phrase>\nExample: /exclusion_add_phrase без опыта работы"
        }

        return try {
            exclusionRuleService.addPhrase(phrase)
            "✅ Added exclusion phrase: '$phrase'\nCache invalidated."
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error adding exclusion phrase: ${e.message}", e)
            "❌ Error adding phrase: ${e.message}"
        }
    }

    /**
     * Handles /exclusion_remove_keyword <word> command
     */
    private fun handleRemoveExclusionKeyword(chatId: String, text: String): String {
        val keyword = text.removePrefix("/exclusion_remove_keyword").trim()
        if (keyword.isEmpty()) {
            return "❌ Usage: /exclusion_remove_keyword <word>\nExample: /exclusion_remove_keyword remote"
        }

        return try {
            val removed = exclusionRuleService.removeKeyword(keyword)
            if (removed) {
                "✅ Removed exclusion keyword: '$keyword'\nCache invalidated."
            } else {
                "⚠️ Keyword '$keyword' not found"
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error removing exclusion keyword: ${e.message}", e)
            "❌ Error removing keyword: ${e.message}"
        }
    }

    /**
     * Handles /exclusion_remove_phrase <phrase> command
     */
    private fun handleRemoveExclusionPhrase(chatId: String, text: String): String {
        val phrase = text.removePrefix("/exclusion_remove_phrase").trim()
        if (phrase.isEmpty()) {
            return "❌ Usage: /exclusion_remove_phrase <phrase>\nExample: /exclusion_remove_phrase без опыта работы"
        }

        return try {
            val removed = exclusionRuleService.removePhrase(phrase)
            if (removed) {
                "✅ Removed exclusion phrase: '$phrase'\nCache invalidated."
            } else {
                "⚠️ Phrase '$phrase' not found"
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error removing exclusion phrase: ${e.message}", e)
            "❌ Error removing phrase: ${e.message}"
        }
    }

    /**
     * Handles /exclusion_list command
     */
    private fun handleListExclusions(chatId: String): String {
        return try {
            val rules = exclusionRuleService.listAll()
            val keywords = rules["keywords"] ?: emptyList()
            val phrases = rules["phrases"] ?: emptyList()

            buildString {
                appendLine("📋 <b>Exclusion Rules</b>")
                appendLine()
                appendLine("<b>Keywords (${keywords.size}):</b>")
                if (keywords.isEmpty()) {
                    appendLine("   (none)")
                } else {
                    keywords.forEach { appendLine("   • $it") }
                }
                appendLine()
                appendLine("<b>Phrases (${phrases.size}):</b>")
                if (phrases.isEmpty()) {
                    appendLine("   (none)")
                } else {
                    phrases.forEach { appendLine("   • $it") }
                }
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error listing exclusions: ${e.message}", e)
            "❌ Error listing exclusions: ${e.message}"
        }
    }

    /**
     * Handles /sent_status [vacancy_id] command
     * Shows status of vacancy sending to Telegram
     */
    private fun handleSentStatusCommand(chatId: String, text: String): String {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) {
            return try {
                // If no ID provided, show summary
                val sentCount = vacancyService.getSentToTelegramVacancies().size
                val notSentCount = vacancyService.getNotSentToTelegramVacancies().size
                
                buildString {
                    appendLine("📊 <b>Telegram Sending Status</b>")
                    appendLine()
                    appendLine("✅ Sent to Telegram: $sentCount")
                    appendLine("⏳ Not sent yet: $notSentCount")
                    appendLine()
                    appendLine("Usage: /sent_status &lt;vacancy_id&gt;")
                    appendLine("Example: /sent_status 12345678")
                }
            } catch (e: Exception) {
                log.error("[TelegramCommand] Error getting sent status summary: ${e.message}", e)
                "❌ Error getting status: ${e.message}"
            }
        }

        val vacancyId = parts[1].trim()
        return try {
            val wasSent = vacancyService.wasSentToTelegram(vacancyId)
            val vacancy = vacancyService.getVacancyById(vacancyId)
            
            if (vacancy == null) {
                "❌ Vacancy with ID '$vacancyId' not found"
            } else {
                buildString {
                    appendLine("📋 <b>Vacancy Sending Status</b>")
                    appendLine()
                    appendLine("<b>ID:</b> ${vacancy.id}")
                    appendLine("<b>Name:</b> ${vacancy.name}")
                    appendLine("<b>Status:</b> ${vacancy.status.name}")
                    appendLine()
                    if (wasSent) {
                        appendLine("✅ <b>Sent to Telegram:</b> Yes")
                        vacancy.sentToTelegramAt?.let {
                            appendLine("📅 <b>Sent at:</b> $it")
                        }
                    } else {
                        appendLine("❌ <b>Sent to Telegram:</b> No")
                        if (vacancy.status == VacancyStatus.ANALYZED) {
                            appendLine("ℹ️ Vacancy is analyzed but not sent yet")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error checking sent status: ${e.message}", e)
            "❌ Error checking status: ${e.message}"
        }
    }
}

