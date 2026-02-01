package com.hhassistant.advice

import com.hhassistant.exception.HHAPIException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(TestExceptionControllerForAdviceTest::class)
@Import(GlobalExceptionHandler::class)
class GlobalExceptionHandlerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `IllegalArgumentException is mapped to 400 BAD_REQUEST`() {
        mockMvc.get("/test/ex/bad-request") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("BAD_REQUEST") }
                jsonPath("$.message") { value("boom") }
                jsonPath("$.path") { value("/test/ex/bad-request") }
            }
    }

    @Test
    fun `HHAPIException RateLimitException is mapped to 429 RATE_LIMIT_EXCEEDED`() {
        mockMvc.get("/test/ex/rate-limit") {
            accept = MediaType.APPLICATION_JSON
        }
            .andExpect {
                status { isTooManyRequests() }
                jsonPath("$.error") { value("RATE_LIMIT_EXCEEDED") }
                jsonPath("$.path") { value("/test/ex/rate-limit") }
            }
    }
}

@RestController
@RequestMapping("/test/ex")
class TestExceptionControllerForAdviceTest {
    @GetMapping("/bad-request")
    fun badRequest() {
        throw IllegalArgumentException("boom")
    }

    @GetMapping("/rate-limit")
    fun rateLimit() {
        throw HHAPIException.RateLimitException("Rate limit")
    }
}
