package com.hhassistant.service

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.config.FormattingConfig
import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.util.SearchConfigFactory
import com.hhassistant.service.util.TokenRefreshService
import com.hhassistant.service.vacancy.VacancyService
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
import java.util.Optional

class VacancyServiceTest {

    private lateinit var hhVacancyClient: HHVacancyClient
    private lateinit var vacancyRepository: VacancyRepository
    private lateinit var searchConfigRepository: SearchConfigRepository
    private lateinit var formattingConfig: FormattingConfig
    private lateinit var notificationService: NotificationService
    private lateinit var tokenRefreshService: TokenRefreshService
    private lateinit var searchConfigFactory: SearchConfigFactory
    private lateinit var searchConfig: com.hhassistant.config.VacancyServiceConfig
    private lateinit var vacancyIdsCache: com.github.benmanes.caffeine.cache.Cache<String, Set<String>>
    private lateinit var service: VacancyService

    @BeforeEach
    fun setup() {
        hhVacancyClient = mockk()
        vacancyRepository = mockk()
        searchConfigRepository = mockk()
        formattingConfig = FormattingConfig(
            defaultCurrency = "RUR",
            areaNotSpecified = "Не указан",
        )
        notificationService = mockk(relaxed = true)
        tokenRefreshService = mockk(relaxed = true)
        searchConfigFactory = mockk(relaxed = true)
        searchConfig = mockk(relaxed = true)
        vacancyIdsCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(100)
            .build<String, Set<String>>()
        service = VacancyService(
            hhVacancyClient = hhVacancyClient,
            vacancyRepository = vacancyRepository,
            searchConfigRepository = searchConfigRepository,
            formattingConfig = formattingConfig,
            notificationService = notificationService,
            tokenRefreshService = tokenRefreshService,
            searchConfigFactory = searchConfigFactory,
            searchConfig = searchConfig,
            maxVacanciesPerCycle = 50,
            vacancyIdsCache = vacancyIdsCache,
        )
    }

    @Test
    fun `should fetch and save new vacancies`() {
        runBlocking {
            val config = createTestSearchConfig()
            val vacancyDto = createTestVacancyDto()
            val existingIds = listOf<String>()

            every { searchConfigRepository.findByIsActiveTrue() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns listOf(vacancyDto)
            every { vacancyRepository.findAllIds() } returns existingIds
            every { vacancyRepository.saveAll(any<List<Vacancy>>()) } returns listOf(createTestVacancy())

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).hasSize(1)
            coVerify { hhVacancyClient.searchVacancies(config) }
            verify { vacancyRepository.saveAll(any<List<Vacancy>>()) }
        }
    }

    @Test
    fun `should skip existing vacancies`() {
        runBlocking {
            val config = createTestSearchConfig()
            val vacancyDto = createTestVacancyDto()
            val existingIds = listOf("12345") // Вакансия уже существует

            every { searchConfigRepository.findByIsActiveTrue() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns listOf(vacancyDto)
            every { vacancyRepository.findAllIds() } returns existingIds

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
            verify(exactly = 0) { vacancyRepository.saveAll(any<List<Vacancy>>()) }
        }
    }

    @Test
    fun `should return empty list when no active configs`() {
        runBlocking {
            every { searchConfigRepository.findByIsActiveTrue() } returns emptyList()

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
            coVerify(exactly = 0) { hhVacancyClient.searchVacancies(any()) }
        }
    }

    @Test
    fun `should handle rate limit exception gracefully`() {
        runBlocking {
            val config = createTestSearchConfig()

            every { searchConfigRepository.findByIsActiveTrue() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } throws HHAPIException.RateLimitException("Rate limit")

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
            // Должен прервать обработку при rate limit
        }
    }

    @Test
    fun `should continue with other configs when one fails`() {
        runBlocking {
            val config1 = createTestSearchConfig(id = 1L, keywords = "Kotlin")
            val config2 = createTestSearchConfig(id = 2L, keywords = "Java")
            val vacancyDto = createTestVacancyDto()
            val existingIds = listOf<String>()

            every { searchConfigRepository.findByIsActiveTrue() } returns listOf(config1, config2)
            coEvery { hhVacancyClient.searchVacancies(config1) } throws HHAPIException.ConnectionException("Connection error")
            coEvery { hhVacancyClient.searchVacancies(config2) } returns listOf(vacancyDto)
            every { vacancyRepository.findAllIds() } returns existingIds
            every { vacancyRepository.saveAll(any<List<Vacancy>>()) } returns listOf(createTestVacancy())

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).hasSize(1)
            coVerify { hhVacancyClient.searchVacancies(config2) }
        }
    }

