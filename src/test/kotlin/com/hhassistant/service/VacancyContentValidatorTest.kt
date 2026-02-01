package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VacancyContentValidatorTest {
    @Test
    fun `validate returns valid when no exclusion rules configured`() {
        val validator = VacancyContentValidator(
            exclusionKeywords = emptyList(),
            exclusionPhrases = emptyList(),
            caseSensitive = false,
        )

        val result = validator.validate(testVacancy(description = "remote"))
        assertThat(result.isValid).isTrue
        assertThat(result.rejectionReason).isNull()
    }

    @Test
    fun `validate rejects vacancy when exclusion keyword matches (case-insensitive)`() {
        val validator = VacancyContentValidator(
            exclusionKeywords = listOf("remote"),
            exclusionPhrases = emptyList(),
            caseSensitive = false,
        )

        val result = validator.validate(testVacancy(description = "We offer REMOTE work"))
        assertThat(result.isValid).isFalse
        assertThat(result.rejectionReason).contains("запрещенные слова")
        assertThat(result.rejectionReason).contains("remote")
    }

    @Test
    fun `validate respects caseSensitive flag`() {
        val validator = VacancyContentValidator(
            exclusionKeywords = listOf("REMOTE"),
            exclusionPhrases = emptyList(),
            caseSensitive = true,
        )

        val shouldPass = validator.validate(testVacancy(description = "remote work"))
        assertThat(shouldPass.isValid).isTrue

        val shouldFail = validator.validate(testVacancy(description = "REMOTE work"))
        assertThat(shouldFail.isValid).isFalse
    }

    @Test
    fun `validate rejects vacancy when exclusion phrase matches`() {
        val validator = VacancyContentValidator(
            exclusionKeywords = emptyList(),
            exclusionPhrases = listOf("no relocation"),
            caseSensitive = false,
        )

        val result = validator.validate(testVacancy(description = "Offer: No Relocation support"))
        assertThat(result.isValid).isFalse
        assertThat(result.rejectionReason).contains("запрещенные фразы")
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
