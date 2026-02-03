package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.domain.model.ResumeStructure
import com.hhassistant.exception.OllamaException
import com.hhassistant.repository.VacancyAnalysisRepository
import com.hhassistant.service.resume.ResumeService
import com.hhassistant.service.skill.SkillExtractionService
import com.hhassistant.service.util.AnalysisTimeService
import com.hhassistant.service.vacancy.VacancyAnalysisService
import com.hhassistant.service.vacancy.VacancyContentValidator
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VacancyAnalysisServiceTest {

    private lateinit var ollamaClient: OllamaClient
    private lateinit var resumeService: ResumeService
    private lateinit var repository: VacancyAnalysisRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var promptConfig: PromptConfig
    private lateinit var vacancyContentValidator: VacancyContentValidator
    private lateinit var metricsService: com.hhassistant.metrics.MetricsService
    private lateinit var analysisTimeService: AnalysisTimeService
    private lateinit var skillExtractionService: SkillExtractionService
    private lateinit var ollamaCircuitBreaker: CircuitBreaker
    private lateinit var ollamaRetry: Retry
    private lateinit var service: VacancyAnalysisService

    @BeforeEach
    fun setup() {
        ollamaClient = mockk(relaxed = true)
        resumeService = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper()
        promptConfig = PromptConfig()
        vacancyContentValidator = mockk(relaxed = true)
        metricsService = mockk(relaxed = true)
        analysisTimeService = mockk(relaxed = true)
        skillExtractionService = mockk(relaxed = true)
        ollamaCircuitBreaker = CircuitBreaker.ofDefaults("ollamaTest")
        ollamaRetry = Retry.ofDefaults("ollamaTest")
        service = VacancyAnalysisService(
            ollamaClient = ollamaClient,
            resumeService = resumeService,
            repository = repository,
            objectMapper = objectMapper,
            promptConfig = promptConfig,
            vacancyContentValidator = vacancyContentValidator,
            metricsService = metricsService,
            analysisTimeService = analysisTimeService,
            skillExtractionService = skillExtractionService,
            ollamaCircuitBreaker = ollamaCircuitBreaker,
            ollamaRetry = ollamaRetry,
            minRelevanceScore = 0.6,
        )
    }

    @Test
    fun `should analyze vacancy and mark as relevant`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery {
                vacancyContentValidator.validate(vacancy)
            } returns VacancyContentValidator.ValidationResult(isValid = true, rejectionReason = null)
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

            coEvery { ollamaClient.chat(any()) } returns analysisResponse

            val savedAnalysis = com.hhassistant.domain.entity.VacancyAnalysis(
                id = 1L,
                vacancyId = vacancy.id,
                isRelevant = true,
                relevanceScore = 0.85,
                reasoning = "Вакансия хорошо подходит: требуются навыки Kotlin и Spring Boot, которые есть в резюме",
                matchedSkills = """["Kotlin", "Spring Boot", "PostgreSQL"]""",
                suggestedCoverLetter = null,
                coverLetterGenerationStatus = com.hhassistant.domain.entity.CoverLetterGenerationStatus.RETRY_QUEUED,
            )

            every { repository.save(any()) } returns savedAnalysis

            val result = service.analyzeVacancy(vacancy)

            assertThat(result as Any).isNotNull
            assertThat(result.isRelevant).isTrue
            assertThat(result.relevanceScore).isEqualTo(0.85)
            assertThat(result.matchedSkills as Any).isNotNull
            assertThat(result.suggestedCoverLetter).isNull()

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
            coEvery {
                vacancyContentValidator.validate(vacancy)
            } returns VacancyContentValidator.ValidationResult(isValid = true, rejectionReason = null)
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

            val result: com.hhassistant.domain.entity.VacancyAnalysis? = service.analyzeVacancy(vacancy)

            assertThat(result as Any).isNotNull
            assertThat(result!!.isRelevant).isFalse
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
            val result: com.hhassistant.domain.entity.VacancyAnalysis? = service.analyzeVacancy(vacancy)

            assertThat(result).isEqualTo(existingAnalysis)
            coVerify(exactly = 0) { ollamaClient.chat(any()) }
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    @Test
    fun `should throw OllamaException when LLM connection fails`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery {
            vacancyContentValidator.validate(vacancy)
        } returns com.hhassistant.service.vacancy.VacancyContentValidator.ValidationResult(
            isValid = true,
            rejectionReason = null,
        )
        coEvery { resumeService.loadResume() } returns resume
        every { resumeService.getResumeStructure(resume) } returns resumeStructure

        coEvery { ollamaClient.chat(any()) } throws RuntimeException("Connection refused")

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(OllamaException.ConnectionException::class.java)
            .hasMessageContaining("Failed to connect to Ollama service")

        coVerify { ollamaClient.chat(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should throw OllamaException when LLM returns invalid JSON`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery {
            vacancyContentValidator.validate(vacancy)
        } returns com.hhassistant.service.vacancy.VacancyContentValidator.ValidationResult(
            isValid = true,
            rejectionReason = null,
        )
        coEvery { resumeService.loadResume() } returns resume
        every { resumeService.getResumeStructure(resume) } returns resumeStructure

        val invalidResponse = "This is not a valid JSON response"
        coEvery { ollamaClient.chat(any()) } returns invalidResponse

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(OllamaException.ParsingException::class.java)

        coVerify { ollamaClient.chat(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should throw OllamaException when relevance score is out of range`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery {
            vacancyContentValidator.validate(vacancy)
        } returns com.hhassistant.service.vacancy.VacancyContentValidator.ValidationResult(
            isValid = true,
            rejectionReason = null,
        )
        coEvery { resumeService.loadResume() } returns resume
        every { resumeService.getResumeStructure(resume) } returns resumeStructure

        val invalidResponse = """
            {
                "is_relevant": true,
                "relevance_score": 1.5,
                "reasoning": "Test",
                "matched_skills": []
            }
        """.trimIndent()

        coEvery { ollamaClient.chat(any()) } returns invalidResponse

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Relevance score must be between 0.0 and 1.0")

        coVerify { ollamaClient.chat(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should handle cover letter generation failure gracefully`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery {
                vacancyContentValidator.validate(vacancy)
            } returns VacancyContentValidator.ValidationResult(isValid = true, rejectionReason = null)
            coEvery { resumeService.loadResume() } returns resume
            every { resumeService.getResumeStructure(resume) } returns resumeStructure

            val analysisResponse = """
                {
                    "is_relevant": true,
                    "relevance_score": 0.85,
                    "reasoning": "Вакансия хорошо подходит",
                    "matched_skills": ["Kotlin", "Spring Boot"]
                }
            """.trimIndent()

            // Анализ выполняется один раз; сопроводительное письмо генерируется асинхронно в очереди
            coEvery { ollamaClient.chat(any()) } returns analysisResponse

            val savedAnalysis = com.hhassistant.domain.entity.VacancyAnalysis(
                id = 1L,
                vacancyId = vacancy.id,
                isRelevant = true,
                relevanceScore = 0.85,
                reasoning = "Вакансия хорошо подходит",
                matchedSkills = """["Kotlin", "Spring Boot"]""",
                suggestedCoverLetter = null,
                coverLetterGenerationStatus = com.hhassistant.domain.entity.CoverLetterGenerationStatus.RETRY_QUEUED,
            )

            every { repository.save(any()) } returns savedAnalysis

            val result: com.hhassistant.domain.entity.VacancyAnalysis? = service.analyzeVacancy(vacancy)

            assertThat(result as Any).isNotNull
            assertThat(result!!.isRelevant).isTrue
            assertThat(result.suggestedCoverLetter).isNull()
            assertThat(
                result.coverLetterGenerationStatus,
            ).isEqualTo(com.hhassistant.domain.entity.CoverLetterGenerationStatus.RETRY_QUEUED)

            coVerify(exactly = 1) { ollamaClient.chat(any()) }
            verify { repository.save(any()) }
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
