package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate

class TelegramCommandHandlerTest {
    private lateinit var telegramClient: TelegramClient
    private lateinit var restTemplate: RestTemplate
    private lateinit var handler: TelegramCommandHandler

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        restTemplate = mockk(relaxed = true)
        handler = TelegramCommandHandler(
            telegramClient = telegramClient,
            restTemplate = restTemplate,
            apiBaseUrl = "http://localhost:8080",
        )
        coEvery { telegramClient.sendMessage(any(), any()) } returns true
    }

    @Test
    fun `unknown command returns help hint`() {
        handler.handleCommand(chatId = "123", text = "/nope")

        coVerify { telegramClient.sendMessage(match { it.contains("Неизвестная команда") }, any()) }
    }

    @Test
    fun `vacancies command calls REST and formats empty result`() {
        every { restTemplate.getForObject(any<String>(), Map::class.java) } returns mapOf(
            "count" to 0,
            "vacancies" to emptyList<Map<String, Any>>(),
        )

        handler.handleCommand(chatId = "123", text = "/vacancies")

        coVerify { telegramClient.sendMessage(match { it.contains("Нет новых вакансий") }, any()) }
    }

    @Test
    fun `mark-applied command posts and reports success`() {
        every { restTemplate.postForObject(any<String>(), any(), Map::class.java) } returns mapOf(
            "success" to true,
        )

        handler.handleCommand(chatId = "123", text = "/mark-applied-999")

        coVerify { telegramClient.sendMessage(match { it.contains("откликнулся") }, any()) }
    }
}






