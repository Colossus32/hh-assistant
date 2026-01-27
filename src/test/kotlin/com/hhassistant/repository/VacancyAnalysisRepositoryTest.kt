package com.hhassistant.repository

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyAnalysis
import com.hhassistant.domain.entity.VacancyStatus
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
import java.time.LocalDateTime

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class VacancyAnalysisRepositoryTest {

    @Autowired
    private lateinit var repository: VacancyAnalysisRepository

    @Autowired
    private lateinit var vacancyRepository: com.hhassistant.repository.VacancyRepository

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
    fun `should save and find analysis by vacancy id`() {
        // Create vacancy first (required by foreign key)
        val vacancy = Vacancy(
            id = "vacancy123",
            name = "Test Vacancy",
            employer = "Test Corp",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/vacancy123",
            description = null,
            experience = null,
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
        vacancyRepository.save(vacancy)

        val analysis = VacancyAnalysis(
            vacancyId = "vacancy123",
            isRelevant = true,
            relevanceScore = 0.85,
            reasoning = "Strong match with skills",
            matchedSkills = "Kotlin, Spring Boot",
            suggestedCoverLetter = null,
        )

        repository.save(analysis)

        val found = repository.findByVacancyId("vacancy123")
        assertThat(found).isNotNull
        assertThat(found?.isRelevant).isTrue
        assertThat(found?.relevanceScore).isEqualTo(0.85)
        assertThat(found?.reasoning).contains("Strong match")
    }

    @Test
    fun `should find relevant analyses`() {
        // Create vacancies first
        val vacancy1 = Vacancy(
            id = "v1",
            name = "Vacancy 1",
            employer = "Company 1",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/v1",
            description = null,
            experience = null,
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
        val vacancy2 = Vacancy(
            id = "v2",
            name = "Vacancy 2",
            employer = "Company 2",
            salary = null,
            area = "SPB",
            url = "https://hh.ru/vacancy/v2",
            description = null,
            experience = null,
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
        vacancyRepository.saveAll(listOf(vacancy1, vacancy2))

        val relevantAnalysis = VacancyAnalysis(
            vacancyId = "v1",
            isRelevant = true,
            relevanceScore = 0.9,
            reasoning = "Perfect match",
            matchedSkills = null,
            suggestedCoverLetter = null,
        )

        val irrelevantAnalysis = VacancyAnalysis(
            vacancyId = "v2",
            isRelevant = false,
            relevanceScore = 0.3,
            reasoning = "Not a match",
            matchedSkills = null,
            suggestedCoverLetter = null,
        )

        repository.saveAll(listOf(relevantAnalysis, irrelevantAnalysis))

        val relevant = repository.findByIsRelevant(true)
        assertThat(relevant).hasSize(1)
        assertThat(relevant[0].vacancyId).isEqualTo("v1")

        val irrelevant = repository.findByIsRelevant(false)
        assertThat(irrelevant).hasSize(1)
        assertThat(irrelevant[0].vacancyId).isEqualTo("v2")
    }

    @Test
    fun `should find relevant analyses by minimum score`() {
        // Create vacancies first
        val vacancyHigh = Vacancy(
            id = "high",
            name = "High Score Vacancy",
            employer = "Company",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/high",
            description = null,
            experience = null,
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
        val vacancyLow = Vacancy(
            id = "low",
            name = "Low Score Vacancy",
            employer = "Company",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/low",
            description = null,
            experience = null,
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )
        vacancyRepository.saveAll(listOf(vacancyHigh, vacancyLow))

        val highScore = VacancyAnalysis(
            vacancyId = "high",
            isRelevant = true,
            relevanceScore = 0.85,
            reasoning = "High score",
            matchedSkills = null,
            suggestedCoverLetter = null,
        )

        val lowScore = VacancyAnalysis(
            vacancyId = "low",
            isRelevant = true,
            relevanceScore = 0.65,
            reasoning = "Low score",
            matchedSkills = null,
            suggestedCoverLetter = null,
        )

        repository.saveAll(listOf(highScore, lowScore))

        val highRelevant = repository.findRelevantByMinScore(0.7)
        assertThat(highRelevant).hasSize(1)
        assertThat(highRelevant[0].vacancyId).isEqualTo("high")
    }
}
