package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.client.telegram.dto.InlineKeyboardMarkup
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.event.VacancyReadyForTelegramEvent
import com.hhassistant.exception.TelegramException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VacancyNotificationServiceTest {

    private lateinit var telegramClient: TelegramClient
    private lateinit var vacancyStatusService: VacancyStatusService
    private lateinit var metricsService: com.hhassistant.metrics.MetricsService
    private lateinit var service: VacancyNotificationService
    private lateinit var capturedMessages: MutableList<Pair<String, InlineKeyboardMarkup?>>

    @BeforeEach
    fun setUp() {
        telegramClient = mockk(relaxed = true)
        vacancyStatusService = mockk(relaxed = true)
        metricsService = mockk(relaxed = true)
        capturedMessages = mutableListOf()

        coEvery { telegramClient.sendMessage(any(), any()) } answers {
            val message = args[0] as String
            val keyboard = args[1] as? InlineKeyboardMarkup
            capturedMessages.add(Pair(message, keyboard))
            true
        }

        service = VacancyNotificationService(
            telegramClient = telegramClient,
            vacancyStatusService = vacancyStatusService,
            metricsService = metricsService,
            apiBaseUrl = "http://localhost:8080",
        )
    }

    @Test
    fun `should handle VacancyReadyForTelegramEvent and send message to Telegram`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            coverLetter = "Test cover letter",
        )
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // When
        service.handleVacancyReadyForTelegram(event)

        // Then
        coVerify(exactly = 1) { telegramClient.sendMessage(any(), any()) }
        verify(exactly = 1) { vacancyStatusService.updateVacancyStatus(any()) }
        assertThat(capturedMessages.size).isEqualTo(1)
        assertThat(capturedMessages[0].first).contains(vacancy.name)
        assertThat(capturedMessages[0].first).contains(vacancy.employer)
        assertThat(capturedMessages[0].first).contains("Test cover letter")
        assertThat(capturedMessages[0].second).isNotNull
    }

    @Test
    fun `should send message without cover letter when cover letter is not available`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            coverLetter = null,
        )
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // When
        service.handleVacancyReadyForTelegram(event)

        // Then
        coVerify(exactly = 1) { telegramClient.sendMessage(any(), any()) }
        assertThat(capturedMessages[0].first).doesNotContain("Сопроводительное письмо")
    }

    @Test
    fun `should handle TelegramException gracefully`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
        )
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        coEvery { telegramClient.sendMessage(any(), any()) } throws TelegramException.RateLimitException("Rate limit")

        // When
        service.handleVacancyReadyForTelegram(event)

        // Then
        coVerify(exactly = 1) { telegramClient.sendMessage(any(), any()) }
        verify(exactly = 0) { vacancyStatusService.updateVacancyStatus(any()) }
    }

    @Test
    fun `should include inline keyboard with action buttons`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
        )
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // When
        service.handleVacancyReadyForTelegram(event)

        // Then
        val keyboard = capturedMessages[0].second
        assertThat(keyboard).isNotNull
        assertThat(keyboard!!.inlineKeyboard).hasSize(1)
        assertThat(keyboard.inlineKeyboard[0]).hasSize(2)
        assertThat(keyboard.inlineKeyboard[0][0].text).contains("Откликнулся")
        assertThat(keyboard.inlineKeyboard[0][1].text).contains("Неинтересная")
        assertThat(keyboard.inlineKeyboard[0][0].url).contains("/api/vacancies/${vacancy.id}/mark-applied")
        assertThat(keyboard.inlineKeyboard[0][1].url).contains("/api/vacancies/${vacancy.id}/mark-not-interested")
    }

    @Test
    fun `should escape HTML in message content`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java & Kotlin Developer")
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            reasoning = "Test <reasoning> with & special chars",
        )
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // When
        service.handleVacancyReadyForTelegram(event)

        // Then
        val message = capturedMessages[0].first
        assertThat(message).contains("&amp;")
        assertThat(message).contains("&lt;")
        assertThat(message).contains("&gt;")
    }

    // Helper methods
    private fun createTestVacancy(id: String, name: String): Vacancy {
        return Vacancy(
            id = id,
            name = name,
            employer = "Test Company",
            salary = "100000-150000 RUB",
            area = "Moscow",
            url = "https://hh.ru/vacancy/$id",
            description = "Test description",
            experience = "3-6 years",
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
    }

    private fun createTestAnalysis(
        vacancyId: String,
        isRelevant: Boolean,
        score: Double,
        coverLetter: String? = null,
        reasoning: String = "Test reasoning",
    ): VacancyAnalysis {
        return VacancyAnalysis(
            vacancyId = vacancyId,
            isRelevant = isRelevant,
            relevanceScore = score,
            reasoning = reasoning,
            matchedSkills = "[]",
            suggestedCoverLetter = coverLetter,
            coverLetterGenerationStatus = if (coverLetter != null) {
                CoverLetterGenerationStatus.SUCCESS
            } else {
                CoverLetterGenerationStatus.FAILED
            },
            coverLetterAttempts = if (coverLetter != null) 1 else 3,
            coverLetterLastAttemptAt = LocalDateTime.now(),
        )
    }
}
