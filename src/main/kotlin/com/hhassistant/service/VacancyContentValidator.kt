package com.hhassistant.service

import com.hhassistant.domain.entity.Vacancy
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Валидатор содержимого вакансии по ключевым словам/фразам
 * Выполняет проверку ДО анализа через LLM для экономии ресурсов
 */
@Component
class VacancyContentValidator(
    @Value("\${app.analysis.exclusion-keywords:#{T(java.util.Collections).emptyList()}}")
    private val exclusionKeywords: List<String>,
    @Value("\${app.analysis.exclusion-phrases:#{T(java.util.Collections).emptyList()}}")
    private val exclusionPhrases: List<String>,
    @Value("\${app.analysis.exclusion-case-sensitive:false}")
    private val caseSensitive: Boolean,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Проверяет вакансию на наличие запрещенных слов/фраз
     *
     * @param vacancy Вакансия для проверки
     * @return ValidationResult с информацией о том, подходит ли вакансия и причиной отклонения
     */
    fun validate(vacancy: Vacancy): ValidationResult {
        // Если списки пустые, валидация пропускается
        if (exclusionKeywords.isEmpty() && exclusionPhrases.isEmpty()) {
            return ValidationResult(isValid = true, rejectionReason = null)
        }

        // Объединяем все текстовые поля вакансии для проверки
        val textToCheck = buildString {
            append(vacancy.name)
            append(" ")
            append(vacancy.employer)
            vacancy.description?.let { append(" ").append(it) }
            append(" ").append(vacancy.area)
            vacancy.experience?.let { append(" ").append(it) }
        }

        val normalizedText = if (caseSensitive) textToCheck else textToCheck.lowercase()

        // Проверяем ключевые слова
        val foundKeywords = exclusionKeywords.filter { keyword ->
            val normalizedKeyword = if (caseSensitive) keyword else keyword.lowercase()
            normalizedText.contains(normalizedKeyword)
        }

        // Проверяем фразы
        val foundPhrases = exclusionPhrases.filter { phrase ->
            val normalizedPhrase = if (caseSensitive) phrase else phrase.lowercase()
            normalizedText.contains(normalizedPhrase)
        }

        // Если найдены запрещенные слова или фразы - вакансия не подходит
        if (foundKeywords.isNotEmpty() || foundPhrases.isNotEmpty()) {
            val reasons = mutableListOf<String>()
            if (foundKeywords.isNotEmpty()) {
                reasons.add("найдены запрещенные слова: ${foundKeywords.joinToString(", ")}")
            }
            if (foundPhrases.isNotEmpty()) {
                reasons.add("найдены запрещенные фразы: ${foundPhrases.joinToString(", ")}")
            }

            val rejectionReason = reasons.joinToString("; ")
            log.info("🚫 [VacancyValidator] Вакансия ${vacancy.id} ('${vacancy.name}') отклонена: $rejectionReason")

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
