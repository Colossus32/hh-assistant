package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.vacancy.service.VacancyFetchService
import com.hhassistant.vacancy.service.VacancySchedulerService
import com.hhassistant.vacancy.service.VacancyService
import com.hhassistant.vacancy.service.VacancyStatusService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Тесты VacancySchedulerService.
 *
 * Текущая архитектура:
 * - checkNewVacancies() вызывает vacancyFetchService.fetchAndSaveNewVacancies().
 *   VacancyFetchService сохраняет вакансии и передаёт их в VacancyProcessingQueueService.
 *   Анализ выполняется в очереди, не в scheduler.
 * - processQueuedVacancies() подхватывает QUEUED из БД и добавляет в очередь.
 */
class VacancySchedulerServiceTest {

    private lateinit var vacancyFetchService: VacancyFetchService
    private lateinit var vacancyService: VacancyService
    private lateinit var vacancyStatusService: VacancyStatusService
    private lateinit var notificationService: NotificationService
    private lateinit var resumeService: ResumeService
    private lateinit var metricsService: com.hhassistant.monitoring.metrics.MetricsService
    private lateinit var skillExtractionService: com.hhassistant.analysis.service.SkillExtractionService
    private lateinit var vacancyProcessingQueueService: com.hhassistant.vacancy.service.VacancyProcessingQueueService
    private lateinit var skillExtractionQueueService: com.hhassistant.analysis.service.SkillExtractionQueueService
    private lateinit var vacancyRepository: com.hhassistant.vacancy.repository.VacancyRepository
    private lateinit var service: VacancySchedulerService

    @BeforeEach
    fun setup() {
        vacancyFetchService = mockk<VacancyFetchService>()
        vacancyService = mockk<VacancyService>()
        vacancyStatusService = mockk<VacancyStatusService>()
        notificationService = mockk<NotificationService>(relaxed = true)
        resumeService = mockk<ResumeService>(relaxed = true)
        metricsService = mockk(relaxed = true)
        skillExtractionService = mockk(relaxed = true)
        vacancyProcessingQueueService = mockk(relaxed = true)
        skillExtractionQueueService = mockk(relaxed = true)
        vacancyRepository = mockk(relaxed = true)
        val vacancyContentValidator = mockk<com.hhassistant.vacancy.service.VacancyContentValidator>(relaxed = true)
        val vacancyRecoveryService = mockk<com.hhassistant.vacancy.service.VacancyRecoveryService>(relaxed = true)
        val circuitBreakerStateService = mockk<com.hhassistant.monitoring.service.CircuitBreakerStateService>(relaxed = true)
        val ollamaMonitoringService = mockk<com.hhassistant.monitoring.service.OllamaMonitoringService>(relaxed = true)

        service = VacancySchedulerService(
            vacancyFetchService = vacancyFetchService,
            vacancyService = vacancyService,
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
        )
    }

    @Test
    fun `checkNewVacancies calls fetchAndSaveNewVacancies and does not call analysis directly`() {
        runBlocking {
            val newVacancies = listOf(createTestVacancy())
            val fetchResult = VacancyFetchService.FetchResult(
                vacancies = newVacancies,
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult

            service.checkNewVacancies()
            delay(300)

            coVerify { vacancyFetchService.fetchAndSaveNewVacancies() }
            verify(exactly = 0) { vacancyService.getNewVacanciesForAnalysis() }
        }
    }

    @Test
    fun `checkNewVacancies handles empty fetch result`() {
        runBlocking {
            val fetchResult = VacancyFetchService.FetchResult(
                vacancies = emptyList(),
                searchKeywords = listOf("Kotlin"),
            )

            coEvery { vacancyFetchService.fetchAndSaveNewVacancies() } returns fetchResult

            service.checkNewVacancies()
            delay(300)

            coVerify { vacancyFetchService.fetchAndSaveNewVacancies() }
            verify(exactly = 0) { vacancyService.getNewVacanciesForAnalysis() }
        }
    }

    @Test
    fun `processQueuedVacancies fetches queued vacancies and enqueues to processing queue`() {
        runBlocking {
            val queuedVacancies = listOf(
                createTestVacancy(id = "1", status = VacancyStatus.QUEUED),
                createTestVacancy(id = "2", status = VacancyStatus.QUEUED),
            )

            every { vacancyService.getQueuedVacanciesForProcessing(limit = 50) } returns queuedVacancies
            coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 2

            service.processQueuedVacancies()
            delay(300)

            verify { vacancyService.getQueuedVacanciesForProcessing(limit = 50) }
            coVerify { vacancyProcessingQueueService.enqueueBatch(listOf("1", "2")) }
        }
    }

    @Test
    fun `processQueuedVacancies does nothing when no queued vacancies`() {
        runBlocking {
            every { vacancyService.getQueuedVacanciesForProcessing(limit = 50) } returns emptyList()

            service.processQueuedVacancies()
            delay(300)

            verify { vacancyService.getQueuedVacanciesForProcessing(limit = 50) }
            coVerify(exactly = 0) { vacancyProcessingQueueService.enqueueBatch(any()) }
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
}
