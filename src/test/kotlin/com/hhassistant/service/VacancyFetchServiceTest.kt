package com.hhassistant.service

import com.hhassistant.integration.hh.HHVacancyClient
import com.hhassistant.integration.hh.dto.VacancyDto
import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.notification.service.NotificationService
import com.hhassistant.service.util.SearchConfigFactory
import com.hhassistant.service.util.TokenRefreshService
import com.hhassistant.vacancy.service.VacancyFetchService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
class VacancyFetchServiceTest {

    private lateinit var hhVacancyClient: HHVacancyClient
    private lateinit var formattingConfig: FormattingConfig
    private lateinit var notificationService: NotificationService
    private lateinit var tokenRefreshService: TokenRefreshService
    private lateinit var metricsService: com.hhassistant.monitoring.metrics.MetricsService
    private lateinit var vacancyProcessingQueueService: com.hhassistant.vacancy.service.VacancyProcessingQueueService
    private lateinit var exclusionKeywordService: com.hhassistant.service.exclusion.ExclusionKeywordService
    private lateinit var vacancyPersistenceService: com.hhassistant.vacancy.service.VacancyPersistenceService
    private lateinit var searchConfigProviderService: com.hhassistant.vacancy.service.SearchConfigProviderService
    private lateinit var service: VacancyFetchService

    @BeforeEach
    fun setUp() {
        hhVacancyClient = mockk(relaxed = true)
        formattingConfig = FormattingConfig(
            defaultCurrency = "RUR",
            areaNotSpecified = "Не указан",
        )
        notificationService = mockk(relaxed = true)
        tokenRefreshService = mockk(relaxed = true)
        metricsService = mockk(relaxed = true)
        vacancyProcessingQueueService = mockk(relaxed = true)
        exclusionKeywordService = mockk(relaxed = true)
        vacancyPersistenceService = mockk(relaxed = true)
        searchConfigProviderService = mockk(relaxed = true)

        service = VacancyFetchService(
            hhVacancyClient = hhVacancyClient,
            formattingConfig = formattingConfig,
            metricsService = metricsService,
            vacancyProcessingQueueService = vacancyProcessingQueueService,
            exclusionKeywordService = exclusionKeywordService,
            vacancyPersistenceService = vacancyPersistenceService,
            searchConfigProviderService = searchConfigProviderService,
            notificationService = notificationService,
            tokenRefreshService = tokenRefreshService,
            maxVacanciesPerCycle = 50,
        )
    }

    @Test
    fun `should fetch vacancies and save them`() = runBlocking {
        // Given
        val vacancyDto1 = createTestVacancyDto("1", "Java Developer")
        val vacancyDto2 = createTestVacancyDto("2", "Kotlin Developer")
        val searchConfig = createTestSearchConfig("Java")
        val savedVacancies = listOf(
            createTestVacancy("1", "Java"),
            createTestVacancy("2", "Kotlin"),
        )

        every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns listOf(vacancyDto1, vacancyDto2)
        every { vacancyPersistenceService.getAllVacancyIds() } returns emptySet()
        every { vacancyPersistenceService.saveVacanciesInBatches(any()) } returns savedVacancies
        every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit
        coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 2

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(2)
        verify(exactly = 1) { vacancyPersistenceService.saveVacanciesInBatches(any()) }
    }

    @Test
    fun `should return empty result when no new vacancies found`() = runBlocking {
        // Given
        val searchConfig = createTestSearchConfig("Java")

        every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns emptyList()

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(0)
    }

    @Test
    fun `should filter out existing vacancies`() = runBlocking {
        // Given - ID "1" already in DB, so only "2" is new
        val vacancyDto1 = createTestVacancyDto("1", "Java Developer")
        val vacancyDto2 = createTestVacancyDto("2", "Kotlin Developer")
        val searchConfig = createTestSearchConfig("Java")
        val savedVacancies = listOf(createTestVacancy("2", "Kotlin"))

        every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns listOf(vacancyDto1, vacancyDto2)
        every { vacancyPersistenceService.getAllVacancyIds() } returns setOf("1")
        every { vacancyPersistenceService.saveVacanciesInBatches(any()) } returns savedVacancies
        every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit
        coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 1

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(1)
        assertThat(result.vacancies[0].id).isEqualTo("2")
    }

    @Test
    fun `should fetch vacancies for multiple search configs`() = runBlocking {
        // Given
        val vacancyDto1 = createTestVacancyDto("1", "Java Developer")
        val vacancyDto2 = createTestVacancyDto("2", "Kotlin Developer")
        val searchConfig1 = createTestSearchConfig("Java")
        val searchConfig2 = createTestSearchConfig("Kotlin")
        val savedVacancies = listOf(
            Vacancy(id = "1", name = "Java", employer = "E", salary = null, area = "M", url = "u", description = null, experience = null, publishedAt = null, status = com.hhassistant.domain.entity.VacancyStatus.QUEUED),
            Vacancy(id = "2", name = "Kotlin", employer = "E", salary = null, area = "M", url = "u", description = null, experience = null, publishedAt = null, status = com.hhassistant.domain.entity.VacancyStatus.QUEUED),
        )

        every { searchConfigProviderService.getActiveSearchConfigs() } returns listOf(searchConfig1, searchConfig2)
        coEvery { hhVacancyClient.searchVacancies(any()) } returnsMany listOf(
            listOf(vacancyDto1),
            listOf(vacancyDto2),
        )
        every { vacancyPersistenceService.getAllVacancyIds() } returns emptySet()
        every { vacancyPersistenceService.saveVacanciesInBatches(any()) } returns savedVacancies
        every { vacancyPersistenceService.updateVacancyIdsCacheIncrementally(any()) } returns Unit
        coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 2

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(2)
        verify(exactly = 1) { vacancyPersistenceService.saveVacanciesInBatches(any()) }
    }

    // Helper methods
    private fun createTestVacancyDto(id: String, name: String): VacancyDto {
        return VacancyDto(
            id = id,
            name = name,
            employer = com.hhassistant.integration.hh.dto.EmployerDto(id = "1", name = "Test Company"),
            salary = null,
            area = com.hhassistant.integration.hh.dto.AreaDto(id = "1", name = "Moscow"),
            url = "https://api.hh.ru/vacancies/$id",
            description = "Test description",
            experience = null,
            publishedAt = "2024-01-01T00:00:00",
            snippet = null,
            alternateUrl = "https://hh.ru/vacancy/$id",
        )
    }

    private fun createTestSearchConfig(keywords: String): SearchConfig {
        return SearchConfig(
            keywords = keywords,
            area = "1",
            minSalary = null,
            maxSalary = null,
            experience = null,
            isActive = true,
        )
    }

    private fun createTestVacancy(id: String, name: String): Vacancy {
        return Vacancy(
            id = id,
            name = name,
            employer = "Test Company",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/$id",
            description = null,
            experience = null,
            publishedAt = null,
            status = VacancyStatus.QUEUED,
        )
    }
}
