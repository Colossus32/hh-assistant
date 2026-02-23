package com.hhassistant.service

import com.hhassistant.integration.hh.HHVacancyClient
import com.hhassistant.integration.hh.dto.VacancyDto
import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.exception.VacancyProcessingException
import com.hhassistant.vacancy.repository.VacancyRepository
import com.hhassistant.service.audit.ConfigAuditSummary
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.util.TokenRefreshService
import com.hhassistant.vacancy.service.SearchConfigProviderService
import com.hhassistant.vacancy.service.VacancyPersistenceService
import com.hhassistant.vacancy.service.VacancyService
import com.hhassistant.vacancy.service.VacancyStatusService
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
    private lateinit var formattingConfig: FormattingConfig
    private lateinit var notificationService: NotificationService
    private lateinit var tokenRefreshService: TokenRefreshService
    private lateinit var vacancyStatusService: VacancyStatusService
    private lateinit var vacancyPersistenceService: VacancyPersistenceService
    private lateinit var searchConfigProviderService: SearchConfigProviderService
    private lateinit var vacancyFetchAuditService: com.hhassistant.service.audit.VacancyFetchAuditService
    private lateinit var processedVacancyCacheService: com.hhassistant.vacancy.service.ProcessedVacancyCacheService
    private lateinit var service: VacancyService

    @BeforeEach
    fun setup() {
        hhVacancyClient = mockk()
        vacancyRepository = mockk()
        formattingConfig = FormattingConfig(
            defaultCurrency = "RUR",
            areaNotSpecified = "Не указан",
        )
        notificationService = mockk(relaxed = true)
        tokenRefreshService = mockk(relaxed = true)
        vacancyStatusService = mockk(relaxed = true)
        vacancyPersistenceService = mockk(relaxed = true)
        searchConfigProviderService = mockk(relaxed = true)
        vacancyFetchAuditService = mockk(relaxed = true)
        processedVacancyCacheService = mockk(relaxed = true)
        service = VacancyService(
            hhVacancyClient = hhVacancyClient,
            vacancyRepository = vacancyRepository,
            formattingConfig = formattingConfig,
            notificationService = notificationService,
            tokenRefreshService = tokenRefreshService,
            vacancyStatusService = vacancyStatusService,
            processedVacancyCacheService = processedVacancyCacheService,
            vacancyFetchAuditService = vacancyFetchAuditService,
            vacancyPersistenceService = vacancyPersistenceService,
            searchConfigProviderService = searchConfigProviderService,
            maxVacanciesPerCycle = 50,
        )
    }

    @Test
    fun `should fetch and save new vacancies`() {
        runBlocking {
            val config = createTestSearchConfig()
            val vacancyDto = createTestVacancyDto()
            val existingIds = emptySet<String>()
            val savedVacancy = createTestVacancy()
            val auditSummary = ConfigAuditSummary(
                configKey = "key",
                keywords = config.keywords,
                totalReceived = 1,
                valid = 1,
                invalid = 0,
                duplicates = 0,
                saved = 1,
                details = emptyList(),
            )

            every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns listOf(vacancyDto)
            every { vacancyPersistenceService.getAllVacancyIds() } returns existingIds
            every { vacancyFetchAuditService.auditVacanciesForConfig(any(), any(), any()) } returns auditSummary
            every { vacancyPersistenceService.saveVacanciesInTransaction(any()) } returns listOf(savedVacancy)
            every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).hasSize(1)
            coVerify { hhVacancyClient.searchVacancies(config) }
            verify { vacancyPersistenceService.saveVacanciesInTransaction(any()) }
        }
    }

    @Test
    fun `should skip existing vacancies`() {
        runBlocking {
            val config = createTestSearchConfig()
            val vacancyDto = createTestVacancyDto()
            val existingIds = setOf("12345") // Вакансия уже существует
            val auditSummary = ConfigAuditSummary(
                configKey = "key",
                keywords = config.keywords,
                totalReceived = 1,
                valid = 0,
                invalid = 0,
                duplicates = 1,
                saved = 0,
                details = emptyList(),
            )

            every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns listOf(vacancyDto)
            every { vacancyPersistenceService.getAllVacancyIds() } returns existingIds
            every { vacancyFetchAuditService.auditVacanciesForConfig(any(), any(), any()) } returns auditSummary

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
            verify(exactly = 0) { vacancyPersistenceService.saveVacanciesInTransaction(any()) }
        }
    }

    @Test
    fun `should return empty list when no active configs`() {
        runBlocking {
            every { searchConfigProviderService.getActiveSearchConfigs() } returns emptyList()

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
            coVerify(exactly = 0) { hhVacancyClient.searchVacancies(any()) }
        }
    }

    @Test
    fun `should handle rate limit exception gracefully`() {
        runBlocking {
            val config = createTestSearchConfig()

            every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } throws HHAPIException.RateLimitException("Rate limit")

            val result = service.fetchAndSaveNewVacancies()

            assertThat(result.vacancies).isEmpty()
        }
    }

    @Test
    fun `should continue with other configs when one fails`() {
        runBlocking {
            val config1 = createTestSearchConfig(id = 1L, keywords = "Kotlin")
            val config2 = createTestSearchConfig(id = 2L, keywords = "Java")
            val vacancyDto = createTestVacancyDto()
            val existingIds = emptySet<String>()
            val savedVacancy = createTestVacancy()
            val auditSummary = ConfigAuditSummary(
                configKey = "key",
                keywords = config2.keywords,
                totalReceived = 1,
                valid = 1,
                invalid = 0,
                duplicates = 0,
                saved = 1,
                details = emptyList(),
            )

            every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(config1, config2)
            coEvery { hhVacancyClient.searchVacancies(config1) } throws
                HHAPIException.ConnectionException("Connection error")
            coEvery { hhVacancyClient.searchVacancies(config2) } returns listOf(vacancyDto)
            every { vacancyPersistenceService.getAllVacancyIds() } returns existingIds
            every { vacancyFetchAuditService.auditVacanciesForConfig(any(), any(), any()) } returns auditSummary
            every { vacancyPersistenceService.saveVacanciesInTransaction(any()) } returns listOf(savedVacancy)
            every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit

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
            val existingIds = emptySet<String>()
            val savedVacancies = vacancyDtos.take(50).map { createTestVacancy(id = it.id) }
            val auditSummary = ConfigAuditSummary(
                configKey = "key",
                keywords = config.keywords,
                totalReceived = 60,
                valid = 60,
                invalid = 0,
                duplicates = 0,
                saved = 50,
                details = emptyList(),
            )

            every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(config)
            coEvery { hhVacancyClient.searchVacancies(config) } returns vacancyDtos
            every { vacancyPersistenceService.getAllVacancyIds() } returns existingIds
            every { vacancyFetchAuditService.auditVacanciesForConfig(any(), any(), any()) } returns auditSummary
            every { vacancyPersistenceService.saveVacanciesInTransaction(any()) } returns savedVacancies
            every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit

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
        every { vacancyPersistenceService.evictVacancyIdsCache() } returns Unit

        service.updateVacancyStatus(updatedVacancy)

        verify { vacancyRepository.save(any<Vacancy>()) }
        verify { vacancyPersistenceService.evictVacancyIdsCache() }
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

        verify(exactly = 0) { vacancyPersistenceService.evictVacancyIdsCache() }
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

    private fun createTestVacancyDto(id: String = "12345"): com.hhassistant.integration.hh.dto.VacancyDto {
        return com.hhassistant.integration.hh.dto.VacancyDto(
            id = id,
            name = "Senior Kotlin Developer",
            employer = com.hhassistant.integration.hh.dto.EmployerDto(id = "1", name = "Tech Corp"),
            salary = com.hhassistant.integration.hh.dto.SalaryDto(from = 200000, to = 300000, currency = "RUR"),
            area = com.hhassistant.integration.hh.dto.AreaDto(id = "1", name = "Москва"),
            url = "https://hh.ru/vacancy/$id",
            description = "Ищем Senior Kotlin Developer",
            experience = com.hhassistant.integration.hh.dto.ExperienceDto(id = "between3And6", name = "От 3 лет"),
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
