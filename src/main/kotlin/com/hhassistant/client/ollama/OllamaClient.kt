package com.hhassistant.client.ollama

import com.hhassistant.aspect.Loggable
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.client.ollama.dto.OllamaChatRequest
import com.hhassistant.client.ollama.dto.OllamaChatResponse
import com.hhassistant.client.ollama.dto.OllamaGenerateRequest
import com.hhassistant.client.ollama.dto.OllamaGenerateResponse
import com.hhassistant.service.monitoring.OllamaMonitoringService
import com.hhassistant.service.monitoring.OllamaTaskType
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class OllamaClient(
    @Qualifier("ollamaWebClient") private val webClient: WebClient,
    @Value("\${ollama.model}") private val model: String,
    @Value("\${ollama.temperature}") private val defaultTemperature: Double,
    @Value("\${ollama.analysis.temperature:\${ollama.temperature}}") private val analysisTemperature: Double,
    @Autowired(required = false) @Lazy private val ollamaMonitoringService: OllamaMonitoringService?,
) {

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
        ollamaMonitoringService?.incrementActiveRequests(taskType)
        try {
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

            return response.response
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
        ollamaMonitoringService?.incrementActiveRequests(taskType)
        try {
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
            return response.message.content
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
}