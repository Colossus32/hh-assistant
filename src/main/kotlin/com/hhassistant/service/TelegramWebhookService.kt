package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.client.telegram.dto.Document
import com.hhassistant.client.telegram.dto.Message
import com.hhassistant.client.telegram.dto.Update
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.VacancyService
import com.hhassistant.service.VacancyStatusService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Сервис для обработки входящих сообщений от Telegram
 */
@Service
class TelegramWebhookService(
    private val telegramClient: TelegramClient,
    private val resumeService: ResumeService,
    private val vacancyStatusService: VacancyStatusService,
    private val vacancyRepository: VacancyRepository,
    private val vacancyService: VacancyService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает входящее обновление от Telegram
     */
    fun handleUpdate(update: Update) {
        log.debug("🔄 [Webhook] Processing update ID: ${update.updateId}")
        
        // Обрабатываем callback_query (нажатие на кнопки) - ПРИОРИТЕТНО
        update.callbackQuery?.let { callbackQuery ->
            log.info("🔘 [Webhook] Found callback_query in update ${update.updateId}, processing...")
            handleCallbackQuery(callbackQuery)
            return
        }

        // Если нет callback_query, обрабатываем message
        val message = update.message
        if (message == null) {
            log.warn("⚠️ [Webhook] Update ${update.updateId} has neither callback_query nor message, ignoring")
            return
        }

        log.debug("💬 [Webhook] Processing message in update ${update.updateId}")

        // Обрабатываем документы (PDF резюме)
        message.document?.let { document ->
            log.info("📎 [Webhook] Found document in message, processing...")
            handleDocument(message, document)
            return
        }

        // Обрабатываем текстовые сообщения
        message.text?.let { text ->
            log.info("💬 [Webhook] Found text message: '$text'")
            handleTextMessage(message, text)
            return
        }
        
        log.warn("⚠️ [Webhook] Message in update ${update.updateId} has no document or text, ignoring")
    }

    /**
     * Обрабатывает документ (PDF резюме)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleDocument(message: Message, document: Document) {
        log.info("📎 [Webhook] Received document: ${document.fileName} (${document.fileSize} bytes, type: ${document.mimeType})")

        // Проверяем, что это PDF
        if (document.mimeType != "application/pdf" && !document.fileName?.endsWith(".pdf", ignoreCase = true)!!) {
            runBlocking {
                telegramClient.sendMessage(
                    "❌ Пожалуйста, отправьте резюме в формате PDF.",
                )
            }
            return
        }

        try {
            runBlocking {
                // Отправляем подтверждение получения
                telegramClient.sendMessage(
                    "📥 Получен файл резюме. Обрабатываю...",
                )

                // Получаем информацию о файле
                val fileInfo = telegramClient.getFile(document.fileId)
                log.info("📎 [Webhook] File info: path=${fileInfo.filePath}, size=${fileInfo.fileSize}")

                // Скачиваем файл
                val fileBytes = telegramClient.downloadFile(fileInfo.filePath ?: throw IllegalStateException("File path is null"))
                log.info("📥 [Webhook] Downloaded file: ${fileBytes.size} bytes")

                // Сохраняем резюме
                val savedResume = resumeService.saveResumeFromBytes(
                    pdfBytes = fileBytes,
                    fileName = document.fileName ?: "resume_from_telegram.pdf",
                )

                // Отправляем подтверждение
                val skillsCount = resumeService.getResumeStructure(savedResume)?.skills?.size ?: 0
                telegramClient.sendMessage(
                    """
                    ✅ <b>Резюме успешно загружено!</b>
                    
                    📄 Файл: ${savedResume.fileName}
                    📊 Навыков найдено: $skillsCount
                    📝 Длина текста: ${savedResume.rawText.length} символов
                    
                    Теперь вы будете получать подходящие вакансии!
                    """.trimIndent(),
                )
            }
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error processing document: ${e.message}", e)
            runBlocking {
                telegramClient.sendMessage(
                    "❌ Ошибка при обработке резюме: ${e.message}\n\nПожалуйста, попробуйте отправить файл еще раз.",
                )
            }
        }
    }

    /**
     * Обрабатывает текстовое сообщение
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleTextMessage(message: Message, text: String) {
        log.info("💬 [Webhook] Received text message: '$text' (length: ${text.length})")

        // Извлекаем команду из текста
        // Команда может быть в формате: /command, /command@botname, /command param1 param2
        val normalizedText = text.trim()
        if (!normalizedText.startsWith("/")) {
            log.debug("💬 [Webhook] Text message is not a command (doesn't start with /), ignoring: '$normalizedText'")
            return
        }

        // Извлекаем команду (убираем @botname и параметры)
        val commandPart = normalizedText.split(" ", limit = 2)[0] // Берем первую часть до пробела
        val command = commandPart.split("@", limit = 2)[0].lowercase() // Убираем @botname если есть

        log.info("🔍 [Webhook] Extracted command: '$command' from text: '$normalizedText'")

        // Обрабатываем команды
        when (command) {
            "/start", "/help" -> {
                log.info("✅ [Webhook] Processing /help command")
                runBlocking {
                    try {
                        telegramClient.sendMessage(
                            """
                            👋 <b>Добро пожаловать в HH Assistant!</b>
                            
                            📋 <b>Доступные команды:</b>
                            • Отправьте PDF файл с резюме для начала работы
                            • /status - проверить статус резюме
                            • /vacancies - получить список всех вакансий со ссылками
                            • /vacancies_new - получить список новых вакансий (еще не откликался)
                            
                            После загрузки резюме вы будете автоматически получать подходящие вакансии!
                            """.trimIndent(),
                        )
                        log.info("✅ [Webhook] Successfully sent /help response")
                    } catch (e: Exception) {
                        log.error("❌ [Webhook] Error sending /help response: ${e.message}", e)
                    }
                }
            }
            "/status" -> {
                log.info("✅ [Webhook] Processing /status command")
                runBlocking {
                    try {
                        val hasResume = resumeService.hasActiveResume()
                        if (hasResume) {
                            val resume = resumeService.loadResume()
                            val structure = resumeService.getResumeStructure(resume)
                            telegramClient.sendMessage(
                                """
                                ✅ <b>Резюме загружено</b>
                                
                                📄 Файл: ${resume.fileName}
                                📊 Навыков: ${structure?.skills?.size ?: 0}
                                📝 Длина: ${resume.rawText.length} символов
                                """.trimIndent(),
                            )
                        } else {
                            telegramClient.sendMessage(
                                """
                                ❌ <b>Резюме не загружено</b>
                                
                                Пожалуйста, отправьте PDF файл с резюме для начала работы.
                                """.trimIndent(),
                            )
                        }
                        log.info("✅ [Webhook] Successfully sent /status response")
                    } catch (e: Exception) {
                        log.error("❌ [Webhook] Error sending /status response: ${e.message}", e)
                    }
                }
            }
            "/vacancies", "/vacancies_all" -> {
                log.info("✅ [Webhook] Processing /vacancies command")
                runBlocking {
                    try {
                        handleListAllVacancies()
                        log.info("✅ [Webhook] Successfully processed /vacancies command")
                    } catch (e: Exception) {
                        log.error("❌ [Webhook] Error processing /vacancies command: ${e.message}", e)
                    }
                }
            }
            "/vacancies_new" -> {
                log.info("✅ [Webhook] Processing /vacancies_new command")
                runBlocking {
                    try {
                        handleListNewVacancies()
                        log.info("✅ [Webhook] Successfully processed /vacancies_new command")
                    } catch (e: Exception) {
                        log.error("❌ [Webhook] Error processing /vacancies_new command: ${e.message}", e)
                    }
                }
            }
            else -> {
                log.warn("⚠️ [Webhook] Unknown command: '$command' (from text: '$normalizedText')")
                runBlocking {
                    try {
                        telegramClient.sendMessage(
                            "❓ Неизвестная команда: $command\n\nИспользуйте /help для списка доступных команд."
                        )
                    } catch (e: Exception) {
                        log.error("❌ [Webhook] Error sending unknown command response: ${e.message}", e)
                    }
                }
            }
        }
    }

    /**
     * Обрабатывает callback_query (нажатие на inline кнопки)
     */
    private fun handleCallbackQuery(callbackQuery: com.hhassistant.client.telegram.dto.CallbackQuery) {
        val callbackData = callbackQuery.data ?: return
        val userId = callbackQuery.from?.id
        val userName = callbackQuery.from?.firstName ?: callbackQuery.from?.username ?: "Unknown"
        val callbackQueryId = callbackQuery.id
        
        log.info("🔘 [Webhook] Received callback query from user $userId ($userName): $callbackData (queryId: $callbackQueryId)")

        runBlocking {
            try {
                // Сначала отвечаем на callback_query (обязательно для Telegram)
                telegramClient.answerCallbackQuery(callbackQueryId, null, false)
                
                when {
                    callbackData.startsWith("mark_applied_") -> {
                        val vacancyId = callbackData.removePrefix("mark_applied_")
                        log.info("✅ [Webhook] User $userId clicked 'Откликнулся' button for vacancy $vacancyId")
                        handleMarkApplied(vacancyId, callbackQueryId)
                    }
                    callbackData.startsWith("mark_not_interested_") -> {
                        val vacancyId = callbackData.removePrefix("mark_not_interested_")
                        log.info("❌ [Webhook] User $userId clicked 'Неинтересная' button for vacancy $vacancyId")
                        handleMarkNotInterested(vacancyId, callbackQueryId)
                    }
                    else -> {
                        log.warn("⚠️ [Webhook] Unknown callback data: $callbackData")
                        telegramClient.answerCallbackQuery(callbackQueryId, "Неизвестная команда", false)
                    }
                }
            } catch (e: Exception) {
                log.error("❌ [Webhook] Error processing callback query: ${e.message}", e)
                // Пытаемся ответить на callback даже при ошибке
                try {
                    telegramClient.answerCallbackQuery(callbackQueryId, "Ошибка: ${e.message}", true)
                } catch (ex: Exception) {
                    log.error("❌ [Webhook] Failed to answer callback query after error: ${ex.message}", ex)
                }
            }
        }
    }

    /**
     * Обрабатывает пометку вакансии как "откликнулся"
     */
    private suspend fun handleMarkApplied(vacancyId: String, callbackQueryId: String) {
        log.info("✅ [Webhook] Starting to mark vacancy $vacancyId as APPLIED")
        
        try {
            // Получаем текущий статус до обновления
            val vacancyBefore = vacancyRepository.findById(vacancyId).orElse(null)
            val oldStatus = vacancyBefore?.status
            log.info("📊 [Webhook] Vacancy $vacancyId current status: $oldStatus")
            
            if (vacancyBefore == null) {
                log.warn("⚠️ [Webhook] Vacancy $vacancyId not found in database")
                telegramClient.sendMessage("⚠️ Вакансия $vacancyId не найдена в базе данных")
                return
            }
            
            // Обновляем статус
            val updatedVacancy = vacancyStatusService.updateVacancyStatusById(vacancyId, VacancyStatus.APPLIED)
            
            if (updatedVacancy != null) {
                log.info("✅ [Webhook] Successfully updated vacancy $vacancyId status: $oldStatus -> ${updatedVacancy.status}")
                log.info("📋 [Webhook] Vacancy details: name='${updatedVacancy.name}', status=${updatedVacancy.status}")
                // Отправляем подтверждение через callback query (toast уведомление)
                telegramClient.answerCallbackQuery(callbackQueryId, "✅ Вакансия помечена как 'Откликнулся'", false)
                // Также отправляем сообщение в чат
                telegramClient.sendMessage("✅ Вакансия '${updatedVacancy.name}' (ID: $vacancyId) помечена как 'Откликнулся'")
            } else {
                log.error("❌ [Webhook] Failed to update vacancy $vacancyId status - updateVacancyStatusById returned null")
                telegramClient.answerCallbackQuery(callbackQueryId, "❌ Не удалось обновить статус", true)
                telegramClient.sendMessage("❌ Не удалось обновить статус вакансии $vacancyId")
            }
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error marking vacancy $vacancyId as applied: ${e.message}", e)
            telegramClient.sendMessage("❌ Ошибка при пометке вакансии: ${e.message}")
        }
    }

    /**
     * Обрабатывает пометку вакансии как "неинтересная"
     */
    private suspend fun handleMarkNotInterested(vacancyId: String, callbackQueryId: String) {
        log.info("❌ [Webhook] Starting to mark vacancy $vacancyId as NOT_INTERESTED")
        
        try {
            // Получаем текущий статус до обновления
            val vacancyBefore = vacancyRepository.findById(vacancyId).orElse(null)
            val oldStatus = vacancyBefore?.status
            log.info("📊 [Webhook] Vacancy $vacancyId current status: $oldStatus")
            
            if (vacancyBefore == null) {
                log.warn("⚠️ [Webhook] Vacancy $vacancyId not found in database")
                telegramClient.sendMessage("⚠️ Вакансия $vacancyId не найдена в базе данных")
                return
            }
            
            // Обновляем статус
            val updatedVacancy = vacancyStatusService.updateVacancyStatusById(vacancyId, VacancyStatus.NOT_INTERESTED)
            
            if (updatedVacancy != null) {
                log.info("❌ [Webhook] Successfully updated vacancy $vacancyId status: $oldStatus -> ${updatedVacancy.status}")
                log.info("📋 [Webhook] Vacancy details: name='${updatedVacancy.name}', status=${updatedVacancy.status}")
                // Отправляем подтверждение через callback query (toast уведомление)
                telegramClient.answerCallbackQuery(callbackQueryId, "❌ Вакансия помечена как 'Неинтересная'", false)
                // Также отправляем сообщение в чат
                telegramClient.sendMessage("❌ Вакансия '${updatedVacancy.name}' (ID: $vacancyId) помечена как 'Неинтересная'")
            } else {
                log.error("❌ [Webhook] Failed to update vacancy $vacancyId status - updateVacancyStatusById returned null")
                telegramClient.answerCallbackQuery(callbackQueryId, "❌ Не удалось обновить статус", true)
                telegramClient.sendMessage("❌ Не удалось обновить статус вакансии $vacancyId")
            }
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error marking vacancy $vacancyId as not interested: ${e.message}", e)
            telegramClient.sendMessage("❌ Ошибка при пометке вакансии: ${e.message}")
        }
    }

    /**
     * Обрабатывает команду /vacancies - показывает все вакансии
     */
    private suspend fun handleListAllVacancies() {
        log.info("📋 [Webhook] Listing all vacancies")

        try {
            val vacancies = vacancyService.findAllVacancies()

            if (vacancies.isEmpty()) {
                telegramClient.sendMessage("📭 В базе данных пока нет вакансий.")
                return
            }

            // Группируем по статусу
            val byStatus = vacancies.groupBy { it.status }
            val message = buildString {
                appendLine("📋 <b>Все вакансии (${vacancies.size}):</b>")
                appendLine()

                // Показываем сначала новые/неоткликнутые
                val newVacancies = byStatus[VacancyStatus.NEW] ?: emptyList()
                val analyzedVacancies = byStatus[VacancyStatus.ANALYZED] ?: emptyList()
                val sentVacancies = byStatus[VacancyStatus.SENT_TO_USER] ?: emptyList()
                val unviewedCount = newVacancies.size + analyzedVacancies.size + sentVacancies.size

                if (unviewedCount > 0) {
                    appendLine("🆕 <b>Новые/неоткликнутые ($unviewedCount):</b>")
                    (newVacancies + analyzedVacancies + sentVacancies).forEach { vacancy ->
                        val normalizedUrl = normalizeVacancyUrl(vacancy.url, vacancy.id)
                        appendLine("• <a href=\"$normalizedUrl\">${escapeHtml(vacancy.name)}</a> - ${escapeHtml(vacancy.employer)}")
                    }
                    appendLine()
                }

                // Показываем откликнутые
                val appliedVacancies = byStatus[VacancyStatus.APPLIED] ?: emptyList()
                if (appliedVacancies.isNotEmpty()) {
                    appendLine("✅ <b>Откликнулся (${appliedVacancies.size}):</b>")
                    appliedVacancies.take(20).forEach { vacancy -> // Ограничиваем до 20 для читаемости
                        val normalizedUrl = normalizeVacancyUrl(vacancy.url, vacancy.id)
                        appendLine("• <a href=\"$normalizedUrl\">${escapeHtml(vacancy.name)}</a> - ${escapeHtml(vacancy.employer)}")
                    }
                    if (appliedVacancies.size > 20) {
                        appendLine("... и еще ${appliedVacancies.size - 20}")
                    }
                    appendLine()
                }

                // Показываем неинтересные
                val notInterestedVacancies = byStatus[VacancyStatus.NOT_INTERESTED] ?: emptyList()
                if (notInterestedVacancies.isNotEmpty()) {
                    appendLine("❌ <b>Неинтересные (${notInterestedVacancies.size}):</b>")
                    appendLine("(Используйте /vacancies_new для просмотра только новых)")
                }
            }

            // Разбиваем на части, если сообщение слишком длинное (Telegram лимит ~4096 символов)
            val maxLength = 4000
            if (message.length <= maxLength) {
                telegramClient.sendMessage(message)
            } else {
                // Отправляем по частям
                val parts = message.split("\n\n")
                var currentPart = StringBuilder()

                for (part in parts) {
                    if (currentPart.length + part.length + 2 > maxLength) {
                        if (currentPart.isNotEmpty()) {
                            telegramClient.sendMessage(currentPart.toString())
                            currentPart.clear()
                        }
                    }
                    if (currentPart.isNotEmpty()) {
                        currentPart.append("\n\n")
                    }
                    currentPart.append(part)
                }

                if (currentPart.isNotEmpty()) {
                    telegramClient.sendMessage(currentPart.toString())
                }
            }
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error listing vacancies: ${e.message}", e)
            telegramClient.sendMessage("❌ Ошибка при получении списка вакансий: ${e.message}")
        }
    }

    /**
     * Обрабатывает команду /vacancies_new - показывает только новые вакансии (еще не откликался)
     */
    private suspend fun handleListNewVacancies() {
        log.info("📋 [Webhook] Listing new vacancies (not applied)")

        try {
            // Получаем вакансии, на которые еще не откликались
            val unviewedVacancies = vacancyService.getUnviewedVacancies()

            if (unviewedVacancies.isEmpty()) {
                telegramClient.sendMessage("📭 Нет новых вакансий. Все вакансии уже обработаны или отмечены.")
                return
            }

            val message = buildString {
                appendLine("🆕 <b>Новые вакансии (${unviewedVacancies.size}):</b>")
                appendLine()
                appendLine("На эти вакансии вы еще не откликались:")
                appendLine()

                unviewedVacancies.forEachIndexed { index, vacancy ->
                    val normalizedUrl = normalizeVacancyUrl(vacancy.url, vacancy.id)
                    appendLine("${index + 1}. <a href=\"$normalizedUrl\">${escapeHtml(vacancy.name)}</a>")
                    appendLine("   🏢 ${escapeHtml(vacancy.employer)}")
                    if (vacancy.salary != null) {
                        appendLine("   💰 ${escapeHtml(vacancy.salary)}")
                    }
                    appendLine("   📍 ${escapeHtml(vacancy.area)}")
                    appendLine("   📊 Статус: ${vacancy.status.name}")
                    appendLine()
                }
            }

            // Разбиваем на части, если сообщение слишком длинное
            val maxLength = 4000
            if (message.length <= maxLength) {
                telegramClient.sendMessage(message)
            } else {
                // Отправляем по частям (по 10 вакансий)
                val chunks = unviewedVacancies.chunked(10)
                chunks.forEachIndexed { chunkIndex, chunk ->
                    val chunkMessage = buildString {
                        appendLine("🆕 <b>Новые вакансии (часть ${chunkIndex + 1} из ${chunks.size}):</b>")
                        appendLine()
                        chunk.forEachIndexed { index, vacancy ->
                            val normalizedUrl = normalizeVacancyUrl(vacancy.url, vacancy.id)
                            val globalIndex = chunkIndex * 10 + index + 1
                            appendLine("$globalIndex. <a href=\"$normalizedUrl\">${escapeHtml(vacancy.name)}</a> - ${escapeHtml(vacancy.employer)}")
                        }
                    }
                    telegramClient.sendMessage(chunkMessage)
                }
            }
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error listing new vacancies: ${e.message}", e)
            telegramClient.sendMessage("❌ Ошибка при получении списка новых вакансий: ${e.message}")
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
     * Экранирует HTML-специальные символы для Telegram
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
