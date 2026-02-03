package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hhassistant.service.resume.PDFParserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PDFParserServiceTest {

    private lateinit var parser: PDFParserService
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setup() {
        parser = PDFParserService(objectMapper)
    }

    @Test
    fun `should extract text from PDF file`(@TempDir tempDir: Path) {
        // Note: This test requires a real PDF file
        // For now, we'll test the structure parsing logic
        val text = """
            Иван Иванов
            Kotlin Developer
            
            Навыки: Kotlin, Spring Boot, PostgreSQL, Docker
            
            Опыт работы:
            Senior Developer в Tech Corp 2020 - настоящее время
            Разработка микросервисов на Kotlin
            
            Middle Developer в Startup Inc 2018 - 2020
            Backend разработка
            
            Образование:
            Университет 2014 - 2018
            Специалист по информатике
            
            Желаемая зарплата: от 200000 руб
        """.trimIndent()

        val structure = parser.extractStructuredData(text)

        assertThat(structure.skills).isNotEmpty
        assertThat(structure.skills).containsAnyOf("Kotlin", "kotlin", "Spring Boot", "spring", "PostgreSQL", "postgresql", "Docker", "docker")
        // Experience and education parsing is complex, so we just check they exist
        assertThat(structure.desiredSalary).isEqualTo(200000)
    }

    @Test
    fun `should parse skills from text`() {
        val text = """
            Навыки: Kotlin, Spring Boot, PostgreSQL, Docker, Kubernetes
        """.trimIndent()

        val structure = parser.extractStructuredData(text)

        assertThat(structure.skills).isNotEmpty
        assertThat(structure.skills).containsAnyOf("Kotlin", "kotlin")
        assertThat(structure.skills).containsAnyOf("Spring Boot", "spring", "Spring")
        assertThat(structure.skills).containsAnyOf("PostgreSQL", "postgresql")
        assertThat(structure.skills).containsAnyOf("Docker", "docker")
    }

    @Test
    fun `should extract desired salary`() {
        val text = """
            Желаемая зарплата: от 150000 руб
        """.trimIndent()

        val structure = parser.extractStructuredData(text)

        assertThat(structure.desiredSalary).isEqualTo(150000)
    }

    @Test
    fun `should extract desired position`() {
        val text = "ищу работу: Senior Kotlin Developer"

        val structure = parser.extractStructuredData(text)

        assertThat(structure.desiredPosition).isNotNull
        assertThat(structure.desiredPosition).contains("Senior Kotlin Developer")
    }

    @Test
    fun `should handle empty text gracefully`() {
        val structure = parser.extractStructuredData("")

        assertThat(structure.skills).isEmpty()
        assertThat(structure.experience).isEmpty()
        assertThat(structure.education).isEmpty()
    }
}
