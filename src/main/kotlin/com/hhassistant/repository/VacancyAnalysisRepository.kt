package com.hhassistant.repository

import com.hhassistant.domain.entity.CoverLetterGenerationStatus
import com.hhassistant.domain.entity.VacancyAnalysis
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface VacancyAnalysisRepository : JpaRepository<VacancyAnalysis, Long> {

    fun findByVacancyId(vacancyId: String): VacancyAnalysis?

    fun findByIsRelevant(isRelevant: Boolean): List<VacancyAnalysis>

    @Query("SELECT va FROM VacancyAnalysis va WHERE va.isRelevant = true AND va.relevanceScore >= :minScore")
    fun findRelevantByMinScore(minScore: Double): List<VacancyAnalysis>

    /**
     * Находит анализы, которые находятся в очереди ретраев и еще не достигли максимального количества попыток
     */
    @Query(
        "SELECT va FROM VacancyAnalysis va WHERE va.coverLetterGenerationStatus = :status AND va.coverLetterAttempts < :maxAttempts",
    )
    fun findByCoverLetterGenerationStatusAndCoverLetterAttemptsLessThan(
        status: CoverLetterGenerationStatus,
        maxAttempts: Int,
    ): List<VacancyAnalysis>

    /**
     * Находит анализы с неудачной генерацией письма (для повторной попытки)
     */
    fun findByCoverLetterGenerationStatus(status: CoverLetterGenerationStatus): List<VacancyAnalysis>
}
