package com.hhassistant.web

import com.hhassistant.client.hh.HHOAuthService
import com.hhassistant.client.hh.dto.OAuthTokenResponse
import com.hhassistant.service.EnvFileService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(OAuthController::class)
@TestPropertySource(
    properties = [
        "hh.oauth.authorization-url=https://hh.ru/oauth/authorize",
        "hh.api.user-agent=test-agent",
    ],
)
class OAuthControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var oauthService: HHOAuthService

    @MockkBean
    private lateinit var envFileService: EnvFileService

    @Test
    fun `GET authorize returns authorization url`() {
        every { oauthService.getAuthorizationUrl(any()) } returns "https://example/auth"

        mockMvc.get("/oauth/authorize") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.authorization_url") { value("https://example/auth") }
                jsonPath("$.token_type") { value("user") }
            }
    }

    @Test
    fun `GET callback returns 400 when error provided`() {
        mockMvc.get("/oauth/callback") {
            param("error", "access_denied")
            param("error_description", "denied")
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("access_denied") }
            }
    }

    @Test
    fun `GET callback returns 400 when code is missing`() {
        mockMvc.get("/oauth/callback") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("missing_code") }
            }
    }

    @Test
    fun `GET application-token returns token and saves to env`() {
        coEvery { oauthService.getApplicationToken(any()) } returns OAuthTokenResponse(
            accessToken = "token123",
            tokenType = "bearer",
            expiresIn = null,
            refreshToken = null,
        )
        every { envFileService.updateEnvVariable("HH_ACCESS_TOKEN", any()) } returns true
        every { envFileService.updateEnvVariable("HH_TOKEN_TYPE", any()) } returns true

        mockMvc.get("/oauth/application-token") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.token_type") { value("application") }
                jsonPath("$.access_token") { value("token123") }
                jsonPath("$.auto_saved") { value(true) }
            }
    }
}