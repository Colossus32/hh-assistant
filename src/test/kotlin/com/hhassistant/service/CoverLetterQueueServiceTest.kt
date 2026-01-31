package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.event.CoverLetterGeneratedEvent
import com.hhassistant.event.CoverLetterGenerationFailedEvent
import com.hhassistant.event.VacancyReadyForTelegramEvent
import com.hhassistant.exception.OllamaException
import com.hhassistant.metrics.MetricsService
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime

class CoverLetterQueueServiceTest {

    private lateinit var vacancyAnalysisRepository: VacancyAnalysisRepository
    private lateinit var coverLetterGenerationService: CoverLetterGenerationService
    private lateinit var vacancyRepository: VacancyRepository
    private lateinit var vacancyStatusService: VacancyStatusService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var resumeService: ResumeService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var metricsService: MetricsService
    private lateinit var capturedEvents: MutableList<ApplicationEvent>
    private lateinit var service: CoverLetterQueueService

    @BeforeEach
    fun setUp() {
        vacancyAnalysisRepository = mockk(relaxed = true)
        coverLetterGenerationService = mockk(relaxed = true)
        vacancyRepository = mockk(relaxed = true)
        vacancyStatusService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        resumeService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper()
        metricsService = mockk(relaxed = true)
        capturedEvents = mutableListOf()

        every { eventPublisher.publishEvent(any()) } answers {
            val event = arg<ApplicationEvent>(0)
            capturedEvents.add(event)
        }

        service = CoverLetterQueueService(
            vacancyAnalysisRepository = vacancyAnalysisRepository,
            coverLetterGenerationService = coverLetterGenerationService,
            vacancyRepository = vacancyRepository,
            vacancyStatusService = vacancyStatusService,
            eventPublisher = eventPublisher,
            resumeService = resumeService,
            objectMapper = objectMapper,
            metricsService = metricsService,
            queueEnabled = true,
            maxRetries = 3,
            maxConcurrent = 2,
        )
    }

