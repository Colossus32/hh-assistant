package com.hhassistant.client.ollama

import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.client.ollama.dto.OllamaChatRequest
import com.hhassistant.client.ollama.dto.OllamaChatResponse
import com.hhassistant.client.ollama.dto.OllamaGenerateRequest
import com.hhassistant.client.ollama.dto.OllamaGenerateResponse
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class OllamaClient(
    @Qualifier("ollamaWebClient") private val webClient: WebClient,
    @Value("\${ollama.model}") private val model: String,
    @Value("\${ollama.temperature}") private val temperature: Double,
) {

    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
    ): String {
        val request = OllamaGenerateRequest(
            model = model,
            prompt = prompt,
            system = systemPrompt,
            temperature = temperature,
            stream = false,
        )

        val response = webClient.post()
            .uri("/api/generate")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<OllamaGenerateResponse>()
            .awaitSingle()

        return response.response
    }

    suspend fun chat(messages: List<ChatMessage>): String {
        val request = OllamaChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            stream = false,
        )

        val response = webClient.post()
            .uri("/api/chat")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<OllamaChatResponse>()
            .awaitSingle()
        return response.message.content
    }
}