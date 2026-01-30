package com.hhassistant.service

import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.client.telegram.TelegramClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
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
) {
    private val log = KotlinLogging.logger {}

    /**
     * Анализирует логи приложения и отправляет отчет в Telegram
     * Запускается каждый день в 9:00 утра
     */
    @Scheduled(cron = "\${app.log-analysis.cron:0 0 9 * * *}")
    fun analyzeLogsAndSendReport() {
        if (!enabled) {
            log.debug("📊 [LogAnalysis] Log analysis is disabled, skipping")
            return
        }

        log.info("📊 [LogAnalysis] Starting daily log analysis...")

        runBlocking {
            try {
                // Читаем логи за последние N часов
                val logLines = readRecentLogs(lookbackHours)
                
                if (logLines.isEmpty()) {
                    log.info("ℹ️ [LogAnalysis] No logs found for analysis")
                    return@runBlocking
                }

                log.info("📋 [LogAnalysis] Read ${logLines.size} log lines for analysis")

                // Анализируем логи с помощью Ollama
                val analysisResult = analyzeLogsWithOllama(logLines)

                // Отправляем отчет в Telegram
                sendAnalysisReport(analysisResult)

                log.info("✅ [LogAnalysis] Log analysis completed and report sent")
            } catch (e: Exception) {
                log.error("❌ [LogAnalysis] Error during log analysis: ${e.message}", e)
            }
        }
    }

    /**
     * Читает логи за последние N часов из файла
     */
    private fun readRecentLogs(hours: Int): List<String> {
        val logFile = File(logFilePath)
        
        if (!logFile.exists()) {
            log.warn("⚠️ [LogAnalysis] Log file not found: ${logFile.absolutePath}")
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
            log.error("❌ [LogAnalysis] Error reading log file: ${e.message}", e)
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
            if (line.length < 19) return null
            
            val dateTimeStr = line.substring(0, 19)
            LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Анализирует логи с помощью Ollama
     */
    private suspend fun analyzeLogsWithOllama(logLines: List<String>): LogAnalysisResult {
        log.info("🤖 [LogAnalysis] Analyzing ${logLines.size} log lines with Ollama...")

        // Ограничиваем размер логов для анализа (последние 5000 строк)
        val logsToAnalyze = logLines.takeLast(5000).joinToString("\n")
        
        val systemPrompt = """
            Ты - эксперт по анализу логов приложений. Проанализируй предоставленные логи и найди:
            
            1. **Критические ошибки** - ошибки, которые могут привести к падению приложения или потере данных
            2. **Повторяющиеся ошибки** - ошибки, которые встречаются часто и требуют внимания
            3. **Предупреждения** - ситуации, которые могут привести к проблемам в будущем
            4. **Возможности улучшения** - места, где можно оптимизировать код или логику
            5. **Проблемы производительности** - медленные операции, таймауты, проблемы с памятью
            
            Верни анализ в структурированном виде на русском языке.
            Будь конкретным - указывай конкретные ошибки, их частоту и возможные причины.
            Если критических проблем нет, укажи это явно.
        """.trimIndent()

        val userPrompt = """
            Проанализируй следующие логи приложения за последние ${lookbackHours} часов:
            
            === ЛОГИ ===
            $logsToAnalyze
            === КОНЕЦ ЛОГОВ ===
            
            Предоставь структурированный анализ:
            - Критические ошибки (если есть)
            - Повторяющиеся проблемы
            - Предупреждения
            - Рекомендации по улучшению
        """.trimIndent()

        val analysisText = try {
            ollamaClient.chat(
                listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
            )
        } catch (e: Exception) {
            log.error("❌ [LogAnalysis] Error analyzing logs with Ollama: ${e.message}", e)
            return LogAnalysisResult(
                success = false,
                errorMessage = "Не удалось проанализировать логи: ${e.message}",
                analysisText = null,
                logLinesCount = logLines.size,
            )
        }

        return LogAnalysisResult(
            success = true,
            errorMessage = null,
            analysisText = analysisText,
            logLinesCount = logLines.size,
        )
    }

    /**
     * Отправляет отчет об анализе в Telegram
     */
    private suspend fun sendAnalysisReport(result: LogAnalysisResult) {
        val message = buildString {
            appendLine("📊 <b>Ежедневный анализ логов приложения</b>")
            appendLine()
            appendLine("📅 <b>Период анализа:</b> последние ${lookbackHours} часов")
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
                log.info("✅ [LogAnalysis] Analysis report sent to Telegram")
            } else {
                log.warn("⚠️ [LogAnalysis] Failed to send analysis report (Telegram returned false)")
            }
        } catch (e: Exception) {
            log.error("❌ [LogAnalysis] Error sending analysis report: ${e.message}", e)
        }
    }
}
