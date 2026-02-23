package com.hhassistant.vacancy.repository

import com.hhassistant.domain.entity.VacancySkill
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * Репозиторий для работы со связями вакансий и навыков.
 *
 * Предоставляет методы для:
 * - Поиска всех навыков для конкретной вакансии
 * - Поиска всех вакансий с конкретным навыком
 * - Подсчета статистики связей
 */
@Repository
interface VacancySkillRepository : JpaRepository<VacancySkill, Long> {
    /**
     * Находит все навыки для конкретной вакансии
     *
     * @param vacancyId ID вакансии
     * @return Список связей вакансия-навык
     */
    fun findByVacancyId(vacancyId: String): List<VacancySkill>

    /**
     * Находит все вакансии, в которых встречается конкретный навык
     *
     * @param skillId ID навыка
     * @return Список связей вакансия-навык
     */
    fun findBySkillId(skillId: Long): List<VacancySkill>

    /**
     * Проверяет, существует ли связь между вакансией и навыком
     */
    fun existsByVacancyIdAndSkillId(vacancyId: String, skillId: Long): Boolean

    /**
     * Удаляет все связи для конкретной вакансии
     * (полезно при переизвлечении навыков)
     */
    fun deleteByVacancyId(vacancyId: String)

    /**
     * Подсчитывает количество вакансий, в которых встречается навык
     * (это должно совпадать с occurrenceCount в Skill, но может быть полезно для проверки)
     */
    @Query("SELECT COUNT(DISTINCT vs.vacancyId) FROM VacancySkill vs WHERE vs.skillId = :skillId")
    fun countVacanciesBySkillId(skillId: Long): Long

    /**
     * Подсчитывает общее количество проанализированных вакансий
     * (вакансии, из которых были извлечены навыки)
     */
    @Query("SELECT COUNT(DISTINCT vs.vacancyId) FROM VacancySkill vs")
    fun countDistinctVacancies(): Long
}
