package com.hhassistant.integration.ollama

import com.hhassistant.integration.ollama.dto.ChatMessage
import com.hhassistant.ratelimit.ConfigurableRateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class OllamaClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OllamaClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
            .build()

        val rateLimiter = ConfigurableRateLimiter(1000, 2000, 1, "OllamaTest")
        client = OllamaClient(
            webClient,
            rateLimiter,
            "qwen2.5:7b",
            0.7,
            analysisTemperature = 0.7,
            vacancyAnalysisTimeoutSeconds = 120L,
            skillExtractionTimeoutSeconds = 60L,
            logAnalysisTimeoutSeconds = 180L,
            otherTimeoutSeconds = 90L,
            ollamaMonitoringService = null,
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `should send generate request to Ollama`() = runBlocking {
        val responseJson = """
        {
          "model": "qwen2.5:7b",
          "created_at": "2024-01-01T10:00:00Z",
          "response": "This is a test response from LLM",
          "done": true,
          "total_duration": 1000,
          "load_duration": 100,
          "prompt_eval_count": 10,
          "prompt_eval_duration": 200,
          "eval_count": 20,
          "eval_duration": 700
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"),
        )

        val result = client.generate("Test prompt", "System prompt")

        assertThat(result).isEqualTo("This is a test response from LLM")

        val request = mockWebServer.takeRequest()
        assertThat(request.path).isEqualTo("/api/generate")
        assertThat(request.method).isEqualTo("POST")
    }

    @Test
    fun `should send chat request to Ollama`() = runBlocking {
        val responseJson = """
        {
          "model": "qwen2.5:7b",
          "created_at": "2024-01-01T10:00:00Z",
          "message": {
            "role": "assistant",
            "content": "Chat response"
          },
          "done": true
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"),
        )

        val messages = listOf(
            ChatMessage("user", "Hello"),
        )

        val result = client.chat(messages)

        assertThat(result).isEqualTo("Chat response")

        val request = mockWebServer.takeRequest()
        assertThat(request.path).isEqualTo("/api/chat")
    }
}
