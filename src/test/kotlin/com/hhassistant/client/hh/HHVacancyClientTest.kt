package com.hhassistant.client.hh

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import com.hhassistant.ratelimit.RateLimitService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class HHVacancyClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: HHVacancyClient
    private lateinit var rateLimitService: RateLimitService
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build()

        rateLimitService = mockk<RateLimitService>(relaxed = true)
        coEvery { rateLimitService.tryConsume() } returns true

        val vacancyDetailsCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder<String, com.hhassistant.client.hh.dto.VacancyDto>()
            .build<String, com.hhassistant.client.hh.dto.VacancyDto>()

        client = HHVacancyClient(webClient, perPage = 50, defaultPage = 0, rateLimitService, vacancyDetailsCache)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `should parse vacancy search response`() = runBlocking {
        val responseJson = """
        {
          "items": [
            {
              "id": "12345",
              "name": "Kotlin Developer",
              "employer": {
                "id": "123",
                "name": "Tech Corp"
              },
              "salary": {
                "from": 150000,
                "to": 200000,
                "currency": "RUR"
              },
              "area": {
                "id": "1",
                "name": "Москва"
              },
              "url": "https://hh.ru/vacancy/12345",
              "description": "Looking for Kotlin developer",
              "experience": {
                "id": "between3And6",
                "name": "От 3 до 6 лет"
              },
              "published_at": "2024-01-01T10:00:00Z"
            }
          ],
          "found": 1,
          "pages": 1,
          "per_page": 50,
          "page": 0
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setBody(responseJson)
                .setHeader("Content-Type", "application/json"),
        )

        val config = SearchConfig(
            keywords = "Kotlin Developer",
            minSalary = 150000,
            maxSalary = null,
            area = "1",
            experience = null,
            isActive = true,
        )

        val vacancies = client.searchVacancies(config)

        assertThat(vacancies).hasSize(1)
        assertThat(vacancies[0].id).isEqualTo("12345")
        assertThat(vacancies[0].name).isEqualTo("Kotlin Developer")
        assertThat(vacancies[0].employer?.name).isEqualTo("Tech Corp")
    }

    @Test
    fun `should get vacancy details`() = runBlocking {
        val vacancyJson = """
        {
          "id": "12345",
          "name": "Kotlin Developer",
          "employer": {
            "id": "123",
            "name": "Tech Corp"
          },
          "salary": {
            "from": 150000,
            "to": 200000,
            "currency": "RUR"
          },
          "area": {
            "id": "1",
            "name": "Москва"
          },
          "url": "https://hh.ru/vacancy/12345",
          "description": "Full description here",
          "experience": {
            "id": "between3And6",
            "name": "От 3 до 6 лет"
          },
          "published_at": "2024-01-01T10:00:00Z"
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setBody(vacancyJson)
                .setHeader("Content-Type", "application/json"),
        )

        val vacancy = client.getVacancyDetails("12345")

        assertThat(vacancy.id).isEqualTo("12345")
        assertThat(vacancy.name).isEqualTo("Kotlin Developer")
        assertThat(vacancy.description).isEqualTo("Full description here")
    }

    @Test
    fun `should throw UnauthorizedException on 401`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized"),
        )

        val config = SearchConfig(
            keywords = "Kotlin",
            minSalary = null,
            maxSalary = null,
            area = null,
            experience = null,
            isActive = true,
        )

        assertThatThrownBy {
            runBlocking {
                client.searchVacancies(config)
            }
        }.isInstanceOf(HHAPIException.UnauthorizedException::class.java)
            .hasMessageContaining("Unauthorized access")
    }

    @Test
    fun `should throw NotFoundException on 404`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found"),
        )

        assertThatThrownBy {
            runBlocking {
                client.getVacancyDetails("99999")
            }
        }.isInstanceOf(HHAPIException.NotFoundException::class.java)
            .hasMessageContaining("Resource not found")
    }

    @Test
    fun `should throw RateLimitException on 429`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("Too Many Requests"),
        )

        val config = SearchConfig(
            keywords = "Kotlin",
            minSalary = null,
            maxSalary = null,
            area = null,
            experience = null,
            isActive = true,
        )

        assertThatThrownBy {
            runBlocking {
                client.searchVacancies(config)
            }
        }.isInstanceOf(HHAPIException.RateLimitException::class.java)
            .hasMessageContaining("Rate limit exceeded")
    }

    @Test
    fun `should throw ConnectionException on 500`() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"),
        )

        val config = SearchConfig(
            keywords = "Kotlin",
            minSalary = null,
            maxSalary = null,
            area = null,
            experience = null,
            isActive = true,
        )

        assertThatThrownBy {
            runBlocking {
                client.searchVacancies(config)
            }
        }.isInstanceOf(HHAPIException.ConnectionException::class.java)
            .hasMessageContaining("Server error")
    }
}
