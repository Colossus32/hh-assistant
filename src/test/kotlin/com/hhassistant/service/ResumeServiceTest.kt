package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.integration.hh.HHResumeClient
import com.hhassistant.integration.hh.dto.ResumeDto
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.repository.ResumeRepository
import com.hhassistant.service.resume.PDFParserService
import com.hhassistant.service.resume.ResumeService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ResumeServiceTest {

    private lateinit var repository: ResumeRepository
    private lateinit var pdfParser: PDFParserService
    private lateinit var hhResumeClient: HHResumeClient
    private lateinit var objectMapper: ObjectMapper
    private lateinit var resumeStructureCache: com.github.benmanes.caffeine.cache.Cache<String, com.hhassistant.domain.model.ResumeStructure>
    private lateinit var service: ResumeService

    @BeforeEach
    fun setup() {
        repository = mockk()
        pdfParser = mockk()
        hhResumeClient = mockk()
        objectMapper = jacksonObjectMapper()
        // Создаем Caffeine Cache как в production коде
        resumeStructureCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .build<String, com.hhassistant.domain.model.ResumeStructure>()
        // Параметры в правильном порядке: repository, pdfParser, hhResumeClient, objectMapper, resumePath, resumeStructureCache
        // resumePath - это nullable, так как мы не используем реальный путь в тестах
        service = ResumeService(repository, pdfParser, hhResumeClient, objectMapper, null, resumeStructureCache)
    }

    @Test
    fun `should return existing active resume from database`() {
        runBlocking {
            val existingResume = Resume(
                fileName = "existing.pdf",
                rawText = "Existing resume text",
                structuredData = null,
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            )

            every { repository.findFirstByIsActiveTrue() } returns existingResume

            val result = service.loadResume()

            assertThat(result).isEqualTo(existingResume)
            coVerify(exactly = 0) { hhResumeClient.getMyResumes() }
        }
    }

    @Test
    fun `should load resume from HH API when no database resume exists`() {
        runBlocking {
            every { repository.findFirstByIsActiveTrue() } returns null

            val hhResumeDto = ResumeDto(
                id = "123",
                title = "Kotlin Developer",
                firstName = "Ivan",
                lastName = "Ivanov",
                skills = listOf(
                    com.hhassistant.integration.hh.dto.SkillDto("Kotlin"),
                    com.hhassistant.integration.hh.dto.SkillDto("Spring Boot"),
                ),
                experience = emptyList(),
                education = emptyList(),
                totalExperience = null,
                url = null,
            )

            coEvery { hhResumeClient.getMyResumes() } returns listOf(hhResumeDto)
            coEvery { hhResumeClient.getResumeDetails("123") } returns hhResumeDto

            every { pdfParser.extractStructuredData(any()) } returns com.hhassistant.domain.model.ResumeStructure(
                skills = emptyList(),
                experience = emptyList(),
                education = emptyList(),
                desiredPosition = null,
                desiredSalary = null,
                summary = null,
            )

            val savedResume = Resume(
                fileName = "hh_resume_123.txt",
                rawText = "Resume text",
                structuredData = "{}",
                source = ResumeSource.HH_API,
                isActive = true,
            )

            every { repository.save(any<Resume>()) } returns savedResume

            val result = service.loadResume()

            assertThat(result.source).isEqualTo(ResumeSource.HH_API)
            verify { repository.save(any<Resume>()) }
        }
    }

    @Test
    fun `should fallback to PDF when HH API fails`(@TempDir tempDir: Path) {
        runBlocking {
            every { repository.findFirstByIsActiveTrue() } returns null
            coEvery { hhResumeClient.getMyResumes() } throws RuntimeException("API Error")

            val pdfFile = File(tempDir.toFile(), "resume.pdf")
            // Create a minimal PDF content (in real test would use actual PDF)
            pdfFile.writeText("Test resume content")

            // Mock PDF parser
            every { pdfParser.extractText(any()) } returns "Test resume content"
            every { pdfParser.extractStructuredData(any()) } returns com.hhassistant.domain.model.ResumeStructure(
                skills = emptyList(),
                experience = emptyList(),
                education = emptyList(),
                desiredPosition = null,
                desiredSalary = null,
                summary = null,
            )

            val savedResume = Resume(
                fileName = "resume.pdf",
                rawText = "Test resume content",
                structuredData = "{}",
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            )

            every { repository.save(any<Resume>()) } returns savedResume

            // Create service with temp directory path
            val serviceWithTempPath = ResumeService(
                repository,
                pdfParser,
                hhResumeClient,
                objectMapper,
                pdfFile.absolutePath,
                resumeStructureCache,
            )

            val result = serviceWithTempPath.loadResume()

            assertThat(result.source).isEqualTo(ResumeSource.MANUAL_UPLOAD)
            verify { repository.save(any<Resume>()) }
        }
    }

    @Test
    fun `should parse resume structure from JSON`() {
        val resume = Resume(
            fileName = "test.pdf",
            rawText = "Test",
            structuredData = """{"skills":["Kotlin","Spring"],"desired_salary":200000}""",
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )

        val structure = service.getResumeStructure(resume)

        assertThat(structure).isNotNull
        assertThat(structure?.skills).isNotEmpty
        assertThat(structure?.skills).contains("Kotlin", "Spring")
        assertThat(structure?.desiredSalary).isEqualTo(200000)
    }

    @Test
    fun `should return null for resume without structured data`() {
        val resume = Resume(
            fileName = "test.pdf",
            rawText = "Test",
            structuredData = null,
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )

        val structure = service.getResumeStructure(resume)

        assertThat(structure).isNull()
    }
}
