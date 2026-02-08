package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.service.vacancy.VacancyAnalysisService
import com.hhassistant.service.vacancy.VacancyFetchService
import com.hhassistant.service.vacancy.VacancySchedulerService
import com.hhassistant.service.vacancy.VacancyService
import com.hhassistant.service.vacancy.VacancyStatusService
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
    private lateinit var skillExtractionService: com.hhassistant.service.skill.SkillExtractionService
    private lateinit var vacancyProcessingQueueService: com.hhassistant.service.vacancy.VacancyProcessingQueueService
    private lateinit var skillExtractionQueueService: com.hhassistant.service.skill.SkillExtractionQueueService
    private lateinit var vacancyRepository: com.hhassistant.repository.VacancyRepository
    private lateinit var service: VacancySchedulerService

    @BeforeEach
    fun setup() {
        vacancyFetchService = mockk<VacancyFetchService>()
        vacancyService = mockk<VacancyService>()
        vacancyAnalysisService = mockk<VacancyAnalysisService>()
        vacancyStatusService = mockk<VacancyStatusService>()
        notificationService = mockk<NotificationService>(relaxed = true)
        resumeService = mockk<ResumeService>(relaxed = true)
        metricsService = mockk(relaxed = true)
        skillExtractionService = mockk(relaxed = true)
        vacancyProcessingQueueService = mockk(relaxed = true)
        skillExtractionQueueService = mockk(relaxed = true)
        vacancyRepository = mockk(relaxed = true)
        val vacancyContentValidator = mockk<com.hhassistant.service.vacancy.VacancyContentValidator>(relaxed = true)
        val vacancyRecoveryService = mockk<com.hhassistant.service.vacancy.VacancyRecoveryService>(relaxed = true)
        val circuitBreakerStateService = mockk<com.hhassistant.service.monitoring.CircuitBreakerStateService>(relaxed = true)
        val ollamaMonitoringService = mockk<com.hhassistant.service.monitoring.OllamaMonitoringService>(relaxed = true)

        service = VacancySchedulerService(
            vacancyFetchService = vacancyFetchService,
            vacancyService = vacancyService,
            vacancyAnalysisService = vacancyAnalysisService,
            vacancyStatusService = vacancyStatusService,
            notificationService = notificationService,
            resumeService = resumeService,
            metricsService = metricsService,
            skillExtractionService = skillExtractionService,
            vacancyProcessingQueueService = vacancyProcessingQueueService,
            skillExtractionQueueService = skillExtractionQueueService,
            vacancyRepository = vacancyRepository,
            vacancyContentValidator = vacancyContentValidator,
            vacancyRecoveryService = vacancyRecoveryService,
            circuitBreakerStateService = circuitBreakerStateService,
            ollamaMonitoringService = ollamaMonitoringService,
            maxConcurrentRequests = 3,
        )
    }

    @Test
    fun `should process vacancies and send relevant ones to Telegram`() {
        runBlocking {
            val newVacancies = listOf(createTestVacancy())
            val vacanciesToAnalyze = listOf(createTestVacancy())
            val analysis = createTestAnalysis(isRelevant = true, score = 0.85)
            val fetchResult = VacancyFetchService.FetchResult(
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
            val fetchResult = VacancyFetchService.FetchResult(
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
            val fetchResult = VacancyFetchService.FetchResult(
                vacancies = emptyList(),
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult
            every { vacancyService.getNewVacanciesForAnalysis() } returns listOf(vacancy1, vacancy2)
            coEvery {
                vacancyAnalysisService.analyzeVacancy(vacancy1)
            } throws OllamaException.ConnectionException("Connection error")
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
            val fetchResult = VacancyFetchService.FetchResult(
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
