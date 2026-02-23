package com.hhassistant.vacancy.port

import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.model.ResumeStructure

/**
 * Порт для анализа вакансии через LLM.
 * Изолирует VacancyAnalysisService от VacancyAnalyzerService/OllamaClient (PROJECT_REVIEW 3.2).
 * В тестах — один мок вместо цепочки OllamaClient + CircuitBreaker + Retry.
 */
interface VacancyLlmAnalyzer {
    data class AnalysisResult(
        val skills: List<String>,
        val relevanceScore: Double,
        val isRelevant: Boolean,
        val reasoning: String?,
    )

    suspend fun analyze(
        vacancy: Vacancy,
        resume: Resume,
        resumeStructure: ResumeStructure?,
    ): AnalysisResult
}
