package com.hhassistant.client.hh

import com.hhassistant.client.hh.dto.*
import com.hhassistant.domain.entity.SearchConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import kotlinx.coroutines.runBlocking

class HHVacancyClientTest {
    
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: HHVacancyClient
    private val objectMapper = ObjectMapper()
    
    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        
        val webClient = WebClient.builder()
            .baseUrl(mockWebServer.url("/").toString())
            .build()
        
        client = HHVacancyClient(webClient)
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
                .setHeader("Content-Type", "application/json")
        )
        
        val config = SearchConfig(
            keywords = "Kotlin Developer",
            minSalary = 150000,
            area = "1",
            experience = null,
            isActive = true
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
                .setHeader("Content-Type", "application/json")
        )
        
        val vacancy = client.getVacancyDetails("12345")
        
        assertThat(vacancy.id).isEqualTo("12345")
        assertThat(vacancy.name).isEqualTo("Kotlin Developer")
        assertThat(vacancy.description).isEqualTo("Full description here")
    }
}

