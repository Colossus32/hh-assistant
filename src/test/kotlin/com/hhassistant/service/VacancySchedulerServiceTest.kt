package com.hhassistant.service

import com.hhassistant.client.telegram.TelegramClient
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.exception.TelegramException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VacancySchedulerServiceTest {

    private lateinit var vacancyService: VacancyService
    private lateinit var vacancyAnalysisService: VacancyAnalysisService
    private lateinit var telegramClient: TelegramClient
    private lateinit var service: VacancySchedulerService

    @BeforeEach
    fun setup() {
        vacancyService = mockk()
        vacancyAnalysisService = mockk()
        telegramClient = mockk()
        service = VacancySchedulerService(
            vacancyService = vacancyService,
            vacancyAnalysisService = vacancyAnalysisService,
            telegramClient = telegramClient,
            dryRun = false,
            maxConcurrentRequests = 3,
        )
    }

    @Test
    fun `should skip execution in dry-run mode`() {
        val dryRunService = VacancySchedulerService(
            vacancyService = vacancyService,
            vacancyAnalysisService = vacancyAnalysisService,
            telegramClient = telegramClient,
            dryRun = true,
            maxConcurrentRequests = 3,
        )

        dryRunService.checkNewVacancies()

        coVerify(exactly = 0) { vacancyService.fetchAndSaveNewVacancies() }
    }

    @Test
    fun `should process vacancies and send relevant ones to Telegram`() {
        runBlocking {
            val newVacancies = listOf(createTestVacancy())
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = true, score = 0.85)

            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns newVacancies
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyService.updateVacancyStatus(any(), any()) } returns Unit
            coEvery { telegramClient.sendMessage(any()) } returns true

            service.checkNewVacancies()

            coVerify { vacancyService.fetchAndSaveNewVacancies() }
            verify { vacancyService.getNewVacanciesForAnalysis() }
            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyService.updateVacancyStatus(any(), VacancyStatus.ANALYZED) }
            coVerify { telegramClient.sendMessage(any()) }
            verify { vacancyService.updateVacancyStatus(any(), VacancyStatus.SENT_TO_USER) }
        }
    }

    @Test
    fun `should skip non-relevant vacancies`() {
        runBlocking {
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = false, score = 0.3)

            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns emptyList()
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyService.updateVacancyStatus(any(), any()) } returns Unit

            service.checkNewVacancies()

            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyService.updateVacancyStatus(any(), VacancyStatus.SKIPPED) }
            coVerify(exactly = 0) { telegramClient.sendMessage(any()) }
        }
    }

    @Test
    fun `should continue processing when one vacancy fails`() {
        runBlocking {
            val vacancy1 = createTestVacancy(id = "1")
            val vacancy2 = createTestVacancy(id = "2")
            val analysis = createTestAnalysis(isRelevant = true, score = 0.8)

            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns emptyList()
            every { vacancyService.getNewVacanciesForAnalysis() } returns listOf(vacancy1, vacancy2)
            coEvery { vacancyAnalysisService.analyzeVacancy(vacancy1) } throws OllamaException.ConnectionException("Connection error")
            coEvery { vacancyAnalysisService.analyzeVacancy(vacancy2) } returns analysis
            every { vacancyService.updateVacancyStatus(any(), any()) } returns Unit
            coEvery { telegramClient.sendMessage(any()) } returns true

            service.checkNewVacancies()

            // Должен обработать обе вакансии, несмотря на ошибку первой
            coVerify { vacancyAnalysisService.analyzeVacancy(vacancy1) }
            coVerify { vacancyAnalysisService.analyzeVacancy(vacancy2) }
            verify { vacancyService.updateVacancyStatus(vacancy1, VacancyStatus.SKIPPED) }
            verify { vacancyService.updateVacancyStatus(vacancy2, VacancyStatus.ANALYZED) }
        }
    }

    @Test
    fun `should handle Telegram rate limit gracefully`() {
        runBlocking {
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = true, score = 0.85)

            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns emptyList()
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyService.updateVacancyStatus(any(), any()) } returns Unit
            coEvery { telegramClient.sendMessage(any()) } throws TelegramException.RateLimitException("Rate limit")

            service.checkNewVacancies()

            // Вакансия должна быть проанализирована, но не отправлена
            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyService.updateVacancyStatus(any(), VacancyStatus.ANALYZED) }
            // Статус SENT_TO_USER не должен быть установлен
            verify(exactly = 0) { vacancyService.updateVacancyStatus(any(), VacancyStatus.SENT_TO_USER) }
        }
    }

    @Test
    fun `should handle Telegram errors gracefully`() {
        runBlocking {
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = true, score = 0.85)

            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns emptyList()
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyService.updateVacancyStatus(any(), any()) } returns Unit
            coEvery { telegramClient.sendMessage(any()) } throws TelegramException.ConnectionException("Connection error")

            service.checkNewVacancies()

            // Должен продолжить работу, несмотря на ошибку Telegram
            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyService.updateVacancyStatus(any(), VacancyStatus.ANALYZED) }
        }
    }

    @Test
    fun `should handle empty vacancy list`() {
        runBlocking {
            coEvery { vacancyService.fetchAndSaveNewVacancies() } returns emptyList()
            every { vacancyService.getNewVacanciesForAnalysis() } returns emptyList()

            service.checkNewVacancies()

            coVerify { vacancyService.fetchAndSaveNewVacancies() }
            verify { vacancyService.getNewVacanciesForAnalysis() }
            coVerify(exactly = 0) { vacancyAnalysisService.analyzeVacancy(any()) }
        }
    }

    private fun createTestVacancy(
        id: String = "12345",
        status: VacancyStatus = VacancyStatus.NEW,
    ): Vacancy {
        return Vacancy(
            id = id,
            name = "Senior Kotlin Developer",
            employer = "Tech Corp",
            salary = "200000 - 300000 RUR",
            area = "Москва",
            url = "https://hh.ru/vacancy/$id",
            description = "Ищем Senior Kotlin Developer",
            experience = "От 3 лет",
            publishedAt = java.time.LocalDateTime.now(),
            status = status,
        )
    }

    private fun createTestAnalysis(
        isRelevant: Boolean,
        score: Double,
    ): VacancyAnalysis {
        return VacancyAnalysis(
            vacancyId = "12345",
            isRelevant = isRelevant,
            relevanceScore = score,
            reasoning = "Test reasoning",
            matchedSkills = """["Kotlin", "Spring Boot"]""",
            suggestedCoverLetter = if (isRelevant) "Test cover letter" else null,
        )
    }
}
