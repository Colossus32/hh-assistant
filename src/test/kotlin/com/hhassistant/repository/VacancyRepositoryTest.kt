package com.hhassistant.repository

import com.hhassistant.domain.entity.Vacancy
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
class VacancyRepositoryTest {

    @Autowired
    private lateinit var repository: VacancyRepository

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
    fun `should save and retrieve vacancy`() {
        val vacancy = Vacancy(
            id = "12345",
            name = "Kotlin Developer",
            employer = "Tech Corp",
            salary = "150000-200000",
            area = "Moscow",
            url = "https://hh.ru/vacancy/12345",
            description = "Looking for Kotlin developer",
            experience = "3-6 years",
            publishedAt = LocalDateTime.now(),
            status = VacancyStatus.NEW,
        )

        repository.save(vacancy)

        val found = repository.findById("12345")
        assertThat(found).isPresent
        assertThat(found.get().name).isEqualTo("Kotlin Developer")
        assertThat(found.get().employer).isEqualTo("Tech Corp")
        assertThat(found.get().status).isEqualTo(VacancyStatus.NEW)
    }

    @Test
    fun `should find vacancies by status`() {
        val vacancy1 = Vacancy(
            id = "1",
            name = "Vacancy 1",
            employer = "Company 1",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/1",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.NEW,
        )

        val vacancy2 = Vacancy(
            id = "2",
            name = "Vacancy 2",
            employer = "Company 2",
            salary = null,
            area = "SPB",
            url = "https://hh.ru/vacancy/2",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.ANALYZED,
        )

        repository.saveAll(listOf(vacancy1, vacancy2))

        val newVacancies = repository.findByStatus(VacancyStatus.NEW)
        assertThat(newVacancies).hasSize(1)
        assertThat(newVacancies[0].id).isEqualTo("1")

        val analyzedVacancies = repository.findByStatus(VacancyStatus.ANALYZED)
        assertThat(analyzedVacancies).hasSize(1)
        assertThat(analyzedVacancies[0].id).isEqualTo("2")
    }

    @Test
    fun `should find all vacancy ids`() {
        val vacancy1 = Vacancy(
            id = "id1",
            name = "Vacancy 1",
            employer = "Company 1",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/id1",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.NEW,
        )

        val vacancy2 = Vacancy(
            id = "id2",
            name = "Vacancy 2",
            employer = "Company 2",
            salary = null,
            area = "SPB",
            url = "https://hh.ru/vacancy/id2",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.NEW,
        )

        repository.saveAll(listOf(vacancy1, vacancy2))

        val ids = repository.findAllIds()
        assertThat(ids).containsExactlyInAnyOrder("id1", "id2")
    }

    @Test
    fun `should check if vacancy exists`() {
        val vacancy = Vacancy(
            id = "exists",
            name = "Test",
            employer = "Test Corp",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/exists",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.NEW,
        )

        repository.save(vacancy)

        assertThat(repository.existsById("exists")).isTrue
        assertThat(repository.existsById("not-exists")).isFalse
    }
}
