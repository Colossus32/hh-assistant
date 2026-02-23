package com.hhassistant.integration.telegram

import com.hhassistant.exception.TelegramException
import com.hhassistant.ratelimit.ConfigurableRateLimiter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class TelegramClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var telegramClient: TelegramClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build()

        val rateLimiter = ConfigurableRateLimiter(1000, 2000, 1, "TelegramTest")
        telegramClient = TelegramClient(
            webClient = webClient,
            rateLimiter = rateLimiter,
            botToken = "test-bot-token",
            chatId = "123456789",
            enabled = true,
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `should send message successfully`() {
        runBlocking {
            val responseBody = """
                {
                    "ok": true,
                    "result": {
                        "message_id": 123,
                        "text": "Test message"
                    }
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBody)
                    .addHeader("Content-Type", "application/json"),
            )

            val result = telegramClient.sendMessage("Test message")

            assertThat(result).isTrue()

            val request = mockWebServer.takeRequest()
            assertThat(request.path).isEqualTo("/bottest-bot-token/sendMessage")
            assertThat(request.method).isEqualTo("POST")
        }
    }

    @Test
    fun `should throw exception when Telegram API returns error`() {
        runBlocking {
            val responseBody = """
                {
                    "ok": false,
                    "error_code": 400,
                    "description": "Bad Request: chat not found"
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBody)
                    .addHeader("Content-Type", "application/json"),
            )

            assertThatThrownBy {
                runBlocking {
                    telegramClient.sendMessage("Test message")
                }
            }.isInstanceOf(TelegramException.InvalidChatException::class.java)
                .hasMessageContaining("Invalid chat ID")
        }
    }

    @Test
    fun `should throw rate limit exception`() {
        runBlocking {
            val responseBody = """
                {
                    "ok": false,
                    "error_code": 429,
                    "description": "Too Many Requests"
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBody)
                    .addHeader("Content-Type", "application/json"),
            )

            assertThatThrownBy {
                runBlocking {
                    telegramClient.sendMessage("Test message")
                }
            }.isInstanceOf(TelegramException.RateLimitException::class.java)
                .hasMessageContaining("Rate limit exceeded")
        }
    }

    @Test
    fun `should return false when disabled`() {
        runBlocking {
            val rateLimiter = ConfigurableRateLimiter(1000, 2000, 1, "TelegramTest")
            val disabledClient = TelegramClient(
                webClient = WebClient.builder().baseUrl("http://localhost").build(),
                rateLimiter = rateLimiter,
                botToken = "test-token",
                chatId = "123",
                enabled = false,
            )

            val result = disabledClient.sendMessage("Test message")

            assertThat(result).isFalse()
        }
    }

    @Test
    fun `should return false when bot token is blank`() {
        runBlocking {
            val rateLimiter = ConfigurableRateLimiter(1000, 2000, 1, "TelegramTest")
            val clientWithBlankToken = TelegramClient(
                webClient = WebClient.builder().baseUrl("http://localhost").build(),
                rateLimiter = rateLimiter,
                botToken = "",
                chatId = "123",
                enabled = true,
            )

            val result = clientWithBlankToken.sendMessage("Test message")

            assertThat(result).isFalse()
        }
    }
}
