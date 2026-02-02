package com.hhassistant.web

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.service.vacancy.VacancyService
import com.hhassistant.service.vacancy.VacancyStatusService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime

@WebMvcTest(VacancyManagementController::class)
class VacancyManagementControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var vacancyService: VacancyService

    @MockkBean
    private lateinit var vacancyStatusService: VacancyStatusService

    @Test
    fun `GET unviewed vacancies returns mapped list`() {
        val vacancy = testVacancy(id = "1", status = VacancyStatus.SENT_TO_USER)
        every { vacancyService.getUnviewedVacancies() } returns listOf(vacancy)

        mockMvc.get("/api/vacancies/unviewed") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.vacancies[0].id") { value("1") }
                jsonPath("$.vacancies[0].status") { value("SENT_TO_USER") }
            }
    }

    @Test
    fun `GET vacancy by id returns 404 when not found`() {
        every { vacancyService.getVacancyById("missing") } returns null

        mockMvc.get("/api/vacancies/missing") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `GET vacancies with invalid status returns empty list`() {
        mockMvc.get("/api/vacancies") {
            param("status", "NOT_A_STATUS")
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.count") { value(0) }
                jsonPath("$.status") { value("NOT_A_STATUS") }
            }

        verify(exactly = 0) { vacancyService.findVacanciesByStatus(any()) }
    }

    @Test
    fun `POST mark-applied returns 404 when vacancy not found`() {
        every { vacancyService.getVacancyById("1") } returns null

        mockMvc.post("/api/vacancies/1/mark-applied") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }

    @Test
    fun `POST mark-applied updates status when vacancy exists`() {
        val vacancy = testVacancy(id = "1", status = VacancyStatus.NEW)
        val updatedVacancy = vacancy.withStatus(VacancyStatus.APPLIED)

        every { vacancyService.getVacancyById("1") } returns vacancy
        every { vacancyStatusService.updateVacancyStatusById("1", VacancyStatus.APPLIED) } returns updatedVacancy

        mockMvc.post("/api/vacancies/1/mark-applied") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.vacancy.id") { value("1") }
                jsonPath("$.vacancy.status") { value("APPLIED") }
                jsonPath("$.vacancy.oldStatus") { value("NEW") }
            }
    }

    private fun testVacancy(
        id: String,
        status: VacancyStatus,
    ): Vacancy {
        return Vacancy(
            id = id,
            name = "Kotlin Developer",
            employer = "Test Corp",
            salary = "100000-200000",
            area = "Moscow",
            url = "https://hh.ru/vacancy/$id",
            description = "Test description",
            experience = "3-6 years",
            publishedAt = LocalDateTime.of(2024, 1, 1, 10, 0),
            fetchedAt = LocalDateTime.of(2024, 1, 1, 10, 0),
            status = status,
        )
    }
}

