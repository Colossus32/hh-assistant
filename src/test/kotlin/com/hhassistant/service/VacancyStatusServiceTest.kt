package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.event.VacancyStatusChangedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.*

class VacancyStatusServiceTest {

    private lateinit var vacancyRepository: com.hhassistant.repository.VacancyRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: VacancyStatusService
    private lateinit var capturedEvents: MutableList<ApplicationEvent>

    @BeforeEach
    fun setUp() {
        vacancyRepository = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        capturedEvents = mutableListOf()

        every { eventPublisher.publishEvent(any()) } answers {
            val event = arg<ApplicationEvent>(0)
            capturedEvents.add(event)
        }

        service = VacancyStatusService(vacancyRepository, eventPublisher)
    }

    @Test
    fun `should update vacancy status and publish VacancyStatusChangedEvent`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer", VacancyStatus.NEW)
        val updatedVacancy = vacancy.withStatus(VacancyStatus.ANALYZED)

        every { vacancyRepository.findById(vacancy.id) } returns Optional.of(vacancy)
        every { vacancyRepository.save(any()) } returns updatedVacancy

        // When
        service.updateVacancyStatus(updatedVacancy)

        // Then
        verify(exactly = 1) { vacancyRepository.findById(vacancy.id) }
        verify(exactly = 1) { vacancyRepository.save(updatedVacancy) }
        assertThat(capturedEvents).hasSize(1)
        assertThat(capturedEvents[0]).isInstanceOf(VacancyStatusChangedEvent::class.java)

        val event = capturedEvents[0] as VacancyStatusChangedEvent
        assertThat(event.vacancy).isEqualTo(updatedVacancy)
        assertThat(event.oldStatus).isEqualTo(VacancyStatus.NEW)
        assertThat(event.newStatus).isEqualTo(VacancyStatus.ANALYZED)
    }

    @Test
    fun `should publish event with null oldStatus when vacancy is new`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer", VacancyStatus.NEW)
        val updatedVacancy = vacancy.withStatus(VacancyStatus.ANALYZED)

        every { vacancyRepository.findById(vacancy.id) } returns Optional.empty()
        every { vacancyRepository.save(any()) } returns updatedVacancy

        // When
        service.updateVacancyStatus(updatedVacancy)

        // Then
        val event = capturedEvents[0] as VacancyStatusChangedEvent
        assertThat(event.oldStatus).isNull()
        assertThat(event.newStatus).isEqualTo(VacancyStatus.ANALYZED)
    }

    @Test
    fun `should update vacancy status by ID`() {
        // Given
        val vacancy = createTestVacancy("1", "Java Developer", VacancyStatus.NEW)
        val updatedVacancy = vacancy.withStatus(VacancyStatus.APPLIED)

        every { vacancyRepository.findById("1") } returnsMany listOf(
            Optional.of(vacancy),
            Optional.of(updatedVacancy),
        )
        every { vacancyRepository.save(any()) } returns updatedVacancy

        // When
        val result = service.updateVacancyStatusById("1", VacancyStatus.APPLIED)

        // Then
        assertThat(result).isNotNull
        assertThat(result!!.status).isEqualTo(VacancyStatus.APPLIED)
        verify(exactly = 1) { vacancyRepository.save(any()) }
        assertThat(capturedEvents).hasSize(1)
    }

    @Test
    fun `should return null when updating non-existent vacancy`() {
        // Given
        every { vacancyRepository.findById("999") } returns Optional.empty()

        // When
        val result = service.updateVacancyStatusById("999", VacancyStatus.APPLIED)

        // Then
        assertThat(result).isNull()
        verify(exactly = 0) { vacancyRepository.save(any()) }
        assertThat(capturedEvents.size).isEqualTo(0)
    }

    // Helper methods
    private fun createTestVacancy(id: String, name: String, status: VacancyStatus): Vacancy {
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
}
