package com.hhassistant.service.telegram

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.AppConstants
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.dto.ApiResponse
import com.hhassistant.dto.VacancyListResponse
import com.hhassistant.web.TopSkillsResponse
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import com.hhassistant.service.skill.SkillExtractionService
import com.hhassistant.service.skill.SkillStatistics
import com.hhassistant.service.skill.SkillStatisticsService
import com.hhassistant.service.vacancy.VacancyService
import com.hhassistant.service.exclusion.ExclusionRuleService
import com.hhassistant.service.exclusion.ExclusionKeywordService
import com.hhassistant.service.util.AnalysisTimeService

/**
 * Обработчик команд Telegram бота.
 * Обрабатывает команды пользователей, вызывая соответствующие REST API endpoints приложения.
 */
@Service
class TelegramCommandHandler(
    private val telegramClient: TelegramClient,
    @Qualifier("internalApiWebClient") private val webClient: WebClient,
    private val skillExtractionService: SkillExtractionService,
    private val skillStatisticsService: SkillStatisticsService,
    private val vacancyService: VacancyService,
    private val exclusionRuleService: ExclusionRuleService,
    private val exclusionKeywordService: ExclusionKeywordService,
    private val analysisTimeService: AnalysisTimeService,
    @Value("\${app.api.base-url:http://localhost:8080}") private val apiBaseUrl: String,
) {
    private val log = KotlinLogging.logger {}

    companion object {
        private const val TELEGRAM_MESSAGE_MAX_LENGTH = AppConstants.TextLimits.TELEGRAM_MESSAGE_MAX_LENGTH
        private const val VACANCY_ID_PATTERN = "^[0-9]+$"
        private const val DEFAULT_SKILLS_LIMIT = 20
        private const val MAX_SKILLS_LIMIT = 100
        private const val MAX_VACANCIES_TO_SHOW = 10
        private const val MAX_ALL_VACANCIES_TO_SHOW = 50
        private const val MAX_EXCLUSION_PARAM_LENGTH = 200
    }

    /**
     * Обрабатывает команду или сообщение от пользователя.
     *
     * @param chatId ID чата пользователя
     * @param text Текст команды или сообщения
     */
    suspend fun handleCommand(chatId: String, text: String) {
        log.info("📱 [TelegramCommand] Handling command from chat $chatId: $text")

        try {
            val response = when {
                text == "/start" -> handleStartCommand(chatId)
                text == "/status" -> handleStatusCommand(chatId)
                text == "/stats" -> handleStatsCommand(chatId)
                text == "/vacancies_all" -> handleAllVacanciesCommand(chatId)
                text.startsWith("/vacancies ") -> handleVacanciesCommand(chatId, text)
                text == "/vacancies" -> handleVacanciesCommand(chatId, text)
                text.startsWith("/skills ") -> handleSkillsCommand(chatId, text)
                text == "/skills" -> handleSkillsCommand(chatId, text)
                text.startsWith("/skills_now ") -> handleSkillsNowCommand(chatId, text)
                text == "/skills_now" -> handleSkillsNowCommand(chatId, text)
                text == "/extract-relevant-skills" -> handleExtractRelevantSkillsCommand(chatId)
                text.startsWith("/exclusion_add_keyword ") -> handleAddExclusionKeyword(chatId, text)
                text.startsWith("/exclusion_add_phrase ") -> handleAddExclusionPhrase(chatId, text)
                text.startsWith("/exclusion_remove_keyword ") -> handleRemoveExclusionKeyword(chatId, text)
                text.startsWith("/exclusion_remove_phrase ") -> handleRemoveExclusionPhrase(chatId, text)
                text == "/exclusion_list" -> handleListExclusions(chatId)
                text.startsWith("/sent_status ") -> handleSentStatusCommand(chatId, text)
                text == "/sent_status" -> handleSentStatusCommand(chatId, text)
                text == "/help" -> handleHelpCommand(chatId)
                text.matches(Regex("/mark-applied-\\d+")) -> handleMarkAppliedCommand(chatId, text)
                text.matches(Regex("/mark-not-interested-\\d+")) -> handleMarkNotInterestedCommand(chatId, text)
                else -> {
                    log.debug("[TelegramCommand] Unknown command: $text")
                    "❓ Неизвестная команда. Используйте /help для списка доступных команд."
                }
            }

            sendMessageSafely(chatId, response)
        } catch (e: Exception) {
            log.error("❌ [TelegramCommand] Failed to handle command: ${e.message}", e)
            sendMessageSafely(chatId, "❌ Ошибка при обработке команды: ${e.message ?: "Неизвестная ошибка"}")
        }
    }

    /**
     * Отправляет сообщение с проверкой длины и разбиением на части при необходимости
     */
    private suspend fun sendMessageSafely(chatId: String, message: String) {
        if (message.length <= TELEGRAM_MESSAGE_MAX_LENGTH) {
            telegramClient.sendMessage(chatId, message)
        } else {
            // Разбиваем сообщение на части
            val parts = message.chunked(TELEGRAM_MESSAGE_MAX_LENGTH - 100) // Оставляем запас
            parts.forEachIndexed { index, part ->
                val partMessage = if (parts.size > 1) {
                    "📄 Часть ${index + 1} из ${parts.size}\n\n$part"
                } else {
                    part
                }
                telegramClient.sendMessage(chatId, partMessage)
            }
        }
    }

    /**
     * Валидирует ID вакансии
     */
    private fun validateVacancyId(vacancyId: String): Boolean {
        return vacancyId.matches(Regex(VACANCY_ID_PATTERN))
    }

    /**
     * Экранирует HTML-специальные символы для безопасной вставки в HTML сообщения
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
            appendLine("   /skills [N] - Топ навыков (с обработкой вакансий)")
            appendLine("   /skills_now [N] - Текущий топ навыков (без ожидания)")
            appendLine("   /help - Справка")
            appendLine()
            appendLine("💡 Используйте /help для подробной информации.")
        }
    }

    /**
     * Обрабатывает команду /status
     */
    private fun handleStatusCommand(chatId: String): String {
        return buildString {
            appendLine("📊 <b>Статус системы:</b>")
            appendLine()
            appendLine("✅ Бот работает")
            appendLine("✅ REST API доступен")
            appendLine()
            appendLine("💡 Используйте /vacancies для просмотра вакансий.")
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
            "❌ Ошибка при получении статистики: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /vacancies
     */
    private suspend fun handleVacanciesCommand(chatId: String, text: String): String {
        return try {
            val url = "$apiBaseUrl/api/vacancies/unviewed"
            val response = webClient.get()
                .uri(url)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono<String>().map { body ->
                        RuntimeException("API error: ${response.statusCode()} - $body")
                    }
                }
                .bodyToMono<VacancyListResponse>()
                .awaitSingle()

            if (response.count == 0) {
                "📋 <b>Непросмотренные вакансии:</b>\n\nНет новых вакансий."
            } else {
                buildString {
                    appendLine("📋 <b>Непросмотренные вакансии (${response.count}):</b>")
                    appendLine()
                    response.vacancies.take(MAX_VACANCIES_TO_SHOW).forEachIndexed { index, vacancy ->
                        appendLine("${index + 1}. <b>${escapeHtml(vacancy.name)}</b>")
                        appendLine("   💼 ${escapeHtml(vacancy.employer)}")
                        appendLine("   💰 ${escapeHtml(vacancy.salary)}")
                        appendLine("   🔗 <a href=\"${vacancy.url}\">Открыть на HH.ru</a>")
                        appendLine("   ✅ /mark-applied-${vacancy.id} | ❌ /mark-not-interested-${vacancy.id}")
                        appendLine()
                    }
                    if (response.count > MAX_VACANCIES_TO_SHOW) {
                        appendLine("... и еще ${response.count - MAX_VACANCIES_TO_SHOW} вакансий")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error getting vacancies: ${e.message}", e)
            "❌ Ошибка при получении вакансий: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /vacancies_all - показывает все вакансии (включая просмотренные)
     */
    private suspend fun handleAllVacanciesCommand(chatId: String): String {
        return try {
            val url = "$apiBaseUrl/api/vacancies/all"
            val response = webClient.get()
                .uri(url)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono<String>().map { body ->
                        RuntimeException("API error: ${response.statusCode()} - $body")
                    }
                }
                .bodyToMono<VacancyListResponse>()
                .awaitSingle()

            if (response.count == 0) {
                "📋 <b>Все вакансии:</b>\n\nВ базе данных пока нет вакансий."
            } else {
                buildString {
                    appendLine("📋 <b>Все вакансии (${response.count}):</b>")
                    appendLine()

                    response.vacancies.take(MAX_ALL_VACANCIES_TO_SHOW).forEachIndexed { index, vacancy ->
                        val viewed = if (vacancy.isViewed == true) "✅ Просмотрена" else "🆕 Не просмотрена"

                        appendLine("${index + 1}. <b>${escapeHtml(vacancy.name)}</b>")
                        appendLine("   💼 ${escapeHtml(vacancy.employer)}")
                        appendLine("   💰 ${escapeHtml(vacancy.salary)}")
                        appendLine("   🔗 <a href=\"${vacancy.url}\">Открыть на HH.ru</a>")
                        appendLine("   $viewed")
                        appendLine()
                    }

                    if (response.count > MAX_ALL_VACANCIES_TO_SHOW) {
                        appendLine("... показано $MAX_ALL_VACANCIES_TO_SHOW из ${response.count} вакансий")
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error getting all vacancies: ${e.message}", e)
            "❌ Ошибка при получении всех вакансий: ${e.message ?: "Неизвестная ошибка"}"
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
                parts[1].toIntOrNull()?.takeIf { it in 1..MAX_SKILLS_LIMIT } ?: DEFAULT_SKILLS_LIMIT
            } else {
                DEFAULT_SKILLS_LIMIT
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
                        "Обрабатываю их, пожалуйста, подождите...",
                )

                // Извлекаем навыки из всех вакансий без навыков
                val processedCount = skillExtractionService.extractSkillsForAllVacancies(vacanciesWithoutSkills)

                log.info("✅ [TelegramCommand] Extracted skills from $processedCount vacancies")
            }

            // Шаг 2: Получаем статистику навыков
            val url = "$apiBaseUrl/api/skills/top?limit=$limit"
            val response = webClient.get()
                .uri(url)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono<String>().map { body ->
                        RuntimeException("API error: ${response.statusCode()} - $body")
                    }
                }
                .bodyToMono<TopSkillsResponse>()
                .awaitSingle()

            if (response.skills.isEmpty()) {
                "📊 <b>Топ навыков:</b>\n\nНет данных. Навыки будут извлекаться при анализе вакансий."
            } else {
                buildString {
                    appendLine("📊 <b>Топ навыков по популярности:</b>")
                    appendLine()
                    response.skills.forEachIndexed { index, skill: SkillStatistics ->
                        appendLine("${index + 1}. <b>${escapeHtml(skill.skillName)}</b> - ${String.format("%.1f", skill.frequencyPercentage)}% (${skill.occurrenceCount} вакансий)")
                    }
                    appendLine()
                    appendLine("Всего проанализировано: <b>${response.totalVacanciesAnalyzed}</b> вакансий")
                }
            }
        } catch (e: Exception) {
            log.error("Error getting skills: ${e.message}", e)
            "❌ Ошибка при получении статистики навыков: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /skills_now - показывает текущую статистику навыков без ожидания обработки
     */
    private fun handleSkillsNowCommand(chatId: String, text: String): String {
        return try {
            // Парсим параметр limit из команды (например, /skills_now 10)
            val parts = text.split(" ", limit = 2)
            val limit = if (parts.size > 1) {
                parts[1].toIntOrNull()?.takeIf { it in 1..MAX_SKILLS_LIMIT } ?: DEFAULT_SKILLS_LIMIT
            } else {
                DEFAULT_SKILLS_LIMIT
            }

            log.info("📊 [TelegramCommand] Processing /skills_now command for chat $chatId with limit $limit")

            // Получаем текущую статистику навыков напрямую из базы
            val skillsStatistics = skillStatisticsService.getTopSkills(limit)
            val totalSkillsCount = skillStatisticsService.getTotalSkillsCount()
            val totalAnalyzedVacancies = skillStatisticsService.getTotalAnalyzedVacancies()

            if (skillsStatistics.isEmpty()) {
                buildString {
                    appendLine("📊 <b>Текущая статистика навыков:</b>")
                    appendLine()
                    appendLine("📋 <b>Всего уникальных навыков:</b> $totalSkillsCount")
                    appendLine("📈 <b>Проанализировано вакансий:</b> $totalAnalyzedVacancies")
                    appendLine()
                    appendLine("❌ <b>Нет данных для отображения топа навыков</b>")
                    appendLine()
                    appendLine("💡 <i>Навыки будут извлекаться при анализе вакансий.</i>")
                    appendLine("Используйте /skills для извлечения навыков из необработанных вакансий.")
                }
            } else {
                buildString {
                    appendLine("📊 <b>Текущий топ навыков по популярности:</b>")
                    appendLine()
                    skillsStatistics.forEachIndexed { index, skill ->
                        appendLine("${index + 1}. <b>${escapeHtml(skill.skillName)}</b> - ${String.format("%.1f", skill.frequencyPercentage)}% (${skill.occurrenceCount} вакансий)")
                    }
                    appendLine()
                    appendLine("📋 <b>Всего уникальных навыков:</b> $totalSkillsCount")
                    appendLine("📈 <b>Проанализировано вакансий:</b> $totalAnalyzedVacancies")
                    appendLine()
                    appendLine("💡 <i>Данные показаны на текущий момент без дополнительной обработки.</i>")
                }
            }
        } catch (e: Exception) {
            log.error("Error getting current skills statistics: ${e.message}", e)
            "❌ Ошибка при получении текущей статистики навыков: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /extract-relevant-skills
     * Извлекает навыки из релевантных вакансий, которые еще не имеют навыков.
     */
    private suspend fun handleExtractRelevantSkillsCommand(chatId: String): String {
        return try {
            log.info("🔍 [TelegramCommand] Processing /extract-relevant-skills command for chat $chatId")

            // Получаем список релевантных вакансий без навыков
            val relevantVacancies = skillExtractionService.getRelevantVacanciesWithoutSkills()

            if (relevantVacancies.isEmpty()) {
                log.info("ℹ️ [TelegramCommand] No relevant vacancies without skills found")
                return "✅ Все релевантные вакансии уже имеют извлеченные навыки.\n\nНет вакансий для обработки."
            }

            log.info("📊 [TelegramCommand] Found ${relevantVacancies.size} relevant vacancies without skills, extracting...")

            // Отправляем сообщение о начале обработки
            telegramClient.sendMessage(
                chatId,
                "⏳ <b>Извлечение навыков из релевантных вакансий...</b>\n\n" +
                    "Найдено ${relevantVacancies.size} релевантных вакансий без навыков.\n" +
                    "Обрабатываю их, пожалуйста, подождите...",
            )

            // Извлекаем навыки из всех релевантных вакансий без навыков
            val processedCount = skillExtractionService.extractSkillsForRelevantVacancies()

            log.info("✅ [TelegramCommand] Extracted skills from $processedCount relevant vacancies")

            buildString {
                appendLine("✅ <b>Извлечение навыков завершено</b>")
                appendLine()
                appendLine("Обработано вакансий: <b>$processedCount</b>")
                appendLine("Найдено релевантных вакансий без навыков: <b>${relevantVacancies.size}</b>")
                if (processedCount < relevantVacancies.size) {
                    appendLine()
                    appendLine("⚠️ Некоторые вакансии не были обработаны из-за ошибок.")
                }
            }
        } catch (e: Exception) {
            log.error("❌ [TelegramCommand] Error extracting skills for relevant vacancies: ${e.message}", e)
            "❌ Ошибка при извлечении навыков из релевантных вакансий: ${e.message ?: "Неизвестная ошибка"}"
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
            appendLine("   ⚠️ Сначала обрабатывает все вакансии без навыков, может занять время")
            appendLine()
            appendLine("<b>/skills_now [N]</b> - Показать текущий топ навыков")
            appendLine("   Пример: /skills_now 15 (показать топ-15 навыков)")
            appendLine("   ⚡ Показывает данные сразу, без дополнительной обработки")
            appendLine()
            appendLine("<b>/extract-relevant-skills</b> - Извлечь навыки из релевантных вакансий без навыков")
            appendLine("   Находит релевантные вакансии, для которых еще не извлечены навыки, и извлекает их")
            appendLine()
            appendLine("<b>/exclusion_list</b> - Показать все правила исключения (ключевые слова и фразы)")
            appendLine()
            appendLine("<b>/exclusion_add_keyword &lt;слово&gt;</b> - Добавить ключевое слово для исключения")
            appendLine("   Пример: /exclusion_add_keyword remote")
            appendLine()
            appendLine("<b>/exclusion_add_phrase &lt;фраза&gt;</b> - Добавить фразу для исключения")
            appendLine("   Пример: /exclusion_add_phrase без опыта работы")
            appendLine()
            appendLine("<b>/exclusion_remove_keyword &lt;слово&gt;</b> - Удалить ключевое слово из исключений")
            appendLine("   Пример: /exclusion_remove_keyword remote")
            appendLine()
            appendLine("<b>/exclusion_remove_phrase &lt;фраза&gt;</b> - Удалить фразу из исключений")
            appendLine("   Пример: /exclusion_remove_phrase без опыта работы")
            appendLine()
            appendLine("<b>/sent_status [vacancy_id]</b> - Проверить, была ли вакансия отправлена в Telegram")
            appendLine("   Пример: /sent_status (сводка) или /sent_status 12345678 (конкретная вакансия)")
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
    private suspend fun handleMarkAppliedCommand(chatId: String, text: String): String {
        val vacancyId = text.removePrefix("/mark-applied-")
        if (!validateVacancyId(vacancyId)) {
            return "❌ Неверный формат ID вакансии"
        }

        return try {
            val url = "$apiBaseUrl/api/vacancies/$vacancyId/mark-applied"
            val response = webClient.post()
                .uri(url)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono<String>().map { body ->
                        RuntimeException("API error: ${response.statusCode()} - $body")
                    }
                }
                .bodyToMono<ApiResponse>()
                .awaitSingle()

            if (response.success) {
                "✅ Вакансия отмечена как \"откликнулся\""
            } else {
                val message = response.message ?: "Ошибка"
                "❌ $message"
            }
        } catch (e: Exception) {
            log.error("Error marking vacancy as applied: ${e.message}", e)
            "❌ Ошибка при обновлении статуса: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /mark-not-interested-{id}
     */
    private suspend fun handleMarkNotInterestedCommand(chatId: String, text: String): String {
        val vacancyId = text.removePrefix("/mark-not-interested-")
        if (!validateVacancyId(vacancyId)) {
            return "❌ Неверный формат ID вакансии"
        }

        return try {
            val url = "$apiBaseUrl/api/vacancies/$vacancyId/mark-not-interested"
            val response = webClient.post()
                .uri(url)
                .retrieve()
                .onStatus({ it.isError }) { response ->
                    response.bodyToMono<String>().map { body ->
                        RuntimeException("API error: ${response.statusCode()} - $body")
                    }
                }
                .bodyToMono<ApiResponse>()
                .awaitSingle()

            if (response.success) {
                "✅ Вакансия отмечена как \"неинтересная\""
            } else {
                val message = response.message ?: "Ошибка"
                "❌ $message"
            }
        } catch (e: Exception) {
            log.error("Error marking vacancy as not interested: ${e.message}", e)
            "❌ Ошибка при обновлении статуса: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команды добавления/удаления exclusion правил
     */
    private fun handleExclusionCommand(
        chatId: String,
        text: String,
        commandPrefix: String,
        isAdd: Boolean,
        isKeyword: Boolean,
    ): String {
        val param = text.removePrefix(commandPrefix).trim()
        if (param.isEmpty()) {
            val type = if (isKeyword) "слово" else "фраза"
            val example = if (isKeyword) "remote" else "без опыта работы"
            return "❌ Использование: $commandPrefix &lt;$type&gt;\nПример: $commandPrefix $example"
        }
        if (param.length > MAX_EXCLUSION_PARAM_LENGTH) {
            return "❌ Слишком длинное значение (максимум $MAX_EXCLUSION_PARAM_LENGTH символов)"
        }

        return try {
            if (isAdd) {
                if (isKeyword) {
                    val added = exclusionKeywordService.addKeyword(param)
                    if (added) {
                        "✅ Добавлено ключевое слово для исключения: '$param'\nВсего слов-блокеров: ${exclusionKeywordService.getKeywordsCount()}"
                    } else {
                        "⚠️ Ключевое слово '$param' уже существует или содержит пробелы (используйте /exclusion_add_phrase для фраз)"
                    }
                } else {
                    exclusionRuleService.addPhrase(param)
                    "✅ Добавлена фраза для исключения: '$param'\n(Фразы используются только для LLM анализа)"
                }
            } else {
                val removed = if (isKeyword) {
                    exclusionKeywordService.removeKeyword(param)
                } else {
                    exclusionRuleService.removePhrase(param)
                }
                if (removed) {
                    val type = if (isKeyword) "ключевое слово" else "фраза"
                    val countInfo = if (isKeyword) "\nВсего слов-блокеров: ${exclusionKeywordService.getKeywordsCount()}" else ""
                    "✅ Удалено $type из исключений: '$param'$countInfo"
                } else {
                    val type = if (isKeyword) "ключевое слово" else "фраза"
                    "⚠️ $type '$param' не найдено"
                }
            }
        } catch (e: Exception) {
            val action = if (isAdd) "добавлении" else "удалении"
            val type = if (isKeyword) "ключевого слова" else "фразы"
            log.error("[TelegramCommand] Error $action exclusion $type: ${e.message}", e)
            "❌ Ошибка при $action $type: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /exclusion_add_keyword <word>
     */
    private fun handleAddExclusionKeyword(chatId: String, text: String): String {
        return handleExclusionCommand(chatId, text, "/exclusion_add_keyword ", isAdd = true, isKeyword = true)
    }

    /**
     * Обрабатывает команду /exclusion_add_phrase <phrase>
     */
    private fun handleAddExclusionPhrase(chatId: String, text: String): String {
        return handleExclusionCommand(chatId, text, "/exclusion_add_phrase ", isAdd = true, isKeyword = false)
    }

    /**
     * Обрабатывает команду /exclusion_remove_keyword <word>
     */
    private fun handleRemoveExclusionKeyword(chatId: String, text: String): String {
        return handleExclusionCommand(chatId, text, "/exclusion_remove_keyword ", isAdd = false, isKeyword = true)
    }

    /**
     * Обрабатывает команду /exclusion_remove_phrase <phrase>
     */
    private fun handleRemoveExclusionPhrase(chatId: String, text: String): String {
        return handleExclusionCommand(chatId, text, "/exclusion_remove_phrase ", isAdd = false, isKeyword = false)
    }

    /**
     * Обрабатывает команду /exclusion_list
     */
    private fun handleListExclusions(chatId: String): String {
        return try {
            val keywords = exclusionKeywordService.getAllKeywords().sorted()
            val rules = exclusionRuleService.listAll()
            val phrases = rules["phrases"] ?: emptyList<String>()

            buildString {
                appendLine("📋 <b>Правила исключения</b>")
                appendLine()
                appendLine("<b>Слова-блокеры (${keywords.size}):</b>")
                appendLine("<i>Используются для первичной валидации в названии вакансии</i>")
                if (keywords.isEmpty()) {
                    appendLine("   (нет)")
                } else {
                    keywords.forEach { appendLine("   • $it") }
                }
                appendLine()
                appendLine("<b>Фразы (${phrases.size}):</b>")
                if (phrases.isEmpty()) {
                    appendLine("   (нет)")
                } else {
                    phrases.forEach { appendLine("   • $it") }
                }
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error listing exclusions: ${e.message}", e)
            "❌ Ошибка при получении списка исключений: ${e.message ?: "Неизвестная ошибка"}"
        }
    }

    /**
     * Обрабатывает команду /sent_status [vacancy_id]
     * Показывает статус отправки вакансии в Telegram
     */
    private suspend fun handleSentStatusCommand(chatId: String, text: String): String {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) {
            return try {
                // Если ID не указан, показываем сводку
                val sentCount = vacancyService.getSentToTelegramVacancies().size
                val notSentCount = vacancyService.getNotSentToTelegramVacancies().size

                buildString {
                    appendLine("📊 <b>Статус отправки в Telegram</b>")
                    appendLine()
                    appendLine("✅ Отправлено в Telegram: $sentCount")
                    appendLine("⏳ Еще не отправлено: $notSentCount")
                    appendLine()
                    appendLine("Использование: /sent_status &lt;vacancy_id&gt;")
                    appendLine("Пример: /sent_status 12345678")
                }
            } catch (e: Exception) {
                log.error("[TelegramCommand] Error getting sent status summary: ${e.message}", e)
                "❌ Ошибка при получении статуса: ${e.message ?: "Неизвестная ошибка"}"
            }
        }

        val vacancyId = parts[1].trim()
        if (!validateVacancyId(vacancyId)) {
            return "❌ Неверный формат ID вакансии"
        }

        return try {
            val wasSent = vacancyService.wasSentToTelegram(vacancyId)
            val vacancy = vacancyService.getVacancyById(vacancyId)

            if (vacancy == null) {
                "❌ Вакансия с ID '$vacancyId' не найдена"
            } else {
                buildString {
                    appendLine("📋 <b>Статус отправки вакансии</b>")
                    appendLine()
                    appendLine("<b>ID:</b> ${vacancy.id}")
                    appendLine("<b>Название:</b> ${escapeHtml(vacancy.name)}")
                    appendLine("<b>Статус:</b> ${vacancy.status.name}")
                    appendLine()
                    if (wasSent) {
                        appendLine("✅ <b>Отправлено в Telegram:</b> Да")
                        vacancy.sentToTelegramAt?.let {
                            appendLine("📅 <b>Отправлено:</b> $it")
                        }
                    } else {
                        appendLine("❌ <b>Отправлено в Telegram:</b> Нет")
                        if (vacancy.status == VacancyStatus.ANALYZED) {
                            appendLine("ℹ️ Вакансия проанализирована, но еще не отправлена")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("[TelegramCommand] Error checking sent status: ${e.message}", e)
            "❌ Ошибка при проверке статуса: ${e.message ?: "Неизвестная ошибка"}"
        }
    }
}