    @Test
    fun `should enqueue vacancy for cover letter generation`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysisId = 1L
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            status = CoverLetterGenerationStatus.NOT_ATTEMPTED,
        ).copy(id = analysisId)

        every { vacancyAnalysisRepository.findById(analysisId) } returns java.util.Optional.of(analysis)
        every { vacancyRepository.findById(vacancy.id) } returns java.util.Optional.of(vacancy)
        coEvery { resumeService.loadResume() } returns createTestResume()
        every { resumeService.getResumeStructure(any()) } returns createTestResumeStructure()

        // When
        service.enqueue(analysisId, vacancy.id)

        // Then
        delay(100) // Даем время на обработку
        verify { vacancyAnalysisRepository.findById(analysisId) }
    }

    @Test
    fun `should publish CoverLetterGeneratedEvent on successful generation`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysisId = 1L
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            status = CoverLetterGenerationStatus.NOT_ATTEMPTED,
        ).copy(id = analysisId)

        every { vacancyAnalysisRepository.findById(analysisId) } returns java.util.Optional.of(analysis)
        every { vacancyRepository.findById(vacancy.id) } returns java.util.Optional.of(vacancy)
        coEvery { resumeService.loadResume() } returns createTestResume()
        every { resumeService.getResumeStructure(any()) } returns createTestResumeStructure()
        coEvery { coverLetterGenerationService.generateCoverLetter(any(), any(), any(), any()) } returns "Test cover letter"
        every { vacancyAnalysisRepository.save(any()) } answers { arg(0) }

        // When
        service.enqueue(analysisId, vacancy.id)

        // Then
        delay(800) // Даем время на обработку (очередь работает на Dispatchers.Default)
        val coverLetterGeneratedEvents = capturedEvents.filterIsInstance<CoverLetterGeneratedEvent>()
        assertThat(coverLetterGeneratedEvents.size).isEqualTo(1)
        assertThat(coverLetterGeneratedEvents[0].vacancy.id).isEqualTo(vacancy.id)
    }

    @Test
    fun `should publish VacancyReadyForTelegramEvent on successful generation`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysisId = 1L
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            status = CoverLetterGenerationStatus.NOT_ATTEMPTED,
        ).copy(id = analysisId)

        every { vacancyAnalysisRepository.findById(analysisId) } returns java.util.Optional.of(analysis)
        every { vacancyRepository.findById(vacancy.id) } returns java.util.Optional.of(vacancy)
        coEvery { resumeService.loadResume() } returns createTestResume()
        every { resumeService.getResumeStructure(any()) } returns createTestResumeStructure()
        coEvery { coverLetterGenerationService.generateCoverLetter(any(), any(), any(), any()) } returns "Test cover letter"
        every { vacancyAnalysisRepository.save(any()) } answers { arg(0) }

        // When
        service.enqueue(analysisId, vacancy.id)

        // Then
        delay(800)
        val readyEvents = capturedEvents.filterIsInstance<VacancyReadyForTelegramEvent>()
        assertThat(readyEvents.size).isEqualTo(1)
        assertThat(readyEvents[0].vacancy.id).isEqualTo(vacancy.id)
        assertThat(readyEvents[0].analysis.hasCoverLetter()).isTrue
    }

    @Test
    fun `should retry on failure and publish CoverLetterGenerationFailedEvent after max retries`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysisId = 1L
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            status = CoverLetterGenerationStatus.NOT_ATTEMPTED,
        ).copy(id = analysisId)

        every { vacancyAnalysisRepository.findById(analysisId) } returns java.util.Optional.of(analysis)
        every { vacancyRepository.findById(vacancy.id) } returns java.util.Optional.of(vacancy)
        coEvery { resumeService.loadResume() } returns createTestResume()
        every { resumeService.getResumeStructure(any()) } returns createTestResumeStructure()
        coEvery { coverLetterGenerationService.generateCoverLetter(any(), any(), any(), any()) } throws OllamaException.CoverLetterGenerationException("Failed", RuntimeException())
        every { vacancyAnalysisRepository.save(any()) } answers { arg(0) }

        // When
        service.enqueue(analysisId, vacancy.id)

        // Then
        delay(2500) // Даем время на все попытки (очередь + несколько re-enqueue)
        val failedEvents = capturedEvents.filterIsInstance<CoverLetterGenerationFailedEvent>()
        assertThat(failedEvents).isNotEmpty
        val lastFailedEvent = failedEvents.last()
        assertThat(lastFailedEvent.attempts).isEqualTo(3)
        
        // После всех попыток должна быть отправлена вакансия без письма
        val readyEvents = capturedEvents.filterIsInstance<VacancyReadyForTelegramEvent>()
        assertThat(readyEvents.size).isEqualTo(1)
        assertThat(readyEvents[0].analysis.hasCoverLetter()).isFalse
    }

    @Test
    fun `should publish VacancyReadyForTelegramEvent without cover letter after max retries`() = runBlocking {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysisId = 1L
        val analysis = createTestAnalysis(
            vacancyId = vacancy.id,
            isRelevant = true,
            score = 0.85,
            status = CoverLetterGenerationStatus.NOT_ATTEMPTED,
        ).copy(id = analysisId)

        every { vacancyAnalysisRepository.findById(analysisId) } returns java.util.Optional.of(analysis)
        every { vacancyRepository.findById(vacancy.id) } returns java.util.Optional.of(vacancy)
        coEvery { resumeService.loadResume() } returns createTestResume()
        every { resumeService.getResumeStructure(any()) } returns createTestResumeStructure()
        coEvery { coverLetterGenerationService.generateCoverLetter(any(), any(), any(), any()) } throws OllamaException.CoverLetterGenerationException("Failed", RuntimeException())
        every { vacancyAnalysisRepository.save(any()) } answers { arg(0) }

        // When
        service.enqueue(analysisId, vacancy.id)

        // Then
        delay(2500)
        val readyEvents = capturedEvents.filterIsInstance<VacancyReadyForTelegramEvent>()
        assertThat(readyEvents.size).isEqualTo(1)
        assertThat(readyEvents[0].analysis.hasCoverLetter()).isFalse
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
        status: CoverLetterGenerationStatus = CoverLetterGenerationStatus.NOT_ATTEMPTED,
    ): VacancyAnalysis {
        return VacancyAnalysis(
            vacancyId = vacancyId,
            isRelevant = isRelevant,
            relevanceScore = score,
            reasoning = "Test reasoning",
            matchedSkills = "[]",
            suggestedCoverLetter = null,
            coverLetterGenerationStatus = status,
            coverLetterAttempts = 0,
            coverLetterLastAttemptAt = null,
        )
    }

    private fun createTestResume(): Resume {
        return Resume(
            fileName = "resume.pdf",
            rawText = "Test resume",
            structuredData = "{}",
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )
    }

    private fun createTestResumeStructure(): com.hhassistant.domain.model.ResumeStructure {
        return com.hhassistant.domain.model.ResumeStructure(
            skills = listOf("Java", "Kotlin"),
            experience = emptyList(),
            education = emptyList(),
            desiredPosition = "Developer",
            desiredSalary = 100000,
            summary = "Test summary",
        )
    }
}
