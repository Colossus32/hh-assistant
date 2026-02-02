package com.hhassistant.web

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.service.EnvFileService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(TokenTestController::class)
@TestPropertySource(properties = ["hh.api.access-token="])
class TokenTestControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var hhVacancyClient: HHVacancyClient

    @MockkBean
    private lateinit var envFileService: EnvFileService

    @Test
    fun `GET token test returns 400 when no token configured`() {
        every { envFileService.readEnvVariable("HH_ACCESS_TOKEN") } returns null

        mockMvc.get("/api/token/test") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("No token found in .env file or configuration") }
            }
    }

    @Test
    fun `GET token info returns details`() {
        every { envFileService.readEnvVariable("HH_ACCESS_TOKEN") } returns null

        mockMvc.get("/api/token/info") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.token_in_env") { value(false) }
            }
    }
}