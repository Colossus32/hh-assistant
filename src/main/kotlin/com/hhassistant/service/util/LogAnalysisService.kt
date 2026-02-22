package com.hhassistant.service.util

import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.config.AppConstants
import io.netty.handler.timeout.ReadTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientRequestException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Результат анализа логов
 */
private data class LogAnalysisResult(
    val success: Boolean,
    val errorMessage: String?,
    val analysisText: String?,
    val logLinesCount: Int,
)

/**
 * Сервис для анализа логов приложения с помощью Ollama
 * Анализирует логи на предмет ошибок, проблем и возможностей улучшения
 */
@Service
class LogAnalysisService(
    private val ollamaClient: OllamaClient,
    private val telegramClient: TelegramClient,
    @Value("\${app.log-analysis.enabled:true}") private val enabled: Boolean,
    @Value("\${app.log-analysis.log-file:logs/hh-assistant.log}") private val logFilePath: String,
    @Value("\${app.log-analysis.lookback-hours:24}") private val lookbackHours: Int,
    @Value("\${app.log-analysis.batch-size:500}") private val batchSize: Int,
    @Value("\${app.log-analysis.max-batches:10}") private val maxBatches: Int,
    @Value("\${app.log-analysis.summary-first:true}") private val summaryFirst: Boolean,
    @Value("\${app.log-analysis.retry.max-attempts:3}") private val retryMaxAttempts: Int,
    @Value("\${app.log-analysis.retry.initial-delay-ms:2000}") private val retryInitialDelayMs: Long,
) {
    private val log = KotlinLogging.logger {}
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Анализирует логи приложения и отправляет отчет в Telegram
     * Запускается каждый день в 9:00 утра
     */
    @Scheduled(cron = "\${app.log-analysis.cron:0 0 9 * * *}")
    fun analyzeLogsAndSendReport() {
        if (!enabled) {
            log.debug(" [LogAnalysis] Log analysis is disabled, skipping")
            return
        }

        log.info(" [LogAnalysis] Starting daily log analysis...")

        analysisScope.launch {
            try {
                // Читаем логи за последние N часов
                val logLines = readRecentLogs(lookbackHours)

                if (logLines.isEmpty()) {
                    log.info("ℹ️ [LogAnalysis] No logs found for analysis")
                    return@launch
                }

                log.info(" [LogAnalysis] Read ${logLines.size} log lines for analysis")

                // Анализируем логи с помощью Ollama
                val analysisResult = analyzeLogsWithOllama(logLines)

                // Отправляем отчет в Telegram
                sendAnalysisReport(analysisResult)

                log.info(" [LogAnalysis] Log analysis completed and report sent")
            } catch (e: Exception) {
                log.error(" [LogAnalysis] Error during log analysis: ${e.message}", e)
            }
        }
    }

    /**
     * Читает логи за последние N часов из файла
     */
    private fun readRecentLogs(hours: Int): List<String> {
        val logFile = File(logFilePath)

        if (!logFile.exists()) {
            log.warn(" [LogAnalysis] Log file not found: ${logFile.absolutePath}")
            return emptyList()
        }

        val cutoffTime = LocalDateTime.now().minusHours(hours.toLong())
        val logLines = mutableListOf<String>()

        try {
            logFile.useLines { lines ->
                lines.forEach { line ->
                    // Пытаемся определить время записи лога
                    // Формат: "2026-01-30 11:42:52 - ..."
                    val timestamp = extractTimestamp(line)
                    if (timestamp != null && timestamp.isAfter(cutoffTime)) {
                        logLines.add(line)
                    } else if (timestamp == null) {
                        // Если не удалось распарсить время, добавляем строку
                        // (может быть продолжение предыдущей записи)
                        if (logLines.isNotEmpty()) {
                            logLines.add(line)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(" [LogAnalysis] Error reading log file: ${e.message}", e)
            return emptyList()
        }

        return logLines
    }

    /**
     * Извлекает timestamp из строки лога
     * Формат: "2026-01-30 11:42:52 - ..."
     */
    private fun extractTimestamp(line: String): LocalDateTime? {
        return try {
            if (line.length < AppConstants.Logging.LOG_TIMESTAMP_LENGTH) return null

            val dateTimeStr = line.substring(0, AppConstants.Logging.LOG_TIMESTAMP_LENGTH)
            LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern(AppConstants.Logging.LOG_TIMESTAMP_FORMAT))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Анализирует логи с помощью Ollama с батчингом и саммаризацией
     */
    private suspend fun analyzeLogsWithOllama(logLines: List<String>): LogAnalysisResult {
        log.info(
            " [LogAnalysis] Analyzing ${logLines.size} log lines with Ollama (batch size: $batchSize, max batches: $maxBatches)...",
        )

        // Разбиваем логи на батчи
        val batches = logLines.chunked(batchSize).take(maxBatches)
        log.info(" [LogAnalysis] Split logs into ${batches.size} batch(es)")

        if (batches.isEmpty()) {
            return LogAnalysisResult(
                success = false,
                errorMessage = "No log batches to analyze",
                analysisText = null,
                logLinesCount = logLines.size,
            )
        }

        return if (summaryFirst && batches.size > 1) {
            // Стратегия: сначала саммари каждого батча, потом анализ саммари
            analyzeWithSummarization(batches)
        } else {
            // Стратегия: анализируем все батчи последовательно и объединяем результаты
            analyzeBatchesSequentially(batches)
        }
    }

    /**
     * Анализирует логи с предварительной саммаризацией батчей
     * 1. Создает саммари каждого батча
     * 2. Анализирует саммари вместе с деталями из проблемных батчей
     */
    private suspend fun analyzeWithSummarization(batches: List<List<String>>): LogAnalysisResult {
        log.info(" [LogAnalysis] Using summarization strategy: ${batches.size} batches")

        val batchSummaries = mutableListOf<String>()
        val problematicBatches = mutableListOf<Pair<Int, List<String>>>()

        // Шаг 1: Создаем саммари каждого батча
        var timeoutErrorsCount = 0
        val maxTimeoutErrors = batches.size / 2 // Если больше половины батчей падают с таймаутами, прекращаем

        for ((index, batch) in batches.withIndex()) {
            try {
                log.info(
                    " [LogAnalysis] Creating summary for batch ${index + 1}/${batches.size} (${batch.size} lines)...",
                )

                val summary = createBatchSummaryWithRetry(batch, index + 1, batches.size)
                batchSummaries.add("=== Батч ${index + 1} ===\n$summary")

                // Если в саммари есть упоминания об ошибках, сохраняем батч для детального анализа
                if (summary.contains("ошибк", ignoreCase = true) ||
                    summary.contains("error", ignoreCase = true) ||
                    summary.contains("exception", ignoreCase = true) ||
                    summary.contains("failed", ignoreCase = true)
                ) {
                    problematicBatches.add(index + 1 to batch)
                    log.info(
                        " [LogAnalysis] Batch ${index + 1} contains errors, will include details in final analysis",
                    )
                }
            } catch (e: Exception) {
                val errorInfo = extractErrorInfo(e)
                log.error(
                    " [LogAnalysis] Error creating summary for batch ${index + 1}: ${errorInfo.message} (type: ${errorInfo.type})",
                    e,
                )

                // Если это таймаут, увеличиваем счетчик
                if (errorInfo.isTimeout) {
                    timeoutErrorsCount++
                    if (timeoutErrorsCount > maxTimeoutErrors) {
                        log.warn(
                            " [LogAnalysis] Too many timeout errors ($timeoutErrorsCount/$batches.size). " +
                                "Skipping remaining batches to avoid further timeouts.",
                        )
                        batchSummaries.add(
                            "=== Батч ${index + 1} ===\n⚠️ Таймаут при создании саммари. " +
                                "Ollama API не отвечает в течение заданного времени. " +
                                "Возможно, сервис перегружен (проверьте pending запросы).",
                        )
                        break // Прекращаем обработку оставшихся батчей
                    }
                }

                batchSummaries.add(
                    "=== Батч ${index + 1} ===\n⚠️ Ошибка при создании саммари: ${errorInfo.message}",
                )
            }
        }

        // Шаг 2: Анализируем саммари вместе с деталями из проблемных батчей
        log.info(
            " [LogAnalysis] Analyzing ${batchSummaries.size} summaries and ${problematicBatches.size} problematic batch details...",
        )

        val finalAnalysis = analyzeSummariesWithDetails(batchSummaries, problematicBatches)

        return LogAnalysisResult(
            success = true,
            errorMessage = null,
            analysisText = finalAnalysis,
            logLinesCount = batches.sumOf { it.size },
        )
    }

    /**
     * Создает саммари одного батча логов с retry логикой
     */
    private suspend fun createBatchSummaryWithRetry(
        batch: List<String>,
        batchNumber: Int,
        totalBatches: Int,
    ): String {
        var lastException: Exception? = null
        var delayMs = retryInitialDelayMs

        for (attempt in 1..retryMaxAttempts) {
            try {
                return createBatchSummary(batch, batchNumber, totalBatches)
            } catch (e: Exception) {
                lastException = e
                val errorInfo = extractErrorInfo(e)

                // Если это не таймаут или это последняя попытка, пробрасываем ошибку
                if (!errorInfo.isTimeout || attempt == retryMaxAttempts) {
                    throw e
                }

                // Для таймаутов делаем retry с экспоненциальной задержкой
                log.warn(
                    " [LogAnalysis] Timeout creating summary for batch $batchNumber (attempt $attempt/$retryMaxAttempts). " +
                        "Retrying in ${delayMs}ms...",
                )
                delay(delayMs)
                delayMs *= 2 // Экспоненциальная задержка
            }
        }

        // Не должно сюда дойти, но на всякий случай
        throw lastException ?: RuntimeException("Failed to create summary after $retryMaxAttempts attempts")
    }

    /**
     * Создает саммари одного батча логов
     */
    private suspend fun createBatchSummary(batch: List<String>, batchNumber: Int, totalBatches: Int): String {
        val batchText = batch.joinToString("\n")

        val systemPrompt = """
            Ты - эксперт по анализу логов. Создай краткое резюме (до ${AppConstants.TextLimits.LOG_ANALYSIS_SUMMARY_WORDS} слов) следующего батча логов.
            
            В резюме укажи:
            - Основные события и операции
            - Количество и типы ошибок (если есть)
            - Критические проблемы (если есть)
            - Предупреждения (если есть)
            
            Будь кратким и конкретным. Если проблем нет, просто опиши основные операции.
            Отвечай на русском языке.
        """.trimIndent()

        val userPrompt = """
            Создай краткое резюме следующего батча логов (батч $batchNumber из $totalBatches):
            
            === ЛОГИ БАТЧА ===
            $batchText
            === КОНЕЦ БАТЧА ===
        """.trimIndent()

        return ollamaClient.chat(
            listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            taskType = com.hhassistant.service.monitoring.OllamaTaskType.LOG_ANALYSIS,
        )
    }

    /**
     * Анализирует саммари батчей вместе с деталями из проблемных батчей
     */
    private suspend fun analyzeSummariesWithDetails(
        summaries: List<String>,
        problematicBatches: List<Pair<Int, List<String>>>,
    ): String {
        val summariesText = summaries.joinToString("\n\n")

        // Добавляем детали из проблемных батчей (ограничиваем размер)
        val problematicDetails = problematicBatches.take(
            AppConstants.Indices.PROBLEMATIC_BATCHES_LIMIT,
        ).joinToString("\n\n") { (batchNum, batch) ->
            "=== Детали проблемного батча $batchNum ===\n${batch.takeLast(
                AppConstants.TextLimits.PROBLEMATIC_BATCH_DETAILS_LINES,
            ).joinToString("\n")}"
        }

        val systemPrompt = """
            Ты - эксперт по анализу логов приложений. Проанализируй предоставленные резюме батчей логов и найди:
            
            1. **Критические ошибки** - ошибки, которые могут привести к падению приложения или потере данных
            2. **Повторяющиеся ошибки** - ошибки, которые встречаются часто и требуют внимания
            3. **Предупреждения** - ситуации, которые могут привести к проблемам в будущем
            4. **Возможности улучшения** - места, где можно оптимизировать код или логику
            5. **Проблемы производительности** - медленные операции, таймауты, проблемы с памятью
            
            Верни анализ в структурированном виде на русском языке.
            Будь конкретным - указывай конкретные ошибки, их частоту и возможные причины.
            Если критических проблем нет, укажи это явно.
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("Проанализируй следующие резюме батчей логов за последние $lookbackHours часов:")
            appendLine()
            appendLine("=== РЕЗЮМЕ БАТЧЕЙ ===")
            appendLine(summariesText)
            appendLine("=== КОНЕЦ РЕЗЮМЕ ===")

            if (problematicDetails.isNotEmpty()) {
                appendLine()
                appendLine("=== ДЕТАЛИ ПРОБЛЕМНЫХ БАТЧЕЙ ===")
                appendLine(problematicDetails)
                appendLine("=== КОНЕЦ ДЕТАЛЕЙ ===")
            }

            appendLine()
            appendLine("Предоставь структурированный анализ:")
            appendLine("- Критические ошибки (если есть)")
            appendLine("- Повторяющиеся проблемы")
            appendLine("- Предупреждения")
            appendLine("- Рекомендации по улучшению")
        }

        return try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
                taskType = com.hhassistant.service.monitoring.OllamaTaskType.LOG_ANALYSIS,
            )
        } catch (e: Exception) {
            val errorInfo = extractErrorInfo(e)
            log.error(
                " [LogAnalysis] Error analyzing summaries: ${errorInfo.message} (type: ${errorInfo.type})",
                e,
            )
            throw e
        }
    }

    /**
     * Анализирует батчи последовательно и объединяет результаты
     * Используется когда батчей мало или summary-first отключен
     */
    private suspend fun analyzeBatchesSequentially(batches: List<List<String>>): LogAnalysisResult {
        log.info(" [LogAnalysis] Using sequential analysis strategy: ${batches.size} batches")

        val batchAnalyses = mutableListOf<String>()

        var timeoutErrorsCount = 0
        val maxTimeoutErrors = batches.size / 2

        for ((index, batch) in batches.withIndex()) {
            try {
                log.info(" [LogAnalysis] Analyzing batch ${index + 1}/${batches.size} (${batch.size} lines)...")

                val batchAnalysis = analyzeSingleBatchWithRetry(batch, index + 1, batches.size)
                batchAnalyses.add("=== Анализ батча ${index + 1} ===\n$batchAnalysis")
            } catch (e: Exception) {
                val errorInfo = extractErrorInfo(e)
                log.error(
                    " [LogAnalysis] Error analyzing batch ${index + 1}: ${errorInfo.message} (type: ${errorInfo.type})",
                    e,
                )

                // Если это таймаут, увеличиваем счетчик
                if (errorInfo.isTimeout) {
                    timeoutErrorsCount++
                    if (timeoutErrorsCount > maxTimeoutErrors) {
                        log.warn(
                            " [LogAnalysis] Too many timeout errors ($timeoutErrorsCount/${batches.size}). " +
                                "Skipping remaining batches to avoid further timeouts.",
                        )
                        batchAnalyses.add(
                            "=== Анализ батча ${index + 1} ===\n⚠️ Таймаут при анализе. " +
                                "Ollama API не отвечает в течение заданного времени.",
                        )
                        break
                    }
                }

                batchAnalyses.add("=== Анализ батча ${index + 1} ===\n⚠️ Ошибка: ${errorInfo.message}")
            }
        }

        // Объединяем результаты
        val combinedAnalysis = if (batchAnalyses.size > 1) {
            combineBatchAnalyses(batchAnalyses)
        } else {
            batchAnalyses.firstOrNull() ?: "Анализ не выполнен"
        }

        return LogAnalysisResult(
            success = true,
            errorMessage = null,
            analysisText = combinedAnalysis,
            logLinesCount = batches.sumOf { it.size },
        )
    }

    /**
     * Анализирует один батч логов с retry логикой
     */
    private suspend fun analyzeSingleBatchWithRetry(
        batch: List<String>,
        batchNumber: Int,
        totalBatches: Int,
    ): String {
        var lastException: Exception? = null
        var delayMs = retryInitialDelayMs

        for (attempt in 1..retryMaxAttempts) {
            try {
                return analyzeSingleBatch(batch, batchNumber, totalBatches)
            } catch (e: Exception) {
                lastException = e
                val errorInfo = extractErrorInfo(e)

                // Если это не таймаут или это последняя попытка, пробрасываем ошибку
                if (!errorInfo.isTimeout || attempt == retryMaxAttempts) {
                    throw e
                }

                // Для таймаутов делаем retry с экспоненциальной задержкой
                log.warn(
                    " [LogAnalysis] Timeout analyzing batch $batchNumber (attempt $attempt/$retryMaxAttempts). " +
                        "Retrying in ${delayMs}ms...",
                )
                delay(delayMs)
                delayMs *= 2 // Экспоненциальная задержка
            }
        }

        throw lastException ?: RuntimeException("Failed to analyze batch after $retryMaxAttempts attempts")
    }

    /**
     * Анализирует один батч логов
     */
    private suspend fun analyzeSingleBatch(batch: List<String>, batchNumber: Int, totalBatches: Int): String {
        val batchText = batch.joinToString("\n")

        val systemPrompt = """
            Ты - эксперт по анализу логов приложений. Проанализируй предоставленные логи и найди:
            
            1. **Критические ошибки** - ошибки, которые могут привести к падению приложения или потере данных
            2. **Повторяющиеся ошибки** - ошибки, которые встречаются часто и требуют внимания
            3. **Предупреждения** - ситуации, которые могут привести к проблемам в будущем
            4. **Возможности улучшения** - места, где можно оптимизировать код или логику
            5. **Проблемы производительности** - медленные операции, таймауты, проблемы с памятью
            
            Верни краткий анализ (до ${AppConstants.TextLimits.LOG_ANALYSIS_BRIEF_WORDS} слов) на русском языке.
            Будь конкретным - указывай конкретные ошибки и их частоту.
            Если проблем нет, укажи это явно.
        """.trimIndent()

        val userPrompt = """
            Проанализируй следующий батч логов (батч $batchNumber из $totalBatches):
            
            === ЛОГИ ===
            $batchText
            === КОНЕЦ ЛОГОВ ===
        """.trimIndent()

        return ollamaClient.chat(
            listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
            taskType = com.hhassistant.service.monitoring.OllamaTaskType.LOG_ANALYSIS,
        )
    }

    /**
     * Объединяет анализы нескольких батчей в финальный отчет
     */
    private suspend fun combineBatchAnalyses(batchAnalyses: List<String>): String {
        val combinedText = batchAnalyses.joinToString("\n\n")

        val systemPrompt = """
            Ты - эксперт по анализу логов. Объедини анализы нескольких батчей логов в единый структурированный отчет.
            
            В отчете укажи:
            1. **Критические ошибки** - обобщи все критические ошибки из всех батчей
            2. **Повторяющиеся проблемы** - найди общие паттерны и повторяющиеся проблемы
            3. **Предупреждения** - обобщи все предупреждения
            4. **Рекомендации по улучшению** - дай общие рекомендации на основе всех батчей
            
            Верни структурированный отчет на русском языке.
            Если проблем нет, укажи это явно.
        """.trimIndent()

        val userPrompt = """
            Объедини следующие анализы батчей в единый отчет:
            
            === АНАЛИЗЫ БАТЧЕЙ ===
            $combinedText
            === КОНЕЦ АНАЛИЗОВ ===
        """.trimIndent()

        return try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
                taskType = com.hhassistant.service.monitoring.OllamaTaskType.LOG_ANALYSIS,
            )
        } catch (e: Exception) {
            val errorInfo = extractErrorInfo(e)
            log.error(
                " [LogAnalysis] Error combining batch analyses: ${errorInfo.message} (type: ${errorInfo.type})",
                e,
            )
            combinedText // Возвращаем просто объединенный текст, если не удалось обработать
        }
    }

    /**
     * Отправляет отчет об анализе в Telegram
     */
    private suspend fun sendAnalysisReport(result: LogAnalysisResult) {
        val message = buildString {
            appendLine("📊 <b>Ежедневный анализ логов приложения</b>")
            appendLine()
            appendLine("📅 <b>Период анализа:</b> последние $lookbackHours часов")
            appendLine("📋 <b>Проанализировано строк:</b> ${result.logLinesCount}")
            appendLine()

            if (!result.success) {
                appendLine("❌ <b>Ошибка анализа:</b>")
                appendLine(result.errorMessage ?: "Неизвестная ошибка")
            } else {
                appendLine("✅ <b>Анализ завершен успешно</b>")
                appendLine()
                appendLine("<b>Результаты анализа:</b>")
                appendLine()
                appendLine(result.analysisText ?: "Анализ не вернул результатов")
            }
        }

        try {
            val sent = telegramClient.sendMessage(message)
            if (sent) {
                log.info(" [LogAnalysis] Analysis report sent to Telegram")
            } else {
                log.warn(" [LogAnalysis] Failed to send analysis report (Telegram returned false)")
            }
        } catch (e: Exception) {
            log.error(" [LogAnalysis] Error sending analysis report: ${e.message}", e)
        }
    }

    /**
     * Информация об ошибке для улучшенного логирования
     */
    private data class ErrorInfo(
        val type: String,
        val message: String,
        val isTimeout: Boolean,
    )

    /**
     * Извлекает информацию об ошибке для улучшенного логирования
     */
    private fun extractErrorInfo(e: Exception): ErrorInfo {
        return when {
            e is TimeoutCancellationException -> {
                ErrorInfo(
                    type = "TimeoutCancellationException",
                    message = "Таймаут корутины при запросе к Ollama API. " +
                        "Запрос был отменен из-за превышения таймаута на уровне корутин. " +
                        "Ollama 'задумался' и не ответил в течение заданного времени. " +
                        "Это может указывать на перегрузку сервиса или слишком медленную обработку модели.",
                    isTimeout = true,
                )
            }
            e is ReadTimeoutException || e.cause is ReadTimeoutException -> {
                ErrorInfo(
                    type = "ReadTimeoutException",
                    message = "Таймаут чтения при запросе к Ollama API. " +
                        "Сервис не ответил в течение заданного времени. " +
                        "Возможно, Ollama перегружен или модель обрабатывает запрос слишком долго.",
                    isTimeout = true,
                )
            }
            e is WebClientRequestException -> {
                val isTimeout = e.cause is ReadTimeoutException ||
                    e.cause is TimeoutCancellationException ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("ReadTimeout", ignoreCase = true) == true ||
                    e.message?.contains("TimeoutCancellation", ignoreCase = true) == true

                ErrorInfo(
                    type = "WebClientRequestException",
                    message = if (isTimeout) {
                        "Таймаут при запросе к Ollama API: ${e.message ?: "неизвестная ошибка"}"
                    } else {
                        "Ошибка запроса к Ollama API: ${e.message ?: "неизвестная ошибка"}"
                    },
                    isTimeout = isTimeout,
                )
            }
            e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("TimeoutCancellation", ignoreCase = true) == true -> {
                ErrorInfo(
                    type = "Timeout (generic)",
                    message = "Таймаут: ${e.message}",
                    isTimeout = true,
                )
            }
            else -> {
                ErrorInfo(
                    type = e.javaClass.simpleName,
                    message = e.message ?: "Неизвестная ошибка",
                    isTimeout = false,
                )
            }
        }
    }
}
