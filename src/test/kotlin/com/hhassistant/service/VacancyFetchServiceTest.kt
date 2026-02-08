package com.hhassistant.service

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.config.FormattingConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.repository.SearchConfigRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.service.notification.NotificationService
import com.hhassistant.service.util.SearchConfigFactory
import com.hhassistant.service.util.TokenRefreshService
import com.hhassistant.service.vacancy.VacancyFetchService
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
    private lateinit var vacancyRepository: VacancyRepository
    private lateinit var searchConfigRepository: SearchConfigRepository
    private lateinit var formattingConfig: FormattingConfig
    private lateinit var notificationService: NotificationService
    private lateinit var tokenRefreshService: TokenRefreshService
    private lateinit var searchConfigFactory: SearchConfigFactory
    private lateinit var searchConfig: com.hhassistant.config.VacancyServiceConfig
    private lateinit var vacancyIdsCache: com.github.benmanes.caffeine.cache.Cache<String, Set<String>>
    private lateinit var metricsService: com.hhassistant.metrics.MetricsService
    private lateinit var vacancyProcessingQueueService: com.hhassistant.service.vacancy.VacancyProcessingQueueService
    private lateinit var exclusionKeywordService: com.hhassistant.service.exclusion.ExclusionKeywordService
    private lateinit var service: VacancyFetchService

    @BeforeEach
    fun setUp() {
        hhVacancyClient = mockk(relaxed = true)
        vacancyRepository = mockk(relaxed = true)
        searchConfigRepository = mockk(relaxed = true)
        formattingConfig = FormattingConfig(
            defaultCurrency = "RUR",
            areaNotSpecified = "Не указан",
        )
        notificationService = mockk(relaxed = true)
        tokenRefreshService = mockk(relaxed = true)
        searchConfigFactory = mockk(relaxed = true)
        searchConfig = com.hhassistant.config.VacancyServiceConfig()
        vacancyIdsCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build<String, Set<String>>()
        metricsService = mockk(relaxed = true)
        vacancyProcessingQueueService = mockk(relaxed = true)
        exclusionKeywordService = mockk(relaxed = true)

        service = VacancyFetchService(
            hhVacancyClient = hhVacancyClient,
            vacancyRepository = vacancyRepository,
            searchConfigRepository = searchConfigRepository,
            notificationService = notificationService,
            tokenRefreshService = tokenRefreshService,
            searchConfigFactory = searchConfigFactory,
            searchConfig = searchConfig,
            formattingConfig = formattingConfig,
            metricsService = metricsService,
            vacancyProcessingQueueService = vacancyProcessingQueueService,
            exclusionKeywordService = exclusionKeywordService,
            maxVacanciesPerCycle = 50,
            vacancyIdsCache = vacancyIdsCache,
        )
    }

    @Test
    fun `should fetch vacancies and save them`() = runBlocking {
        // Given
        val vacancyDto1 = createTestVacancyDto("1", "Java Developer")
        val vacancyDto2 = createTestVacancyDto("2", "Kotlin Developer")
        val searchConfig = createTestSearchConfig("Java")

        every { searchConfigRepository.findByIsActiveTrue() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns listOf(vacancyDto1, vacancyDto2)
        every { vacancyRepository.findAllIds() } returns emptyList()
        every { vacancyRepository.saveAll(any<List<Vacancy>>()) } answers { firstArg() }
        coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 2

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(2)
        verify(exactly = 1) { vacancyRepository.saveAll(any<List<Vacancy>>()) }
    }

    @Test
    fun `should return empty result when no new vacancies found`() = runBlocking {
        // Given
        val searchConfig = createTestSearchConfig("Java")

        every { searchConfigRepository.findByIsActiveTrue() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns emptyList()

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(0)
    }

    @Test
    fun `should filter out existing vacancies`() = runBlocking {
        // Given
        val vacancyDto1 = createTestVacancyDto("1", "Java Developer")
        val vacancyDto2 = createTestVacancyDto("2", "Kotlin Developer")
        val searchConfig = createTestSearchConfig("Java")

        every { searchConfigRepository.findByIsActiveTrue() } returns listOf(searchConfig)
        coEvery { hhVacancyClient.searchVacancies(any()) } returns listOf(vacancyDto1, vacancyDto2)
        every { vacancyRepository.existsById("1") } returns true // Уже существует
        every { vacancyRepository.existsById("2") } returns false
        every { vacancyRepository.saveAll(any<List<Vacancy>>()) } answers { firstArg() }

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

        every { searchConfigRepository.findByIsActiveTrue() } returns listOf(searchConfig1, searchConfig2)
        coEvery { hhVacancyClient.searchVacancies(any()) } returnsMany listOf(
            listOf(vacancyDto1),
            listOf(vacancyDto2),
        )
        every { vacancyRepository.findAllIds() } returns emptyList()
        every { vacancyRepository.saveAll(any<List<Vacancy>>()) } answers { firstArg() }
        coEvery { vacancyProcessingQueueService.enqueueBatch(any()) } returns 2

        // When
        val result = service.fetchAndSaveNewVacancies()

        // Then
        assertThat(result.vacancies.size).isEqualTo(2)
        verify(exactly = 1) { vacancyRepository.saveAll(any<List<Vacancy>>()) }
    }

    // Helper methods
    private fun createTestVacancyDto(id: String, name: String): VacancyDto {
        return VacancyDto(
            id = id,
            name = name,
            employer = com.hhassistant.client.hh.dto.EmployerDto(id = "1", name = "Test Company"),
            salary = null,
            area = com.hhassistant.client.hh.dto.AreaDto(id = "1", name = "Moscow"),
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
}
