package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.model.ResumeStructure

/**
 * Порт для получения резюме кандидата.
 * Изолирует VacancyAnalysisService от ResumeService (PROJECT_REVIEW 3.2 — слабая изоляция).
 * Упрощает тестирование: вместо мока ResumeService с БД/API/PDF — мок одного интерфейса.
 */
interface ResumeProvider {
    suspend fun loadResume(): Resume
    fun getResumeStructure(resume: Resume): ResumeStructure?
}
