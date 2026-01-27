package com.hhassistant.repository

import com.hhassistant.domain.entity.VacancyAnalysis
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime

@DataJpaTest
@Testcontainers
class VacancyAnalysisRepositoryTest {
    
    @Autowired
    private lateinit var repository: VacancyAnalysisRepository
    
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
        val analysis = VacancyAnalysis(
            vacancyId = "vacancy123",
            isRelevant = true,
            relevanceScore = 0.85,
            reasoning = "Strong match with skills",
            matchedSkills = "Kotlin, Spring Boot",
            suggestedCoverLetter = null
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
        val relevantAnalysis = VacancyAnalysis(
            vacancyId = "v1",
            isRelevant = true,
            relevanceScore = 0.9,
            reasoning = "Perfect match",
            matchedSkills = null,
            suggestedCoverLetter = null
        )
        
        val irrelevantAnalysis = VacancyAnalysis(
            vacancyId = "v2",
            isRelevant = false,
            relevanceScore = 0.3,
            reasoning = "Not a match",
            matchedSkills = null,
            suggestedCoverLetter = null
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
        val highScore = VacancyAnalysis(
            vacancyId = "high",
            isRelevant = true,
            relevanceScore = 0.85,
            reasoning = "High score",
            matchedSkills = null,
            suggestedCoverLetter = null
        )
        
        val lowScore = VacancyAnalysis(
            vacancyId = "low",
            isRelevant = true,
            relevanceScore = 0.65,
            reasoning = "Low score",
            matchedSkills = null,
            suggestedCoverLetter = null
        )
        
        repository.saveAll(listOf(highScore, lowScore))
        
        val highRelevant = repository.findRelevantByMinScore(0.7)
        assertThat(highRelevant).hasSize(1)
        assertThat(highRelevant[0].vacancyId).isEqualTo("high")
    }
}

