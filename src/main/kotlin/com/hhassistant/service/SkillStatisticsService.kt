package com.hhassistant.service

import com.hhassistant.repository.SkillRepository
import com.hhassistant.repository.VacancySkillRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для работы со статистикой навыков.
 *
 * Предоставляет методы для:
 * - Получения топ навыков по популярности
 * - Расчет частоты встречаемости навыков
 * - Подсчета общего количества проанализированных вакансий
 */
@Service
class SkillStatisticsService(
    private val skillRepository: SkillRepository,
    private val vacancySkillRepository: VacancySkillRepository,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает топ навыков по частоте встречаемости.
     *
     * @param limit Максимальное количество навыков для возврата
     * @return Список статистики навыков, отсортированный по частоте (от большего к меньшему)
     */
    @Transactional(readOnly = true)
    fun getTopSkills(limit: Int = 20): List<SkillStatistics> {
        log.debug("📊 [SkillStatistics] Getting top $limit skills")

        val totalVacancies = getTotalAnalyzedVacancies()
        if (totalVacancies == 0L) {
            log.warn("⚠️ [SkillStatistics] No analyzed vacancies found")
            return emptyList()
        }

        val topSkills = skillRepository.findTopSkills()
            .take(limit)

        return topSkills.map { skill ->
            SkillStatistics(
                skillName = skill.name,
                occurrenceCount = skill.occurrenceCount,
                totalVacanciesAnalyzed = totalVacancies.toInt(),
                frequencyPercentage = calculateFrequencyPercentage(skill.occurrenceCount, totalVacancies),
            )
        }
    }

    /**
     * Получает статистику для конкретного навыка.
     *
     * @param skillName Название навыка (оригинальное или нормализованное)
     * @return Статистика навыка или null, если навык не найден
     */
    @Transactional(readOnly = true)
    fun getSkillStatistics(skillName: String): SkillStatistics? {
        log.debug("📊 [SkillStatistics] Getting statistics for skill: $skillName")

        val skill = skillRepository.findByName(skillName)
            .orElseGet {
                // Пробуем найти по нормализованному имени
                skillRepository.findByNormalizedName(normalizeSkillName(skillName))
                    .orElse(null)
            }

        if (skill == null) {
            log.debug("⚠️ [SkillStatistics] Skill not found: $skillName")
            return null
        }

        val totalVacancies = getTotalAnalyzedVacancies()
        if (totalVacancies == 0L) {
            return SkillStatistics(
                skillName = skill.name,
                occurrenceCount = skill.occurrenceCount,
                totalVacanciesAnalyzed = 0,
                frequencyPercentage = 0.0,
            )
        }

        return SkillStatistics(
            skillName = skill.name,
            occurrenceCount = skill.occurrenceCount,
            totalVacanciesAnalyzed = totalVacancies.toInt(),
            frequencyPercentage = calculateFrequencyPercentage(skill.occurrenceCount, totalVacancies),
        )
    }

    /**
     * Получает общее количество проанализированных вакансий
     * (вакансии, из которых были извлечены навыки).
     */
    @Transactional(readOnly = true)
    fun getTotalAnalyzedVacancies(): Long {
        return vacancySkillRepository.countDistinctVacancies()
    }

    /**
     * Получает общее количество уникальных навыков в базе.
     */
    @Transactional(readOnly = true)
    fun getTotalSkillsCount(): Long {
        return skillRepository.countAllSkills()
    }

    /**
     * Рассчитывает процент встречаемости навыка.
     *
     * @param occurrenceCount Количество раз, когда навык был найден
     * @param totalVacancies Общее количество проанализированных вакансий
     * @return Процент встречаемости (0.0 - 100.0)
     */
    private fun calculateFrequencyPercentage(occurrenceCount: Int, totalVacancies: Long): Double {
        if (totalVacancies == 0L) {
            return 0.0
        }
        return (occurrenceCount.toDouble() / totalVacancies.toDouble()) * 100.0
    }

    /**
     * Нормализует название навыка (приведение к единому виду).
     */
    private fun normalizeSkillName(skill: String): String {
        return skill
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/**
 * DTO для статистики навыка.
 */
data class SkillStatistics(
    /**
     * Оригинальное название навыка
     */
    val skillName: String,

    /**
     * Количество вакансий, в которых был найден этот навык
     */
    val occurrenceCount: Int,

    /**
     * Общее количество проанализированных вакансий
     */
    val totalVacanciesAnalyzed: Int,

    /**
     * Процент встречаемости навыка (0.0 - 100.0)
     * Рассчитывается как: (occurrenceCount / totalVacanciesAnalyzed) * 100
     */
    val frequencyPercentage: Double,
)