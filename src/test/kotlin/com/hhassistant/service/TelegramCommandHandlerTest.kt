package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.client.telegram.dto.User
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
    private lateinit var skillExtractionService: SkillExtractionService
    private lateinit var vacancyService: VacancyService
    private lateinit var exclusionRuleService: ExclusionRuleService
    private lateinit var resumeService: ResumeService
    private lateinit var vacancyAnalysisService: VacancyAnalysisService
    private lateinit var authorizationService: TelegramAuthorizationService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var handler: TelegramCommandHandler

    private val authorizedUser = User(
        id = 123456789L,
        isBot = false,
        firstName = "Test",
        username = "testuser",
    )

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        restTemplate = mockk(relaxed = true)
        skillExtractionService = mockk(relaxed = true)
        vacancyService = mockk(relaxed = true)
        exclusionRuleService = mockk(relaxed = true)
        resumeService = mockk(relaxed = true)
        vacancyAnalysisService = mockk(relaxed = true)
        objectMapper = ObjectMapper()

        // Мокаем authorizationService так, чтобы он всегда разрешал доступ для тестов
        authorizationService = mockk(relaxed = true) {
            every { isAuthorized(any()) } returns true
            every { getUserInfo(any()) } returns "Test User"
        }

        handler = TelegramCommandHandler(
            telegramClient = telegramClient,
            restTemplate = restTemplate,
            skillExtractionService = skillExtractionService,
            vacancyService = vacancyService,
            exclusionRuleService = exclusionRuleService,
            resumeService = resumeService,
            vacancyAnalysisService = vacancyAnalysisService,
            authorizationService = authorizationService,
            objectMapper = objectMapper,
            apiBaseUrl = "http://localhost:8080",
        )
        coEvery { telegramClient.sendMessage(any(), any()) } returns true
    }

    @Test
    fun `unknown command returns help hint`() {
        handler.handleCommand(chatId = "123", text = "/nope", user = authorizedUser)

        coVerify { telegramClient.sendMessage("123", match { it.contains("Unknown command") || it.contains("Неизвестная команда") }) }
    }

    @Test
    fun `vacancies command calls REST and formats empty result`() {
        every { restTemplate.getForObject<Map<String, Any>>(any<String>(), any<Class<Map<String, Any>>>()) } returns mapOf(
            "count" to 0,
            "vacancies" to emptyList<Map<String, Any>>(),
        )

        handler.handleCommand(chatId = "123", text = "/vacancies", user = authorizedUser)

        coVerify { telegramClient.sendMessage("123", match { it.contains("Нет новых вакансий") }) }
    }

    @Test
    fun `mark-applied command posts and reports success`() {
        every { restTemplate.postForObject<Map<String, Any>>(any<String>(), any(), any<Class<Map<String, Any>>>()) } returns mapOf(
            "success" to true,
        )

        handler.handleCommand(chatId = "123", text = "/mark-applied-999", user = authorizedUser)

        coVerify { telegramClient.sendMessage("123", match { it.contains("откликнулся") }) }
    }

    @Test
    fun `unauthorized user gets access denied message`() {
        // Мокаем authorizationService так, чтобы он запрещал доступ
        every { authorizationService.isAuthorized(any()) } returns false
        every { authorizationService.getUserInfo(any()) } returns "Unauthorized User"

        handler.handleCommand(chatId = "123", text = "/vacancies", user = authorizedUser)

        coVerify { telegramClient.sendMessage("123", match { it.contains("Доступ запрещен") || it.contains("Access denied") }) }
        coVerify(exactly = 0) { restTemplate.getForObject<Any>(any(), any()) }
    }
}
