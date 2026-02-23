package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Skill
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.integration.hh.dto.KeySkillDto

/**
 * Порт для сохранения навыков вакансии.
 * Изолирует VacancyAnalysisService от SkillExtractionService (PROJECT_REVIEW 3.2).
 */
interface SkillSaverPort {
    suspend fun extractAndSaveSkills(
        vacancy: Vacancy,
        keySkillsFromApi: List<KeySkillDto>? = null,
    ): List<Skill>
}
