package com.hhassistant.repository

import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ResumeRepositoryTest {

    @Autowired
    private lateinit var repository: ResumeRepository

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Test
    fun `should save and retrieve resume`() {
        val resume = Resume(
            fileName = "resume.pdf",
            rawText = "John Doe\nKotlin Developer\n5 years experience",
            structuredData = """{"skills": ["Kotlin", "Spring Boot"]}""",
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )

        repository.save(resume)

        val found = repository.findById(resume.id!!)
        assertThat(found).isPresent
        assertThat(found.get().fileName).isEqualTo("resume.pdf")
        assertThat(found.get().source).isEqualTo(ResumeSource.MANUAL_UPLOAD)
        assertThat(found.get().isActive).isTrue
    }

    @Test
    fun `should find active resumes only`() {
        val activeResume = Resume(
            fileName = "active.pdf",
            rawText = "Active resume",
            structuredData = null,
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )

        val inactiveResume = Resume(
            fileName = "inactive.pdf",
            rawText = "Inactive resume",
            structuredData = null,
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = false,
        )

        repository.saveAll(listOf(activeResume, inactiveResume))

        val active = repository.findByIsActiveTrue()
        assertThat(active).hasSize(1)
        assertThat(active[0].fileName).isEqualTo("active.pdf")
    }

    @Test
    fun `should find first active resume`() {
        val resume1 = Resume(
            fileName = "first.pdf",
            rawText = "First resume",
            structuredData = null,
            source = ResumeSource.HH_API,
            isActive = true,
        )

        val resume2 = Resume(
            fileName = "second.pdf",
            rawText = "Second resume",
            structuredData = null,
            source = ResumeSource.MANUAL_UPLOAD,
            isActive = true,
        )

        repository.saveAll(listOf(resume1, resume2))

        val first = repository.findFirstByIsActiveTrue()
        assertThat(first).isNotNull
        assertThat(first?.isActive).isTrue
    }
}
