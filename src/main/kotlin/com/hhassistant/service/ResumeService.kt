package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.hh.HHResumeClient
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.repository.ResumeRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class ResumeService(
    private val repository: ResumeRepository,
    private val pdfParser: PDFParserService,
    private val hhResumeClient: HHResumeClient,
    private val objectMapper: ObjectMapper,
    @Value("\${app.resume.path:./resumes/resume.pdf}") private val resumePath: String,
) {
    private val log = KotlinLogging.logger {}

    suspend fun loadResume(): Resume {
        // 1. Проверяем, есть ли активное резюме в БД
        val existingResume = repository.findFirstByIsActiveTrue()
        if (existingResume != null) {
            log.debug("Using existing resume from database: ${existingResume.fileName}")
            return existingResume
        }

        // 2. Пытаемся загрузить из HH.ru API
        try {
            val hhResume = loadFromHHAPI()
            if (hhResume != null) {
                log.info("Resume loaded from HH.ru API")
                return hhResume
            }
        } catch (e: Exception) {
            log.warn("Failed to load resume from HH.ru API: ${e.message}", e)
        }

        // 3. Fallback: загружаем из локального PDF
        return loadFromPDF()
    }

    private suspend fun loadFromHHAPI(): Resume? {
        return try {
            val resumes = hhResumeClient.getMyResumes()
            if (resumes.isEmpty()) {
                log.info("No resumes found in HH.ru API")
                return null
            }

            val hhResume = resumes.first()
            val resumeDetails = hhResumeClient.getResumeDetails(hhResume.id)

            // Конвертируем HH.ru ResumeDto в наш Resume entity
            val resumeText = buildResumeText(resumeDetails)
            val structuredData = pdfParser.extractStructuredData(resumeText)

            repository.save(
                Resume(
                    fileName = "hh_resume_${resumeDetails.id}.txt",
                    rawText = resumeText,
                    structuredData = objectMapper.writeValueAsString(structuredData),
                    source = ResumeSource.HH_API,
                    isActive = true,
                ),
            )
        } catch (e: Exception) {
            log.error("Error loading resume from HH.ru API", e)
            null
        }
    }

    private fun buildResumeText(resumeDto: com.hhassistant.client.hh.dto.ResumeDto): String {
        val sb = StringBuilder()

        sb.appendLine("${resumeDto.firstName ?: ""} ${resumeDto.lastName ?: ""}".trim())
        sb.appendLine(resumeDto.title)
        sb.appendLine()

        if (resumeDto.skills?.isNotEmpty() == true) {
            sb.appendLine("Навыки:")
            resumeDto.skills.forEach { skill ->
                sb.appendLine("- ${skill.name}")
            }
            sb.appendLine()
        }

        if (resumeDto.experience?.isNotEmpty() == true) {
            sb.appendLine("Опыт работы:")
            resumeDto.experience.forEach { exp ->
                sb.appendLine("${exp.position ?: ""} в ${exp.company ?: ""}")
                exp.description?.let { sb.appendLine(it) }
            }
            sb.appendLine()
        }

        if (resumeDto.education?.isNotEmpty() == true) {
            sb.appendLine("Образование:")
            resumeDto.education.forEach { edu ->
                sb.appendLine("${edu.name ?: ""} ${edu.year ?: ""}")
            }
        }

        return sb.toString()
    }

    private fun loadFromPDF(): Resume {
        val pdfFile = File(resumePath)
        require(pdfFile.exists()) {
            "Resume PDF file not found at: ${pdfFile.absolutePath}. " +
                "Please place your resume.pdf in the resumes/ directory."
        }

        log.info("Loading resume from PDF: ${pdfFile.absolutePath}")

        val rawText = pdfParser.extractText(pdfFile)
        val structuredData = pdfParser.extractStructuredData(rawText)

        return repository.save(
            Resume(
                fileName = pdfFile.name,
                rawText = rawText,
                structuredData = objectMapper.writeValueAsString(structuredData),
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            ),
        )
    }

    fun getResumeStructure(resume: Resume): com.hhassistant.domain.model.ResumeStructure? {
        return resume.structuredData?.let {
            try {
                objectMapper.readValue(it, com.hhassistant.domain.model.ResumeStructure::class.java)
            } catch (e: Exception) {
                log.warn("Failed to parse structured data for resume ${resume.id}", e)
                null
            }
        }
    }
}
