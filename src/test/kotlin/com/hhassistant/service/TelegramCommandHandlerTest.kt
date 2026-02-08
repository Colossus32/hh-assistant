package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.client.telegram.dto.User
import com.hhassistant.service.exclusion.ExclusionKeywordService
import com.hhassistant.service.exclusion.ExclusionRuleService
import com.hhassistant.service.skill.SkillExtractionQueueService
import com.hhassistant.service.skill.SkillExtractionService
import com.hhassistant.service.skill.SkillStatisticsService
import com.hhassistant.service.telegram.TelegramAuthorizationService
import com.hhassistant.service.telegram.TelegramCommandHandler
import com.hhassistant.service.util.AnalysisTimeService
import com.hhassistant.service.vacancy.VacancyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class TelegramCommandHandlerTest {
    private lateinit var telegramClient: TelegramClient
    private lateinit var webClient: WebClient
    private lateinit var skillExtractionService: SkillExtractionService
    private lateinit var skillExtractionQueueService: SkillExtractionQueueService
    private lateinit var skillStatisticsService: SkillStatisticsService
    private lateinit var vacancyService: VacancyService
    private lateinit var exclusionRuleService: ExclusionRuleService
    private lateinit var exclusionKeywordService: ExclusionKeywordService
    private lateinit var analysisTimeService: AnalysisTimeService
    private lateinit var authorizationService: TelegramAuthorizationService
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
        webClient = mockk(relaxed = true)
        skillExtractionService = mockk(relaxed = true)
        skillExtractionQueueService = mockk(relaxed = true)
        skillStatisticsService = mockk(relaxed = true)
        vacancyService = mockk(relaxed = true)
        exclusionRuleService = mockk(relaxed = true)
        exclusionKeywordService = mockk(relaxed = true)
        analysisTimeService = mockk(relaxed = true)
        val vacancyProcessingQueueService = mockk<com.hhassistant.service.vacancy.VacancyProcessingQueueService>(relaxed = true)

        // Мокаем authorizationService так, чтобы он всегда разрешал доступ для тестов
        authorizationService = mockk(relaxed = true) {
            every { isAuthorized(any()) } returns true
            every { getUserInfo(any()) } returns "Test User"
        }

        handler = TelegramCommandHandler(
            telegramClient = telegramClient,
            webClient = webClient,
            skillExtractionService = skillExtractionService,
            skillStatisticsService = skillStatisticsService,
            skillExtractionQueueService = skillExtractionQueueService,
            vacancyProcessingQueueService = vacancyProcessingQueueService,
            vacancyService = vacancyService,
            exclusionRuleService = exclusionRuleService,
            exclusionKeywordService = exclusionKeywordService,
            analysisTimeService = analysisTimeService,
            apiBaseUrl = "http://localhost:8080",
        )
        coEvery { telegramClient.sendMessage(targetChatId = any(), text = any(), replyMarkup = any()) } returns true
    }

    @Test
    fun `unknown command returns help hint`() {
        kotlinx.coroutines.runBlocking {
            handler.handleCommand(chatId = "123", text = "/nope")

            coVerify {
                telegramClient.sendMessage(
                    targetChatId = "123",
                    text = match {
                        it.contains("Unknown command") || it.contains("Неизвестная команда")
                    },
                )
            }
        }
    }

    @Test
    fun `vacancies command calls REST and formats empty result`() {
        kotlinx.coroutines.runBlocking {
            handler.handleCommand(chatId = "123", text = "/vacancies")

            coVerify {
                telegramClient.sendMessage(
                    targetChatId = "123",
                    text = match { it.contains("Нет новых вакансий") },
                )
            }
        }
    }

    @Test
    fun `mark-applied command posts and reports success`() {
        kotlinx.coroutines.runBlocking {
            handler.handleCommand(chatId = "123", text = "/mark-applied-999")

            coVerify { telegramClient.sendMessage(targetChatId = "123", text = match { it.contains("откликнулся") }) }
        }
    }

    @Test
    fun `unauthorized user gets access denied message`() {
        kotlinx.coroutines.runBlocking {
            // Мокаем authorizationService так, чтобы он запрещал доступ
            every { authorizationService.isAuthorized(any()) } returns false
            every { authorizationService.getUserInfo(any()) } returns "Unauthorized User"

            handler.handleCommand(chatId = "123", text = "/vacancies")

            coVerify {
                telegramClient.sendMessage(
                    targetChatId = "123",
                    text = match {
                        it.contains("Доступ запрещен") || it.contains("Access denied")
                    },
                )
            }
        }
    }
}
