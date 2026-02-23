package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.domain.model.ResumeStructure
import com.hhassistant.exception.OllamaException
import com.hhassistant.vacancy.repository.VacancyAnalysisRepository
import com.hhassistant.service.util.AnalysisTimeService
import com.hhassistant.vacancy.service.VacancyAnalysisService
import com.hhassistant.vacancy.service.VacancyProcessingControlService
import com.hhassistant.vacancy.port.ContentValidatorPort
import com.hhassistant.vacancy.port.ResumeProvider
import com.hhassistant.vacancy.port.SkillSaverPort
import com.hhassistant.vacancy.port.VacancyLlmAnalyzer
import com.hhassistant.vacancy.port.VacancyStatusUpdater
import com.hhassistant.vacancy.port.VacancyUrlChecker
import io.github.resilience4j.circuitbreaker.CircuitBreaker
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

    private lateinit var resumeProvider: ResumeProvider
    private lateinit var repository: VacancyAnalysisRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var contentValidator: ContentValidatorPort
    private lateinit var statusUpdater: VacancyStatusUpdater
    private lateinit var metricsService: com.hhassistant.monitoring.metrics.MetricsService
    private lateinit var analysisTimeService: AnalysisTimeService
    private lateinit var skillSaver: SkillSaverPort
    private lateinit var circuitBreakerStateService: com.hhassistant.monitoring.service.CircuitBreakerStateService
    private lateinit var processedVacancyCacheService: com.hhassistant.vacancy.service.ProcessedVacancyCacheService
    private lateinit var vacancyProcessingControlService: VacancyProcessingControlService
    private lateinit var vacancyUrlChecker: VacancyUrlChecker
    private lateinit var llmAnalyzer: VacancyLlmAnalyzer
    private lateinit var ollamaCircuitBreaker: CircuitBreaker
    private lateinit var service: VacancyAnalysisService

    @BeforeEach
    fun setup() {
        resumeProvider = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper()
        contentValidator = mockk(relaxed = true)
        statusUpdater = mockk(relaxed = true)
        metricsService = mockk(relaxed = true)
        analysisTimeService = mockk(relaxed = true)
        skillSaver = mockk(relaxed = true)
        circuitBreakerStateService = mockk(relaxed = true)
        processedVacancyCacheService = mockk(relaxed = true)
        vacancyProcessingControlService = mockk(relaxed = true) {
            every { isProcessingPaused() } returns false
        }
        vacancyUrlChecker = mockk(relaxed = true)
        llmAnalyzer = mockk(relaxed = true)
        ollamaCircuitBreaker = CircuitBreaker.ofDefaults("ollamaTest")
        service = VacancyAnalysisService(
            resumeProvider = resumeProvider,
            repository = repository,
            objectMapper = objectMapper,
            contentValidator = contentValidator,
            statusUpdater = statusUpdater,
            metricsService = metricsService,
            analysisTimeService = analysisTimeService,
            skillSaver = skillSaver,
            circuitBreakerStateService = circuitBreakerStateService,
            processedVacancyCacheService = processedVacancyCacheService,
            vacancyProcessingControlService = vacancyProcessingControlService,
            vacancyUrlChecker = vacancyUrlChecker,
            llmAnalyzer = llmAnalyzer,
            ollamaCircuitBreaker = ollamaCircuitBreaker,
        )
    }

    @Test
    fun `should analyze vacancy and mark as relevant`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
            coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
            coEvery { resumeProvider.loadResume() } returns resume
            every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
            coEvery {
                llmAnalyzer.analyze(vacancy, resume, resumeStructure)
            } returns VacancyLlmAnalyzer.AnalysisResult(
                skills = listOf("Kotlin", "Spring Boot", "PostgreSQL"),
                relevanceScore = 0.85,
                isRelevant = true,
                reasoning = "Вакансия хорошо подходит",
            )

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

            coVerify { llmAnalyzer.analyze(any(), any(), any()) }
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
            coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
            coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
            coEvery { resumeProvider.loadResume() } returns resume
            every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
            coEvery {
                llmAnalyzer.analyze(vacancy, resume, resumeStructure)
            } returns VacancyLlmAnalyzer.AnalysisResult(
                skills = emptyList(),
                relevanceScore = 0.25,
                isRelevant = false,
                reasoning = "Вакансия не подходит",
            )

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

            coVerify(exactly = 1) { llmAnalyzer.analyze(any(), any(), any()) }
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

        every { processedVacancyCacheService.isProcessed(vacancy.id) } returns true
        every { repository.findByVacancyId(vacancy.id) } returns existingAnalysis

        runBlocking {
            val result: com.hhassistant.domain.entity.VacancyAnalysis? = service.analyzeVacancy(vacancy)

            assertThat(result).isEqualTo(existingAnalysis)
            coVerify(exactly = 0) { llmAnalyzer.analyze(any(), any(), any()) }
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    @Test
    fun `should throw OllamaException when LLM connection fails`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
        coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
        coEvery { resumeProvider.loadResume() } returns resume
        every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
        coEvery { llmAnalyzer.analyze(any(), any(), any()) } throws OllamaException.ConnectionException("Failed to connect to Ollama service")

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(OllamaException.ConnectionException::class.java)
            .hasMessageContaining("Failed to connect to Ollama service")

        coVerify { llmAnalyzer.analyze(any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should throw OllamaException when LLM returns invalid JSON`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
        coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
        coEvery { resumeProvider.loadResume() } returns resume
        every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
        coEvery { llmAnalyzer.analyze(any(), any(), any()) } throws OllamaException.ParsingException("Invalid JSON")

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(OllamaException.ParsingException::class.java)

        coVerify { llmAnalyzer.analyze(any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should throw when relevance score is out of range`() {
        val vacancy = createTestVacancy()
        val resume = createTestResume()
        val resumeStructure = createTestResumeStructure()

        every { repository.findByVacancyId(vacancy.id) } returns null
        coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
        coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
        coEvery { resumeProvider.loadResume() } returns resume
        every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
        coEvery { llmAnalyzer.analyze(any(), any(), any()) } throws IllegalArgumentException("Relevance score must be between 0.0 and 1.0")

        assertThatThrownBy {
            runBlocking {
                service.analyzeVacancy(vacancy)
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Relevance score must be between 0.0 and 1.0")

        coVerify { llmAnalyzer.analyze(any(), any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `should handle cover letter generation failure gracefully`() {
        runBlocking {
            val vacancy = createTestVacancy()
            val resume = createTestResume()
            val resumeStructure = createTestResumeStructure()

            every { repository.findByVacancyId(vacancy.id) } returns null
            coEvery { vacancyUrlChecker.checkVacancyUrl(vacancy.id) } returns true
            coEvery { contentValidator.validate(vacancy) } returns ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
            coEvery { resumeProvider.loadResume() } returns resume
            every { resumeProvider.getResumeStructure(resume) } returns resumeStructure
            coEvery {
                llmAnalyzer.analyze(vacancy, resume, resumeStructure)
            } returns VacancyLlmAnalyzer.AnalysisResult(
                skills = listOf("Kotlin", "Spring Boot"),
                relevanceScore = 0.85,
                isRelevant = true,
                reasoning = "Вакансия хорошо подходит",
            )

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
            assertThat(result.coverLetterGenerationStatus).isEqualTo(com.hhassistant.domain.entity.CoverLetterGenerationStatus.RETRY_QUEUED)

            coVerify(exactly = 1) { llmAnalyzer.analyze(any(), any(), any()) }
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
