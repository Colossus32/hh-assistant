package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VacancySchedulerServiceTest {

    private lateinit var vacancyFetchService: VacancyFetchService
    private lateinit var vacancyService: VacancyService
    private lateinit var vacancyAnalysisService: VacancyAnalysisService
    private lateinit var vacancyStatusService: VacancyStatusService
    private lateinit var notificationService: NotificationService
    private lateinit var resumeService: ResumeService
    private lateinit var metricsService: com.hhassistant.metrics.MetricsService
    private lateinit var service: VacancySchedulerService

    @BeforeEach
    fun setup() {
        vacancyFetchService = mockk()
        vacancyService = mockk()
        vacancyAnalysisService = mockk()
        vacancyStatusService = mockk()
        notificationService = mockk(relaxed = true)
        resumeService = mockk(relaxed = true)
        metricsService = mockk(relaxed = true)
        service = VacancySchedulerService(
            vacancyFetchService = vacancyFetchService,
            vacancyService = vacancyService,
            vacancyAnalysisService = vacancyAnalysisService,
            vacancyStatusService = vacancyStatusService,
            notificationService = notificationService,
            resumeService = resumeService,
            metricsService = metricsService,
            dryRun = false,
            maxConcurrentRequests = 3,
        )
    }

    @Test
    fun `should skip execution in dry-run mode`() {
        val dryRunService = VacancySchedulerService(
            vacancyFetchService = vacancyFetchService,
            vacancyService = vacancyService,
            vacancyAnalysisService = vacancyAnalysisService,
            vacancyStatusService = vacancyStatusService,
            notificationService = notificationService,
            resumeService = resumeService,
            metricsService = metricsService,
            dryRun = true,
            maxConcurrentRequests = 3,
        )

        dryRunService.checkNewVacancies()

        coVerify(exactly = 0) { vacancyFetchService.fetchAndSaveNewVacancies() }
    }

    @Test
    fun `should process vacancies and send relevant ones to Telegram`() {
        runBlocking {
            val newVacancies = listOf(createTestVacancy())
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = true, score = 0.85)
            val fetchResult = com.hhassistant.service.VacancyFetchService.FetchResult(
                vacancies = newVacancies,
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyStatusService.updateVacancyStatus(any()) } returns Unit

            service.checkNewVacancies()

            coVerify { vacancyFetchService.fetchAndSaveNewVacancies() }
            verify { vacancyService.getNewVacanciesForAnalysis() }
            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyStatusService.updateVacancyStatus(any()) }
        }
    }

    @Test
    fun `should skip non-relevant vacancies`() {
        runBlocking {
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = false, score = 0.3)
            val fetchResult = com.hhassistant.service.VacancyFetchService.FetchResult(
                vacancies = emptyList(),
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult
            every { vacancyService.getNewVacanciesForAnalysis() } returns vacanciesToAnalyze
            coEvery { vacancyAnalysisService.analyzeVacancy(any()) } returns analysis
            every { vacancyStatusService.updateVacancyStatus(any()) } returns Unit

            service.checkNewVacancies()

            coVerify { vacancyAnalysisService.analyzeVacancy(any()) }
            verify { vacancyStatusService.updateVacancyStatus(any()) }
        }
    }

    @Test
    fun `should continue processing when one vacancy fails`() {
        runBlocking {
            val vacancy1 = createTestVacancy(id = "1")
            val vacancy2 = createTestVacancy(id = "2")
            val analysis = createTestAnalysis(isRelevant = true, score = 0.8)
            val fetchResult = com.hhassistant.service.VacancyFetchService.FetchResult(
                vacancies = emptyList(),
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult
            every { vacancyService.getNewVacanciesForAnalysis() } returns listOf(vacancy1, vacancy2)
            coEvery { vacancyAnalysisService.analyzeVacancy(vacancy1) } throws OllamaException.ConnectionException("Connection error")
            coEvery { vacancyAnalysisService.analyzeVacancy(vacancy2) } returns analysis
            every { vacancyStatusService.updateVacancyStatus(any()) } returns Unit

            service.checkNewVacancies()

            // Должен обработать обе вакансии, несмотря на ошибку первой
            coVerify { vacancyAnalysisService.analyzeVacancy(vacancy1) }
            coVerify { vacancyAnalysisService.analyzeVacancy(vacancy2) }
            verify { vacancyStatusService.updateVacancyStatus(any()) }
        }
    }

    @Test
    fun `should handle empty vacancy list`() {
        runBlocking {
            val fetchResult = com.hhassistant.service.VacancyFetchService.FetchResult(
                vacancies = emptyList(),
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult
            every { vacancyService.getNewVacanciesForAnalysis() } returns emptyList()

            service.checkNewVacancies()

            coVerify { vacancyFetchService.fetchAndSaveNewVacancies() }
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
