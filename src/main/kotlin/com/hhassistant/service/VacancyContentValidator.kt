package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Validator for vacancy content by exclusion keywords/phrases
 * Performs validation BEFORE LLM analysis to save resources
 * Uses ExclusionRuleService to get rules from database (with caching)
 */
@Component
class VacancyContentValidator(
    private val exclusionRuleService: ExclusionRuleService,
    // Fallback to config if DB is empty (for backward compatibility)
    @Value("\${app.analysis.exclusion-keywords:#{T(java.util.Collections).emptyList()}}")
    private val fallbackKeywords: List<String>,
    @Value("\${app.analysis.exclusion-phrases:#{T(java.util.Collections).emptyList()}}")
    private val fallbackPhrases: List<String>,
    @Value("\${app.analysis.exclusion-case-sensitive:false}")
    private val fallbackCaseSensitive: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Validates vacancy for exclusion keywords/phrases
     *
     * @param vacancy Vacancy to validate
     * @return ValidationResult with information about whether vacancy is valid and rejection reason
     */
    fun validate(vacancy: Vacancy): ValidationResult {
        // Get exclusion rules from database (cached), fallback to config if empty
        val exclusionKeywords = exclusionRuleService.getAllKeywords().takeIf { it.isNotEmpty() } ?: fallbackKeywords
        val exclusionPhrases = exclusionRuleService.getAllPhrases().takeIf { it.isNotEmpty() } ?: fallbackPhrases
        val caseSensitive = exclusionRuleService.isCaseSensitive() || fallbackCaseSensitive

        // If lists are empty, validation is skipped
        if (exclusionKeywords.isEmpty() && exclusionPhrases.isEmpty()) {
            return ValidationResult(isValid = true, rejectionReason = null)
        }

        // Combine all text fields of vacancy for checking
        val textToCheck = buildString {
            append(vacancy.name)
            append(" ")
            append(vacancy.employer)
            vacancy.description?.let { append(" ").append(it) }
            append(" ").append(vacancy.area)
            vacancy.experience?.let { append(" ").append(it) }
        }

        val normalizedText = if (caseSensitive) textToCheck else textToCheck.lowercase()

        // Check keywords
        val foundKeywords = exclusionKeywords.filter { keyword ->
            val normalizedKeyword = if (caseSensitive) keyword else keyword.lowercase()
            normalizedText.contains(normalizedKeyword)
        }

        // Check phrases
        val foundPhrases = exclusionPhrases.filter { phrase ->
            val normalizedPhrase = if (caseSensitive) phrase else phrase.lowercase()
            normalizedText.contains(normalizedPhrase)
        }

        // If forbidden words or phrases found - vacancy is not suitable
        if (foundKeywords.isNotEmpty() || foundPhrases.isNotEmpty()) {
            val reasons = mutableListOf<String>()
            if (foundKeywords.isNotEmpty()) {
                reasons.add("found exclusion keywords: ${foundKeywords.joinToString(", ")}")
            }
            if (foundPhrases.isNotEmpty()) {
                reasons.add("found exclusion phrases: ${foundPhrases.joinToString(", ")}")
            }

            val rejectionReason = reasons.joinToString("; ")
            log.debug("[VacancyValidator] Vacancy ${vacancy.id} ('${vacancy.name}') rejected: $rejectionReason")

            return ValidationResult(
                isValid = false,
                rejectionReason = rejectionReason,
            )
        }

        return ValidationResult(isValid = true, rejectionReason = null)
    }

    /**
     * Результат валидации вакансии
     */
    data class ValidationResult(
        /**
         * true если вакансия прошла валидацию, false если найдены запрещенные слова/фразы
         */
        val isValid: Boolean,

        /**
         * Причина отклонения (если isValid = false)
         */
        val rejectionReason: String?,
    )
}
