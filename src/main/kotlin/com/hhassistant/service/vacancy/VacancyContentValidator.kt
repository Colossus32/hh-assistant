package com.hhassistant.service.vacancy

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.service.exclusion.ExclusionRuleService
import com.hhassistant.service.resume.ResumeService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Validator for vacancy content by exclusion keywords/phrases and resume skills matching
 * Performs validation BEFORE LLM analysis to save resources
 * Uses ExclusionRuleService to get rules from database (with caching)
 * Checks if vacancy contains at least one skill from resume to skip LLM analysis
 */
@Component
class VacancyContentValidator(
    private val exclusionRuleService: ExclusionRuleService,
    private val resumeService: ResumeService,
    // Fallback to config if DB is empty (for backward compatibility)
    @Value("\${app.analysis.exclusion-keywords:#{T(java.util.Collections).emptyList()}}")
    private val fallbackKeywords: List<String>,
    @Value("\${app.analysis.exclusion-phrases:#{T(java.util.Collections).emptyList()}}")
    private val fallbackPhrases: List<String>,
    @Value("\${app.analysis.exclusion-case-sensitive:false}")
    private val fallbackCaseSensitive: Boolean,
    @Value("\${app.analysis.skill-matching.enabled:true}")
    private val skillMatchingEnabled: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Validates vacancy for exclusion keywords/phrases and resume skills matching
     * Performs multiple checks BEFORE LLM analysis to save resources:
     * 1. Exclusion keywords/phrases check
     * 2. Resume skills matching check (if enabled)
     *
     * @param vacancy Vacancy to validate
     * @return ValidationResult with information about whether vacancy is valid and rejection reason
     */
    suspend fun validate(vacancy: Vacancy): ValidationResult {
        // Step 1: Check exclusion keywords/phrases
        val exclusionResult = validateExclusionRules(vacancy)
        if (!exclusionResult.isValid) {
            return exclusionResult
        }

        // Step 2: Check resume skills matching (if enabled)
        if (skillMatchingEnabled) {
            val skillsResult = validateResumeSkills(vacancy)
            if (!skillsResult.isValid) {
                return skillsResult
            }
        }

        return ValidationResult(isValid = true, rejectionReason = null)
    }

    /**
     * Validates vacancy for exclusion keywords/phrases
     */
    private fun validateExclusionRules(vacancy: Vacancy): ValidationResult {
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
     * Validates if vacancy contains at least one skill from resume
     * If no matching skills found - vacancy is not relevant and can skip LLM analysis
     *
     * @param vacancy Vacancy to validate
     * @return ValidationResult with information about skills matching
     */
    private suspend fun validateResumeSkills(vacancy: Vacancy): ValidationResult {
        try {
            // Load resume and get skills
            val resume = resumeService.loadResume()
            val resumeStructure = resumeService.getResumeStructure(resume)

            // If no resume structure or no skills - skip this check
            if (resumeStructure?.skills.isNullOrEmpty()) {
                log.debug("[VacancyValidator] No resume skills available, skipping skills matching check")
                return ValidationResult(isValid = true, rejectionReason = null)
            }

            val resumeSkills = resumeStructure!!.skills

            // Combine vacancy text for checking
            val vacancyText = buildString {
                append(vacancy.name.lowercase())
                append(" ")
                vacancy.description?.let { append(it.lowercase()) }
            }

            // Check if at least one skill from resume is present in vacancy
            val matchingSkills = resumeSkills.count { skill ->
                val skillLower = skill.lowercase().trim()
                // Check exact match or word match
                vacancyText.contains(skillLower) ||
                    skillLower.split(" ").any { word ->
                        word.length > 3 && vacancyText.contains(word)
                    }
            }

            // If no matching skills found - vacancy is not relevant
            if (matchingSkills == 0) {
                log.debug("[VacancyValidator] Vacancy ${vacancy.id} ('${vacancy.name}') rejected: no matching skills from resume")
                return ValidationResult(
                    isValid = false,
                    rejectionReason = "no matching skills from resume (checked ${resumeSkills.size} skills)",
                )
            }

            log.debug("[VacancyValidator] Vacancy ${vacancy.id} passed skills matching check ($matchingSkills/${resumeSkills.size} skills matched)")
            return ValidationResult(isValid = true, rejectionReason = null)
        } catch (e: Exception) {
            // If resume loading fails - don't block validation, just log and continue
            log.warn("[VacancyValidator] Failed to load resume for skills matching: ${e.message}", e)
            return ValidationResult(isValid = true, rejectionReason = null)
        }
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
