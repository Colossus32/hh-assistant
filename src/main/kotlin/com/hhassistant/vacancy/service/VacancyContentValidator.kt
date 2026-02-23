package com.hhassistant.vacancy.service

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.vacancy.port.ContentValidatorPort
import com.hhassistant.monitoring.metrics.MetricsService
import com.hhassistant.service.exclusion.ExclusionRuleService
import com.hhassistant.service.resume.ResumeService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Validator for vacancy content by exclusion keywords and resume skills matching
 * Performs validation BEFORE LLM analysis to save resources
 * Uses ExclusionRuleService to get rules from database (with caching)
 * Checks if vacancy contains at least one skill from resume to skip LLM analysis
 */
@Component
class VacancyContentValidator(
    private val exclusionRuleService: ExclusionRuleService,
    private val resumeService: ResumeService,
    private val metricsService: MetricsService,
    // Fallback to config if DB is empty (for backward compatibility)
    @Value("\${app.analysis.exclusion-keywords:#{T(java.util.Collections).emptyList()}}")
    private val fallbackKeywords: List<String>,
    @Value("\${app.analysis.exclusion-case-sensitive:false}")
    private val fallbackCaseSensitive: Boolean,
    @Value("\${app.analysis.skill-matching.enabled:true}")
    private val skillMatchingEnabled: Boolean,
) : ContentValidatorPort {
    private val log = KotlinLogging.logger {}

    /**
     * Validates vacancy for exclusion keywords and resume skills matching
     * Performs multiple checks BEFORE LLM analysis to save resources:
     * 1. Exclusion keywords check
     * 2. Resume skills matching check (if enabled)
     *
     * @param vacancy Vacancy to validate
     * @return ValidationResult with information about whether vacancy is valid and rejection reason
     */
    override suspend fun validate(vacancy: Vacancy): ContentValidatorPort.ValidationResult {
        // Увеличиваем счетчик проверенных вакансий
        metricsService.incrementVacanciesValidated()

        // Step 1: Check exclusion keywords
        val exclusionResult = validateExclusionRules(vacancy)
        if (!exclusionResult.isValid) {
            metricsService.incrementVacanciesRejectedByValidator()
            return exclusionResult
        }

        // Step 2: Check resume skills matching (if enabled)
        if (skillMatchingEnabled) {
            val skillsResult = validateResumeSkills(vacancy)
            if (!skillsResult.isValid) {
                metricsService.incrementVacanciesRejectedByValidator()
                return skillsResult
            }
        }

        // Вакансия прошла валидацию
        metricsService.incrementVacanciesPassedValidation()
        return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
    }

    /**
     * Validates vacancy for exclusion keywords
     */
    private fun validateExclusionRules(vacancy: Vacancy): ContentValidatorPort.ValidationResult {
        // Get exclusion rules from database (cached), fallback to config if empty
        val exclusionKeywords = exclusionRuleService.getAllKeywords().takeIf { it.isNotEmpty() } ?: fallbackKeywords
        val caseSensitive = exclusionRuleService.isCaseSensitive() || fallbackCaseSensitive

        // If list is empty, validation is skipped
        if (exclusionKeywords.isEmpty()) {
            return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
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

        // Check keywords using word boundaries to avoid false positives
        // For example, "Java" should not match "JavaScript"
        val foundKeywords = exclusionKeywords.filter { keyword ->
            val normalizedKeyword = if (caseSensitive) keyword else keyword.lowercase()
            // Use word boundaries (\b) for exact word matching
            // Escape special regex characters in keyword
            val escapedKeyword = Regex.escape(normalizedKeyword)
            val pattern = Regex("\\b$escapedKeyword\\b", if (caseSensitive) RegexOption.IGNORE_CASE else RegexOption.IGNORE_CASE)
            pattern.containsMatchIn(normalizedText)
        }

        // If forbidden words found - vacancy is not suitable
        if (foundKeywords.isNotEmpty()) {
            val rejectionReason = "found exclusion keywords: ${foundKeywords.joinToString(", ")}"
            log.debug("[VacancyValidator] Vacancy ${vacancy.id} ('${vacancy.name}') rejected: $rejectionReason")

            return ContentValidatorPort.ValidationResult(
                isValid = false,
                rejectionReason = rejectionReason,
            )
        }

        return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
    }

    /**
     * Validates if vacancy contains at least one skill from resume
     * If no matching skills found - vacancy is not relevant and can skip LLM analysis
     *
     * @param vacancy Vacancy to validate
     * @return ValidationResult with information about skills matching
     */
    private suspend fun validateResumeSkills(vacancy: Vacancy): ContentValidatorPort.ValidationResult {
        try {
            // Load resume and get skills
            val resume = resumeService.loadResume()
            val resumeStructure = resumeService.getResumeStructure(resume)

            // If no resume structure or no skills - skip this check
            if (resumeStructure?.skills.isNullOrEmpty()) {
                log.debug("[VacancyValidator] No resume skills available, skipping skills matching check")
                return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
            }

            val resumeSkills = resumeStructure!!.skills

            // Combine vacancy text for checking
            val vacancyText = buildString {
                append(vacancy.name.lowercase())
                append(" ")
                vacancy.description?.let { append(it.lowercase()) }
            }

            // Check if at least one skill from resume is present in vacancy
            // Use word boundaries to avoid false positives (e.g., "Java" should not match "JavaScript")
            val matchingSkills = resumeSkills.count { skill ->
                val skillLower = skill.lowercase().trim()
                // Escape special regex characters and use word boundaries for exact word matching
                val escapedSkill = Regex.escape(skillLower)
                val pattern = Regex("\\b$escapedSkill\\b", RegexOption.IGNORE_CASE)

                // Check exact match with word boundaries
                pattern.containsMatchIn(vacancyText) ||
                    // Also check individual words if skill consists of multiple words
                    skillLower.split(" ").any { word ->
                        if (word.length > 3) {
                            val escapedWord = Regex.escape(word)
                            val wordPattern = Regex("\\b$escapedWord\\b", RegexOption.IGNORE_CASE)
                            wordPattern.containsMatchIn(vacancyText)
                        } else {
                            false
                        }
                    }
            }

            // If no matching skills found - vacancy is not relevant
            if (matchingSkills == 0) {
                log.debug(
                    "[VacancyValidator] Vacancy ${vacancy.id} ('${vacancy.name}') rejected: no matching skills from resume",
                )
                return ContentValidatorPort.ValidationResult(
                    isValid = false,
                    rejectionReason = "no matching skills from resume (checked ${resumeSkills.size} skills)",
                )
            }

            log.debug(
                "[VacancyValidator] Vacancy ${vacancy.id} passed skills matching check ($matchingSkills/${resumeSkills.size} skills matched)",
            )
            return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
        } catch (e: Exception) {
            // If resume loading fails - don't block validation, just log and continue
            log.warn("[VacancyValidator] Failed to load resume for skills matching: ${e.message}", e)
            return ContentValidatorPort.ValidationResult(isValid = true, rejectionReason = null)
        }
    }
}
