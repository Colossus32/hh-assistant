package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.service.exclusion.ExclusionRuleService
import com.hhassistant.service.resume.ResumeService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VacancyContentValidatorTest {
    private val exclusionRuleService = mockk<ExclusionRuleService>(relaxed = true)
    private val resumeService = mockk<ResumeService>(relaxed = true)

    @Test
    fun `validate returns valid when no exclusion rules configured`() = runBlocking {
        every { exclusionRuleService.getAllKeywords() } returns emptyList()
        every { exclusionRuleService.getAllPhrases() } returns emptyList()
        every { exclusionRuleService.isCaseSensitive() } returns false

        val validator = VacancyContentValidator(
            exclusionRuleService = exclusionRuleService,
            resumeService = resumeService,
            fallbackKeywords = emptyList(),
            fallbackPhrases = emptyList(),
            fallbackCaseSensitive = false,
            skillMatchingEnabled = false, // Отключаем проверку навыков для этого теста
        )

        val result = validator.validate(testVacancy(description = "remote"))
        assertThat(result.isValid).isTrue
        assertThat(result.rejectionReason).isNull()
    }

    @Test
    fun `validate rejects vacancy when exclusion keyword matches (case-insensitive)`() = runBlocking {
        every { exclusionRuleService.getAllKeywords() } returns listOf("remote")
        every { exclusionRuleService.getAllPhrases() } returns emptyList()
        every { exclusionRuleService.isCaseSensitive() } returns false

        val validator = VacancyContentValidator(
            exclusionRuleService = exclusionRuleService,
            resumeService = resumeService,
            fallbackKeywords = emptyList(),
            fallbackPhrases = emptyList(),
            fallbackCaseSensitive = false,
            skillMatchingEnabled = false,
        )

        val result = validator.validate(testVacancy(description = "We offer REMOTE work"))
        assertThat(result.isValid).isFalse
        assertThat(result.rejectionReason).contains("exclusion keywords")
        assertThat(result.rejectionReason).contains("remote")
    }

    @Test
    fun `validate respects caseSensitive flag`() = runBlocking {
        every { exclusionRuleService.getAllKeywords() } returns listOf("REMOTE")
        every { exclusionRuleService.getAllPhrases() } returns emptyList()
        every { exclusionRuleService.isCaseSensitive() } returns true

        val validator = VacancyContentValidator(
            exclusionRuleService = exclusionRuleService,
            resumeService = resumeService,
            fallbackKeywords = emptyList(),
            fallbackPhrases = emptyList(),
            fallbackCaseSensitive = true,
            skillMatchingEnabled = false,
        )

        val shouldPass = validator.validate(testVacancy(description = "remote work"))
        assertThat(shouldPass.isValid).isTrue

        val shouldFail = validator.validate(testVacancy(description = "REMOTE work"))
        assertThat(shouldFail.isValid).isFalse
    }

    @Test
    fun `validate rejects vacancy when exclusion phrase matches`() = runBlocking {
        every { exclusionRuleService.getAllKeywords() } returns emptyList()
        every { exclusionRuleService.getAllPhrases() } returns listOf("no relocation")
        every { exclusionRuleService.isCaseSensitive() } returns false

        val validator = VacancyContentValidator(
            exclusionRuleService = exclusionRuleService,
            resumeService = resumeService,
            fallbackKeywords = emptyList(),
            fallbackPhrases = emptyList(),
            fallbackCaseSensitive = false,
            skillMatchingEnabled = false,
        )

        val result = validator.validate(testVacancy(description = "Offer: No Relocation support"))
        assertThat(result.isValid).isFalse
        assertThat(result.rejectionReason).contains("exclusion phrases")
    }

    private fun testVacancy(description: String?): Vacancy {
        return Vacancy(
            id = "1",
            name = "Kotlin Developer",
            employer = "Test Corp",
            salary = null,
            area = "Moscow",
            url = "https://hh.ru/vacancy/1",
            description = description,
            experience = "3-6 years",
            publishedAt = LocalDateTime.of(2024, 1, 1, 10, 0),
            status = VacancyStatus.NEW,
        )
    }
}
