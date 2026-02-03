package com.hhassistant.service.resume

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.domain.model.Education
import com.hhassistant.domain.model.Experience
import com.hhassistant.domain.model.ResumeStructure
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Service
import java.io.File
import java.util.regex.Pattern

@Service
class PDFParserService(
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    fun extractText(pdfFile: File): String {
        require(pdfFile.exists()) { "PDF file does not exist: ${pdfFile.absolutePath}" }
        require(pdfFile.extension.lowercase() == "pdf") { "File is not a PDF: ${pdfFile.name}" }

        return Loader.loadPDF(pdfFile).use { document ->
            val stripper = PDFTextStripper()
            stripper.setStartPage(1)
            stripper.setEndPage(document.numberOfPages)
            val text = stripper.getText(document)
            text.trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
        }
    }

    /**
     * Извлекает текст из PDF из байтов
     */
    fun extractTextFromBytes(pdfBytes: ByteArray): String {
        return Loader.loadPDF(pdfBytes).use { document ->
            val stripper = PDFTextStripper()
            stripper.setStartPage(1)
            stripper.setEndPage(document.numberOfPages)
            val text = stripper.getText(document)
            text.trim()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
        }
    }

    fun extractStructuredData(text: String): ResumeStructure {
        val normalizedText = text.lowercase()

        val skills = extractSkills(normalizedText, text)
        val experience = extractExperience(text)
        val education = extractEducation(text)
        val desiredPosition = extractDesiredPosition(text)
        val desiredSalary = extractDesiredSalary(text)
        val summary = extractSummary(text)

        return ResumeStructure(
            skills = skills,
            experience = experience,
            education = education,
            desiredPosition = desiredPosition,
            desiredSalary = desiredSalary,
            summary = summary,
        )
    }

    private fun extractSkills(normalizedText: String, originalText: String): List<String> {
        val skillsSectionPatterns = listOf(
            Pattern.compile("навыки?[\\s:]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("skills?[\\s:]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("технологии[\\s:]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
        )

        for (pattern in skillsSectionPatterns) {
            val matcher = pattern.matcher(originalText)
            if (matcher.find()) {
                val skillsText = matcher.group(1)
                return parseSkillsList(skillsText)
            }
        }

        // Fallback: ищем упоминания популярных технологий
        val commonTechs = listOf(
            "kotlin", "java", "spring", "postgresql", "docker", "kubernetes",
            "react", "vue", "angular", "typescript", "javascript", "python",
            "git", "gradle", "maven", "redis", "elasticsearch",
        )

        return commonTechs.filter { normalizedText.contains(it) }
    }

    private fun parseSkillsList(skillsText: String): List<String> {
        return skillsText
            .split(Regex("[,;]|\\s+и\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }
            .distinct()
    }

    private fun extractExperience(text: String): List<Experience> {
        val experiencePattern = Pattern.compile(
            "(?i)(?:опыт работы|experience|работал|работала)[\\s\\S]*?((?:\\d{4}|\\d{1,2}\\.\\d{4})[\\s\\S]*?)(?=(?:образование|education|навыки|skills|$))",
        )

        val matcher = experiencePattern.matcher(text)
        if (!matcher.find()) {
            return emptyList()
        }

        val experienceText = matcher.group(1)
        val positions = mutableListOf<Experience>()

        // Простой парсинг опыта работы
        val positionPattern = Pattern.compile(
            "([^\\n]+?)\\s+(?:в|at|@)\\s+([^\\n]+?)\\s+(?:\\d{4}|\\d{1,2}\\.\\d{4})[\\s\\-]+(?:\\d{4}|\\d{1,2}\\.\\d{4}|настоящее время|present)",
            Pattern.CASE_INSENSITIVE,
        )

        val posMatcher = positionPattern.matcher(experienceText)
        while (posMatcher.find()) {
            positions.add(
                Experience(
                    position = posMatcher.group(1).trim(),
                    company = posMatcher.group(2).trim(),
                    duration = posMatcher.group(0).substringAfterLast(" "),
                ),
            )
        }

        return positions
    }

    private fun extractEducation(text: String): List<Education> {
        val educationPattern = Pattern.compile(
            "(?i)(?:образование|education)[\\s\\S]*?((?:\\d{4}|\\d{1,2}\\.\\d{4})[\\s\\S]*?)(?=(?:опыт|experience|навыки|skills|$))",
        )

        val matcher = educationPattern.matcher(text)
        if (!matcher.find()) {
            return emptyList()
        }

        val educationText = matcher.group(1)
        val institutions = mutableListOf<Education>()

        // Простой парсинг образования
        val institutionPattern = Pattern.compile(
            "([^\\n]+?)\\s+(?:\\d{4}|\\d{1,2}\\.\\d{4})",
            Pattern.CASE_INSENSITIVE,
        )

        val instMatcher = institutionPattern.matcher(educationText)
        while (instMatcher.find()) {
            val line = instMatcher.group(0)
            val yearMatch = Pattern.compile("(\\d{4})").matcher(line)
            val year = if (yearMatch.find()) yearMatch.group(1).toIntOrNull() else null

            institutions.add(
                Education(
                    institution = line.substringBeforeLast(" ").trim(),
                    year = year,
                ),
            )
        }

        return institutions
    }

    private fun extractDesiredPosition(text: String): String? {
        val patterns = listOf(
            Pattern.compile(
                "(?:желаемая должность|desired position|ищу работу|looking for)[\\s:]+([^\\n]+)",
                Pattern.CASE_INSENSITIVE,
            ),
            Pattern.compile("(?:позиция|position)[\\s:]+([^\\n]+)", Pattern.CASE_INSENSITIVE),
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val result = matcher.group(1).trim()
                if (result.isNotBlank()) {
                    return result
                }
            }
        }

        return null
    }

    private fun extractDesiredSalary(text: String): Int? {
        val patterns = listOf(
            Pattern.compile(
                "(?:желаемая зарплата|desired salary|зарплата|salary)[\\s:]+(?:от|from)?\\s*(\\d+)\\s*(?:руб|rub|rur)",
                Pattern.CASE_INSENSITIVE,
            ),
            Pattern.compile("(?:от|from)\\s*(\\d+)\\s*(?:руб|rub|rur)", Pattern.CASE_INSENSITIVE),
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1).toIntOrNull()
            }
        }

        return null
    }

    private fun extractSummary(text: String): String? {
        val summaryPattern = Pattern.compile(
            "(?i)(?:о себе|about|summary|резюме|кратко)[\\s:]+([^\\n]+(?:\\n[^\\n]+){0,3})",
        )

        val matcher = summaryPattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1).trim()
        }

        // Fallback: первые 2-3 строки после имени
        val lines = text.lines()
        if (lines.size > 3) {
            return lines.subList(2, minOf(5, lines.size)).joinToString("\n").trim()
        }

        return null
    }
}
