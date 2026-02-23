package com.hhassistant.vacancy.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.OllamaException
import com.hhassistant.integration.ollama.OllamaClient
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Unit-тесты VacancyAnalyzerService.
 * Проверяют логику парсинга JSON-ответа LLM (в т.ч. через analyze())
 * и валидацию результата.
 */
class VacancyAnalyzerServiceTest {

    private lateinit var ollamaClient: OllamaClient
    private lateinit var objectMapper: ObjectMapper
    private lateinit var promptConfig: PromptConfig
    private lateinit var ollamaCircuitBreaker: CircuitBreaker
    private lateinit var ollamaRetry: Retry
    private lateinit var service: VacancyAnalyzerService

    private val vacancy = Vacancy(
        id = "v1",
        name = "Kotlin Developer",
        employer = "Tech",
        salary = "200k",
        area = "Москва",
        url = "https://hh.ru/vacancy/v1",
        description = "Kotlin, Spring",
        experience = "1-3 года",
        publishedAt = LocalDateTime.now(),
        status = VacancyStatus.NEW,
    )

    private val resume = Resume(
        fileName = "resume.pdf",
        rawText = "Kotlin developer",
        structuredData = null,
        source = com.hhassistant.domain.entity.ResumeSource.MANUAL_UPLOAD,
    )

    @BeforeEach
    fun setup() {
        ollamaClient = mockk()
        objectMapper = jacksonObjectMapper()
        promptConfig = PromptConfig()
        ollamaCircuitBreaker = CircuitBreaker.ofDefaults("test")
        ollamaRetry = Retry.ofDefaults("test")
        service = VacancyAnalyzerService(
            ollamaClient = ollamaClient,
            objectMapper = objectMapper,
            promptConfig = promptConfig,
            ollamaCircuitBreaker = ollamaCircuitBreaker,
            ollamaRetry = ollamaRetry,
            minRelevanceScore = 0.6,
        )
    }

    @Test
    fun `analyze parses plain JSON response`() = runBlocking {
        val json = """{"relevance_score": 0.85, "is_relevant": true, "reasoning": "Match", "skills": ["Kotlin", "Spring"]}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.relevanceScore).isEqualTo(0.85)
        assertThat(result.isRelevant).isTrue
        assertThat(result.skills).containsExactlyInAnyOrder("Kotlin", "Spring")
        assertThat(result.reasoning).isEqualTo("Match")
    }

    @Test
    fun `analyze parses JSON in markdown code block`() = runBlocking {
        val wrapped = """Some text
```json
{"relevance_score": 0.7, "matched_skills": ["Kotlin"], "reasoning": "Ok"}
```
"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns wrapped

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.relevanceScore).isEqualTo(0.7)
        assertThat(result.skills).containsExactly("Kotlin")
        assertThat(result.reasoning).isEqualTo("Ok")
    }

    @Test
    fun `analyze uses matched_skills when skills absent`() = runBlocking {
        val json = """{"relevance_score": 0.9, "matched_skills": ["PostgreSQL", "Docker"], "reasoning": "Skills"}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.skills).containsExactlyInAnyOrder("PostgreSQL", "Docker")
    }

    @Test
    fun `analyze determines isRelevant from score when is_relevant absent`() = runBlocking {
        val json = """{"relevance_score": 0.65, "skills": ["Kotlin"], "reasoning": "Ok"}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.relevanceScore).isEqualTo(0.65)
        assertThat(result.isRelevant).isTrue
    }

    @Test
    fun `analyze treats score below min as not relevant`() = runBlocking {
        val json = """{"relevance_score": 0.3, "is_relevant": false, "skills": [], "reasoning": "No match"}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.relevanceScore).isEqualTo(0.3)
        assertThat(result.isRelevant).isFalse
    }

    @Test
    fun `analyze throws ParsingException for invalid JSON`() = runBlocking {
        coEvery { ollamaClient.chatForAnalysis(any()) } returns "not valid json {{{"

        assertThatThrownBy {
            runBlocking { service.analyze(vacancy, resume, null) }
        }.isInstanceOf(OllamaException.ParsingException::class.java)
    }

    @Test
    fun `analyze throws ParsingException when relevance_score missing`() = runBlocking {
        val json = """{"skills": ["Kotlin"], "reasoning": "Ok"}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        assertThatThrownBy {
            runBlocking { service.analyze(vacancy, resume, null) }
        }.isInstanceOf(OllamaException.ParsingException::class.java)
    }

    @Test
    fun `analyze throws ParsingException when relevance_score not a number`() = runBlocking {
        val json = """{"relevance_score": "high", "skills": ["Kotlin"]}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        assertThatThrownBy {
            runBlocking { service.analyze(vacancy, resume, null) }
        }.isInstanceOf(OllamaException.ParsingException::class.java)
    }

    @Test
    fun `analyze filters blank skills and limits to 20`() = runBlocking {
        val skills = (1..25).map { "Skill$it" } + listOf("", "  ", "x")
        val json = """{"relevance_score": 0.8, "skills": ${objectMapper.writeValueAsString(skills)}, "reasoning": "Ok"}"""
        coEvery { ollamaClient.chatForAnalysis(any()) } returns json

        val result = service.analyze(vacancy, resume, null)

        assertThat(result.skills).hasSize(20)
        assertThat(result.skills).doesNotContain("", "  ")
        assertThat(result.skills).contains("Skill1", "Skill2", "Skill20")
    }
}
