package com.hhassistant.client.ollama.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

data class OllamaGenerateResponse(
    val model: String,
    @JsonProperty("created_at")
    val createdAt: String,
    val response: String,
    val done: Boolean,
    @JsonProperty("total_duration")
    val totalDuration: Long?,
    @JsonProperty("load_duration")
    val loadDuration: Long?,
    @JsonProperty("prompt_eval_count")
    val promptEvalCount: Int?,
    @JsonProperty("prompt_eval_duration")
    val promptEvalDuration: Long?,
    @JsonProperty("eval_count")
    val evalCount: Int?,
    @JsonProperty("eval_duration")
    val evalDuration: Long?,
)

data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String,
)

data class OllamaChatResponse(
    val model: String,
    @JsonProperty("created_at")
    val createdAt: String,
    val message: ChatMessage,
    val done: Boolean,
)






