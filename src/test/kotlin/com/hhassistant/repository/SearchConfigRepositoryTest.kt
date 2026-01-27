package com.hhassistant.repository

import com.hhassistant.domain.entity.SearchConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@DataJpaTest
@Testcontainers
class SearchConfigRepositoryTest {
    
    @Autowired
    private lateinit var repository: SearchConfigRepository
    
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
    fun `should save and retrieve search config`() {
        val config = SearchConfig(
            keywords = "Kotlin Developer",
            minSalary = 150000,
            maxSalary = 300000,
            area = "1",  // Moscow area code
            experience = "3-6 years",
            isActive = true
        )
        
        repository.save(config)
        
        val found = repository.findById(config.id!!)
        assertThat(found).isPresent
        assertThat(found.get().keywords).isEqualTo("Kotlin Developer")
        assertThat(found.get().minSalary).isEqualTo(150000)
        assertThat(found.get().isActive).isTrue
    }
    
    @Test
    fun `should find active configs only`() {
        val activeConfig = SearchConfig(
            keywords = "Kotlin",
            minSalary = null,
            maxSalary = null,
            area = null,
            experience = null,
            isActive = true
        )
        
        val inactiveConfig = SearchConfig(
            keywords = "Java",
            minSalary = null,
            maxSalary = null,
            area = null,
            experience = null,
            isActive = false
        )
        
        repository.saveAll(listOf(activeConfig, inactiveConfig))
        
        val active = repository.findByIsActiveTrue()
        assertThat(active).hasSize(1)
        assertThat(active[0].keywords).isEqualTo("Kotlin")
    }
}

