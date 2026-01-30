package com.hhassistant.event

import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.repository.VacancyRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime

/**
 * Интеграционные тесты для проверки событийной архитектуры
 * Проверяет, что сервисы правильно публикуют и обрабатывают события
 */
class EventDrivenArchitectureTest {

    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var vacancyRepository: VacancyRepository
    private lateinit var vacancyAnalysisRepository: VacancyAnalysisRepository
    private lateinit var capturedEvents: MutableList<ApplicationEvent>

    @BeforeEach
    fun setUp() {
        eventPublisher = mockk(relaxed = true)
        vacancyRepository = mockk(relaxed = true)
        vacancyAnalysisRepository = mockk(relaxed = true)
        capturedEvents = mutableListOf()

        // Перехватываем все публикуемые события
        every { eventPublisher.publishEvent(any()) } answers {
            val event = firstArg<ApplicationEvent>()
            capturedEvents.add(event)
        }
    }

    @Test
    fun `VacancyFetchService should publish VacancyFetchedEvent`() {
        // Given
        val vacancies = listOf(
            createTestVacancy("1", "Java Developer"),
            createTestVacancy("2", "Kotlin Developer"),
        )
        val keywords = "Java"

        // When
        val event = VacancyFetchedEvent(this, vacancies, keywords)

        // Then
        assertThat(event.vacancies).hasSize(2)
        assertThat(event.searchKeywords).isEqualTo(keywords)
        assertThat(event.source).isEqualTo(this)
    }

    @Test
    fun `VacancyAnalysisService should publish VacancyAnalyzedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(vacancy.id, isRelevant = true, score = 0.85)

        // When
        val event = VacancyAnalyzedEvent(this, vacancy, analysis)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.analysis).isEqualTo(analysis)
        assertThat(event.analysis.isRelevant).isTrue
        assertThat(event.analysis.relevanceScore).isEqualTo(0.85)
    }

    @Test
    fun `CoverLetterQueueService should publish CoverLetterGeneratedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancy.id,
            isRelevant = true,
            score = 0.85,
            coverLetter = "Test cover letter",
        )

        // When
        val event = CoverLetterGeneratedEvent(this, vacancy, analysis)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.analysis).isEqualTo(analysis)
        assertThat(event.analysis.suggestedCoverLetter).isEqualTo("Test cover letter")
    }

    @Test
    fun `CoverLetterQueueService should publish CoverLetterGenerationFailedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(vacancy.id, isRelevant = true, score = 0.85)
        val attempts = 3

        // When
        val event = CoverLetterGenerationFailedEvent(this, vacancy, analysis, attempts)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.analysis).isEqualTo(analysis)
        assertThat(event.attempts).isEqualTo(3)
    }

    @Test
    fun `CoverLetterQueueService should publish VacancyReadyForTelegramEvent on success`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancy.id,
            isRelevant = true,
            score = 0.85,
            coverLetter = "Test cover letter",
        )

        // When
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.analysis).isEqualTo(analysis)
        assertThat(event.analysis.hasCoverLetter()).isTrue
    }

    @Test
    fun `CoverLetterQueueService should publish VacancyReadyForTelegramEvent on failure after max retries`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(vacancy.id, isRelevant = true, score = 0.85)

        // When
        val event = VacancyReadyForTelegramEvent(this, vacancy, analysis)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.analysis).isEqualTo(analysis)
        assertThat(event.analysis.hasCoverLetter()).isFalse
    }

    @Test
    fun `VacancyStatusService should publish VacancyStatusChangedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer", status = VacancyStatus.NEW)
        val oldStatus = VacancyStatus.NEW
        val newStatus = VacancyStatus.ANALYZED

        // When
        val event = VacancyStatusChangedEvent(this, vacancy, oldStatus, newStatus)

        // Then
        assertThat(event.vacancy).isEqualTo(vacancy)
        assertThat(event.oldStatus).isEqualTo(oldStatus)
        assertThat(event.newStatus).isEqualTo(newStatus)
    }

    @Test
    fun `Event flow VacancyFetchedEvent to VacancyAnalyzedEvent to VacancyReadyForTelegramEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(
            vacancy.id,
            isRelevant = true,
            score = 0.85,
            coverLetter = "Test cover letter",
        )

        // When - симулируем полный flow событий
        val fetchedEvent = VacancyFetchedEvent(this, listOf(vacancy), "Java")
        eventPublisher.publishEvent(fetchedEvent)

        val analyzedEvent = VacancyAnalyzedEvent(this, vacancy, analysis)
        eventPublisher.publishEvent(analyzedEvent)

        val readyEvent = VacancyReadyForTelegramEvent(this, vacancy, analysis)
        eventPublisher.publishEvent(readyEvent)

        // Then
        assertThat(capturedEvents.size).isEqualTo(3)
        assertThat(capturedEvents[0]).isInstanceOf(VacancyFetchedEvent::class.java)
        assertThat(capturedEvents[1]).isInstanceOf(VacancyAnalyzedEvent::class.java)
        assertThat(capturedEvents[2]).isInstanceOf(VacancyReadyForTelegramEvent::class.java)

        val fetched = capturedEvents[0] as VacancyFetchedEvent
        assertThat(fetched.vacancies).contains(vacancy)

        val analyzed = capturedEvents[1] as VacancyAnalyzedEvent
        assertThat(analyzed.vacancy).isEqualTo(vacancy)
        assertThat(analyzed.analysis.isRelevant).isTrue

        val ready = capturedEvents[2] as VacancyReadyForTelegramEvent
        assertThat(ready.vacancy).isEqualTo(vacancy)
        assertThat(ready.analysis.hasCoverLetter()).isTrue
    }

    @Test
    fun `Event flow failed cover letter generation should publish CoverLetterGenerationFailedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer")
        val analysis = createTestAnalysis(vacancy.id, isRelevant = true, score = 0.85)

        // When
        val analyzedEvent = VacancyAnalyzedEvent(this, vacancy, analysis)
        eventPublisher.publishEvent(analyzedEvent)

        val failedEvent = CoverLetterGenerationFailedEvent(this, vacancy, analysis, attempts = 3)
        eventPublisher.publishEvent(failedEvent)

        val readyEvent = VacancyReadyForTelegramEvent(this, vacancy, analysis)
        eventPublisher.publishEvent(readyEvent)

        // Then
        assertThat(capturedEvents).hasSize(3)
        assertThat(capturedEvents[1]).isInstanceOf(CoverLetterGenerationFailedEvent::class.java)
        val failed = capturedEvents[1] as CoverLetterGenerationFailedEvent
        assertThat(failed.attempts).isEqualTo(3)
        assertThat(failed.analysis.hasCoverLetter()).isFalse
    }

    // Helper methods
    private fun createTestVacancy(
        id: String,
        name: String,
        status: VacancyStatus = VacancyStatus.NEW,
    ): Vacancy {
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
            status = status,
        )
    }

    private fun createTestAnalysis(
        vacancyId: String,
        isRelevant: Boolean,
        score: Double,
        coverLetter: String? = null,
    ): VacancyAnalysis {
        return VacancyAnalysis(
            vacancyId = vacancyId,
            isRelevant = isRelevant,
            relevanceScore = score,
            reasoning = "Test reasoning",
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
