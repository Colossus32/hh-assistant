package com.hhassistant.integration.ollama

import com.hhassistant.aspect.Loggable
import com.hhassistant.integration.ollama.dto.ChatMessage
import com.hhassistant.integration.ollama.dto.OllamaChatRequest
import com.hhassistant.integration.ollama.dto.OllamaChatResponse
import com.hhassistant.integration.ollama.dto.OllamaGenerateRequest
import com.hhassistant.integration.ollama.dto.OllamaGenerateResponse
import com.hhassistant.monitoring.service.OllamaMonitoringService
import com.hhassistant.monitoring.service.OllamaTaskType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

@Component
class OllamaClient(
    @Qualifier("ollamaWebClient") private val webClient: WebClient,
    @Qualifier("ollamaRateLimiter") private val rateLimiter: com.hhassistant.ratelimit.ConfigurableRateLimiter,
    @Value("\${ollama.model}") private val model: String,
    @Value("\${ollama.temperature}") private val defaultTemperature: Double,
    @Value("\${ollama.analysis.temperature:\${ollama.temperature}}") private val analysisTemperature: Double,
    @Value("\${ollama.timeouts.vacancy-analysis:120}") private val vacancyAnalysisTimeoutSeconds: Long,
    @Value("\${ollama.timeouts.skill-extraction:60}") private val skillExtractionTimeoutSeconds: Long,
    @Value("\${ollama.timeouts.log-analysis:180}") private val logAnalysisTimeoutSeconds: Long,
    @Value("\${ollama.timeouts.other:90}") private val otherTimeoutSeconds: Long,
    private val ollamaMonitoringService: OllamaMonitoringService?,
) {
    private val log = KotlinLogging.logger {}

    @Loggable
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Double? = null,
    ): String {
        return generate(prompt, systemPrompt, temperature, OllamaTaskType.OTHER)
    }

    @Loggable
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Double? = null,
        taskType: OllamaTaskType,
    ): String {
        rateLimiter.tryConsume()
        val timeoutSeconds = getTimeoutForTaskType(taskType)
        ollamaMonitoringService?.incrementActiveRequests(taskType)
        val requestStartTime = System.currentTimeMillis()

        try {
            return withTimeout(Duration.ofSeconds(timeoutSeconds).toMillis()) {
                val request = OllamaGenerateRequest(
                    model = model,
                    prompt = prompt,
                    system = systemPrompt,
                    temperature = temperature ?: defaultTemperature,
                    stream = false,
                )

                val response = webClient.post()
                    .uri("/api/generate")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono<OllamaGenerateResponse>()
                    .awaitSingle()

                val duration = System.currentTimeMillis() - requestStartTime
                if (duration > timeoutSeconds * 1000 * 0.8) {
                    log.warn(
                        "[Ollama] Request took ${duration}ms (${duration / 1000.0}s), " +
                            "close to timeout ${timeoutSeconds}s for task type: $taskType",
                    )
                }

                response.response
            }
        } catch (e: TimeoutCancellationException) {
            val duration = System.currentTimeMillis() - requestStartTime
            log.error(
                "[Ollama] Request timeout after ${duration}ms (${duration / 1000.0}s) " +
                    "for task type: $taskType (timeout: ${timeoutSeconds}s). " +
                    "Ollama took too long to respond. This may indicate overload or model processing issues.",
            )
            throw RuntimeException(
                "Ollama request timeout after ${timeoutSeconds}s for task type: $taskType. " +
                    "The service may be overloaded or the model is processing too slowly.",
                e,
            )
        } finally {
            ollamaMonitoringService?.decrementActiveRequests(taskType)
        }
    }

    @Loggable
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double? = null,
    ): String {
        return chat(messages, temperature, OllamaTaskType.OTHER)
    }

    @Loggable
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        taskType: OllamaTaskType,
    ): String {
        rateLimiter.tryConsume()
        val timeoutSeconds = getTimeoutForTaskType(taskType)
        ollamaMonitoringService?.incrementActiveRequests(taskType)
        val requestStartTime = System.currentTimeMillis()

        try {
            return withTimeout(Duration.ofSeconds(timeoutSeconds).toMillis()) {
                val request = OllamaChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature ?: defaultTemperature,
                    stream = false,
                )

                val response = webClient.post()
                    .uri("/api/chat")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono<OllamaChatResponse>()
                    .awaitSingle()

                val duration = System.currentTimeMillis() - requestStartTime
                if (duration > timeoutSeconds * 1000 * 0.8) {
                    log.warn(
                        "[Ollama] Request took ${duration}ms (${duration / 1000.0}s), " +
                            "close to timeout ${timeoutSeconds}s for task type: $taskType",
                    )
                }

                response.message.content
            }
        } catch (e: TimeoutCancellationException) {
            val duration = System.currentTimeMillis() - requestStartTime
            log.error(
                "[Ollama] Request timeout after ${duration}ms (${duration / 1000.0}s) " +
                    "for task type: $taskType (timeout: ${timeoutSeconds}s). " +
                    "Ollama took too long to respond. This may indicate overload or model processing issues.",
            )
            throw RuntimeException(
                "Ollama request timeout after ${timeoutSeconds}s for task type: $taskType. " +
                    "The service may be overloaded or the model is processing too slowly.",
                e,
            )
        } finally {
            ollamaMonitoringService?.decrementActiveRequests(taskType)
        }
    }

    /**
     * Выполняет chat запрос с temperature для анализа вакансий (более детерминированный)
     */
    @Loggable
    suspend fun chatForAnalysis(messages: List<ChatMessage>): String {
        return chat(messages, temperature = analysisTemperature, taskType = OllamaTaskType.VACANCY_ANALYSIS)
    }

    /**
     * Получает таймаут для указанного типа задачи
     */
    private fun getTimeoutForTaskType(taskType: OllamaTaskType): Long {
        return when (taskType) {
            OllamaTaskType.VACANCY_ANALYSIS -> vacancyAnalysisTimeoutSeconds
            OllamaTaskType.SKILL_EXTRACTION -> skillExtractionTimeoutSeconds
            OllamaTaskType.LOG_ANALYSIS -> logAnalysisTimeoutSeconds
            OllamaTaskType.OTHER -> otherTimeoutSeconds
        }
    }
}
