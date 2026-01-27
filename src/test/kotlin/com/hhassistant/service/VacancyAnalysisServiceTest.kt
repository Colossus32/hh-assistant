package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.domain.model.ResumeStructure
import com.hhassistant.repository.VacancyAnalysisRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VacancyAnalysisServiceTest {

    private lateinit var ollamaClient: OllamaClient
    private lateinit var resumeService: ResumeService
    private lateinit var repository: VacancyAnalysisRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var service: VacancyAnalysisService

    @BeforeEach
    fun setup() {
        ollamaClient = mockk()
        resumeService = mockk()
        repository = mockk()
        objectMapper = jacksonObjectMapper()
        service = VacancyAnalysisService(ollamaClient, resumeService, repository, objectMapper, 0.6)
    }

    @Test
    fun `should analyze vacancy and mark as relevant`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery { resumeService.loadResume() } returns resume
            every { resumeService.getResumeStructure(resume) } returns resumeStructure

            val analysisResponse = """
                {
                    "is_relevant": true,
                    "relevance_score": 0.85,
                    "reasoning": "Вакансия хорошо подходит: требуются навыки Kotlin и Spring Boot, которые есть в резюме",
                    "matched_skills": ["Kotlin", "Spring Boot", "PostgreSQL"]
                }
            """.trimIndent()

            val coverLetterResponse = "Уважаемые коллеги! Я заинтересован в позиции..."
            coEvery { ollamaClient.chat(any()) } returnsMany listOf(analysisResponse, coverLetterResponse)

            val savedAnalysis = com.hhassistant.domain.entity.VacancyAnalysis(
                id = 1L,
                vacancyId = vacancy.id,
                isRelevant = true,
                relevanceScore = 0.85,
                reasoning = "Вакансия хорошо подходит: требуются навыки Kotlin и Spring Boot, которые есть в резюме",
                matchedSkills = """["Kotlin", "Spring Boot", "PostgreSQL"]""",
                suggestedCoverLetter = coverLetterResponse,
            )

            every { repository.save(any()) } returns savedAnalysis

            val result = service.analyzeVacancy(vacancy)

            assertThat(result).isNotNull
            assertThat(result.isRelevant).isTrue
            assertThat(result.relevanceScore).isEqualTo(0.85)
            assertThat(result.matchedSkills).isNotNull
            assertThat(result.suggestedCoverLetter).isNotNull

            coVerify { ollamaClient.chat(any()) }
            verify { repository.save(any()) }
        }
    }

    @Test
    fun `should analyze vacancy and mark as not relevant`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery { resumeService.loadResume() } returns resume
            every { resumeService.getResumeStructure(resume) } returns resumeStructure

            val analysisResponse = """
                {
                    "is_relevant": false,
                    "relevance_score": 0.25,
                    "reasoning": "Вакансия не подходит: требуется опыт в Python, а в резюме только Kotlin",
                    "matched_skills": []
                }
            """.trimIndent()

            coEvery { ollamaClient.chat(any()) } returns analysisResponse

            val savedAnalysis = com.hhassistant.domain.entity.VacancyAnalysis(
                id = 1L,
                vacancyId = vacancy.id,
                isRelevant = false,
                relevanceScore = 0.25,
                reasoning = "Вакансия не подходит: требуется опыт в Python, а в резюме только Kotlin",
                matchedSkills = "[]",
                suggestedCoverLetter = null,
            )

            every { repository.save(any()) } returns savedAnalysis

            val result = service.analyzeVacancy(vacancy)

            assertThat(result).isNotNull
            assertThat(result.isRelevant).isFalse
            assertThat(result.relevanceScore).isEqualTo(0.25)
            assertThat(result.suggestedCoverLetter).isNull()

            coVerify(exactly = 1) { ollamaClient.chat(any()) } // Only analysis, no cover letter
            verify { repository.save(any()) }
        }
    }

    @Test
    fun `should check if vacancy already analyzed`() {
        val vacancy = createTestVacancy()
        val existingAnalysis = com.hhassistant.domain.entity.VacancyAnalysis(
            id = 1L,
            vacancyId = vacancy.id,
            isRelevant = true,
            relevanceScore = 0.8,
            reasoning = "Already analyzed",
            matchedSkills = null,
            suggestedCoverLetter = null,
        )

        every { repository.findByVacancyId(vacancy.id) } returns existingAnalysis

        runBlocking {
            val result = service.analyzeVacancy(vacancy)

            assertThat(result).isEqualTo(existingAnalysis)
            coVerify(exactly = 0) { ollamaClient.chat(any()) }
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    private fun createTestVacancy(): Vacancy {
        return Vacancy(
            id = "12345",
            name = "Senior Kotlin Developer",
            employer = "Tech Corp",
            salary = "200000 - 300000 RUR",
            area = "Москва",
            url = "https://hh.ru/vacancy/12345",
            description = "Ищем Senior Kotlin Developer с опытом работы с Spring Boot и PostgreSQL",
            experience = "От 3 лет",
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
    }

    private fun createTestResume(): Resume {
        return Resume(
            fileName = "resume.pdf",
            rawText = "Иван Иванов\nKotlin Developer\nНавыки: Kotlin, Spring Boot, PostgreSQL",
            structuredData = """{"skills": ["Kotlin", "Spring Boot"], "desired_salary": 200000}""",
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )
    }

    private fun createTestResumeStructure(): ResumeStructure {
        return ResumeStructure(
            skills = listOf("Kotlin", "Spring Boot", "PostgreSQL", "Docker"),
            experience = emptyList(),
            education = emptyList(),
            desiredPosition = "Senior Kotlin Developer",
            desiredSalary = 200000,
            summary = "Experienced Kotlin developer",
        )
    }
}