    @Test
    fun `should respect max vacancies limit`() {
        runBlocking {
            val config = createTestSearchConfig()
            val vacancyDtos = (1..60).map { createTestVacancyDto(id = "$it") }
            val existingIds = listOf<String>()

            every { searchConfigRepository.findByIsActiveTrue() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns vacancyDtos
            every { vacancyRepository.findAllIds() } returns existingIds
            every { vacancyRepository.saveAll(any<List<Vacancy>>()) } returns vacancyDtos.take(50).map { createTestVacancy(id = it.id) }

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).hasSize(50)
        }
    }

    @Test
    fun `should get new vacancies for analysis`() {
        val newVacancies = listOf(
            createTestVacancy(status = VacancyStatus.NEW),
            createTestVacancy(id = "67890", status = VacancyStatus.NEW),
        )

        every { vacancyRepository.findByStatus(VacancyStatus.NEW) } returns newVacancies

        val result = service.getNewVacanciesForAnalysis()

        assertThat(result).hasSize(2)
        assertThat(result).allSatisfy { vacancy -> assertThat(vacancy.status).isEqualTo(VacancyStatus.NEW) }
    }

    @Test
    fun `should update vacancy status`() {
        val vacancy = createTestVacancy(status = VacancyStatus.NEW)
        val updatedVacancy = vacancy.copy(status = VacancyStatus.ANALYZED)

        every { vacancyRepository.findById(updatedVacancy.id) } returns Optional.of(vacancy)
        every { vacancyRepository.save(any<Vacancy>()) } returns updatedVacancy

        service.updateVacancyStatus(updatedVacancy)

        verify { vacancyRepository.save(any<Vacancy>()) }
    }

    @Test
    fun `should throw exception when update fails`() {
        val vacancy = createTestVacancy()
        val updatedVacancy = vacancy.copy(status = VacancyStatus.ANALYZED)

        every { vacancyRepository.findById(updatedVacancy.id) } returns Optional.of(vacancy)
        every { vacancyRepository.save(any<Vacancy>()) } throws RuntimeException("Database error")

        assertThatThrownBy {
            service.updateVacancyStatus(updatedVacancy)
        }.isInstanceOf(VacancyProcessingException::class.java)
            .hasMessageContaining("Failed to update vacancy status")
    }

    private fun createTestSearchConfig(
        id: Long = 1L,
        keywords: String = "Kotlin Developer",
    ): SearchConfig {
        return SearchConfig(
            id = id,
            keywords = keywords,
            minSalary = 100000,
            maxSalary = null,
            area = "Москва",
            experience = "От 3 лет",
            isActive = true,
        )
    }

    private fun createTestVacancyDto(id: String = "12345"): com.hhassistant.client.hh.dto.VacancyDto {
        return com.hhassistant.client.hh.dto.VacancyDto(
            id = id,
            name = "Senior Kotlin Developer",
            employer = com.hhassistant.client.hh.dto.EmployerDto(id = "1", name = "Tech Corp"),
            salary = com.hhassistant.client.hh.dto.SalaryDto(from = 200000, to = 300000, currency = "RUR"),
            area = com.hhassistant.client.hh.dto.AreaDto(id = "1", name = "Москва"),
            url = "https://hh.ru/vacancy/$id",
            description = "Ищем Senior Kotlin Developer",
            experience = com.hhassistant.client.hh.dto.ExperienceDto(id = "between3And6", name = "От 3 лет"),
            publishedAt = "2024-01-01T10:00:00+0300",
            snippet = null,
        )
    }

    private fun createTestVacancy(
        id: String = "12345",
        status: VacancyStatus = VacancyStatus.NEW,
    ): Vacancy {
        return Vacancy(
            id = id,
            name = "Senior Kotlin Developer",
            employer = "Tech Corp",
            salary = "200000 - 300000 RUR",
            area = "Москва",
            url = "https://hh.ru/vacancy/$id",
            description = "Ищем Senior Kotlin Developer",
            experience = "От 3 лет",
            publishedAt = java.time.LocalDateTime.now(),
            status = status,
        )
    }
}
