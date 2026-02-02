package com.hhassistant.web

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.AreaDto
import com.hhassistant.client.hh.dto.EmployerDto
import com.hhassistant.client.hh.dto.VacancyDto
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(VacancyTestController::class)
class VacancyTestControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var hhVacancyClient: HHVacancyClient

    @Test
    fun `GET search returns vacancies`() {
        coEvery { hhVacancyClient.searchVacancies(any()) } returns listOf(
            VacancyDto(
                id = "1",
                name = "Kotlin Dev",
                employer = EmployerDto(id = "e1", name = "Test Corp"),
                salary = null,
                area = AreaDto(id = "1", name = "Moscow"),
                url = "https://api.hh.ru/vacancies/1",
                description = null,
                experience = null,
                publishedAt = "2024-01-01T00:00:00",
                snippet = null,
                alternateUrl = "https://hh.ru/vacancy/1",
            ),
        )

        mockMvc.get("/api/vacancies/test/search") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.count") { value(1) }
                jsonPath("$.vacancies[0].id") { value("1") }
            }
    }

    @Test
    fun `GET details returns vacancy`() {
        coEvery { hhVacancyClient.getVacancyDetails("1") } returns VacancyDto(
            id = "1",
            name = "Kotlin Dev",
            employer = EmployerDto(id = "e1", name = "Test Corp"),
            salary = null,
            area = AreaDto(id = "1", name = "Moscow"),
            url = "https://api.hh.ru/vacancies/1",
            description = "desc",
            experience = null,
            publishedAt = "2024-01-01T00:00:00",
            snippet = null,
            alternateUrl = "https://hh.ru/vacancy/1",
        )

        mockMvc.get("/api/vacancies/test/1") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.vacancy.id") { value("1") }
                jsonPath("$.vacancy.name") { value("Kotlin Dev") }
            }
    }
}