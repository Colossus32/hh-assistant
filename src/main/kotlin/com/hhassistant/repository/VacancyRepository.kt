package com.hhassistant.repository

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface VacancyRepository : JpaRepository<Vacancy, String> {

    fun findByStatus(status: VacancyStatus): List<Vacancy>

    fun findByStatusIn(statuses: List<VacancyStatus>): List<Vacancy>

    @Query("SELECT v FROM Vacancy v WHERE v.status = :status AND v.sentToTelegramAt IS NOT NULL")
    fun findByStatusAndSentToTelegramAtIsNotNull(status: VacancyStatus): List<Vacancy>

    @Query("SELECT v FROM Vacancy v WHERE v.status IN :statuses AND v.sentToTelegramAt IS NULL")
    fun findByStatusInAndSentToTelegramAtIsNull(statuses: List<VacancyStatus>): List<Vacancy>

    @Query("SELECT v FROM Vacancy v WHERE v.sentToTelegramAt IS NOT NULL")
    fun findAllSentToTelegram(): List<Vacancy>

    @Query("SELECT v FROM Vacancy v WHERE v.sentToTelegramAt IS NULL AND v.status IN :statuses")
    fun findAllNotSentToTelegram(statuses: List<VacancyStatus>): List<Vacancy>

    @Query("SELECT v.id FROM Vacancy v")
    fun findAllIds(): List<String>

    /**
     * Получает список вакансий со статусом SKIPPED для повторной обработки.
     * Выполняется на стороне БД с лимитом через Pageable.
     * Ограничивает retry только вакансиями, которые были получены недавно (за последние 48 часов),
     * чтобы избежать бесконечного цикла retry для старых вакансий.
     *
     * @param pageable Пагинация для ограничения количества результатов
     * @return Список вакансий со статусом SKIPPED, отсортированных по fetchedAt
     */
    @Query(
        """
        SELECT v FROM Vacancy v 
        WHERE v.status = 'SKIPPED'
        AND v.fetchedAt >= :cutoffTime
        ORDER BY v.fetchedAt ASC
    """,
    )
    fun findSkippedVacanciesForRetry(pageable: Pageable, cutoffTime: java.time.LocalDateTime): List<Vacancy>

    /**
     * Получает список вакансий, для которых еще не извлечены навыки.
     * Использует поле skills_extracted_at для быстрой проверки без запросов к vacancy_skills.
     *
     * @return Список вакансий без извлеченных навыков
     */
    @Query("SELECT v FROM Vacancy v WHERE v.skillsExtractedAt IS NULL")
    fun findVacanciesWithoutSkills(): List<Vacancy>

    /**
     * Получает список релевантных вакансий, для которых еще не извлечены навыки.
     * Использует JOIN с VacancyAnalysis для поиска релевантных вакансий без навыков.
     *
     * @return Список релевантных вакансий без извлеченных навыков
     */
    @Query(
        """
        SELECT v FROM Vacancy v
        INNER JOIN VacancyAnalysis va ON v.id = va.vacancyId
        WHERE va.isRelevant = true
        AND v.skillsExtractedAt IS NULL
        ORDER BY va.relevanceScore DESC, va.analyzedAt DESC
    """,
    )
    fun findRelevantVacanciesWithoutSkills(): List<Vacancy>

    /**
     * Получает одну вакансию без навыков для обработки recovery механизма.
     * Использует поле skills_extracted_at для быстрой проверки.
     * Приоритет отдается релевантным вакансиям, затем по дате получения (более старые первыми).
     *
     * @param pageable Пагинация для ограничения результата одной вакансией
     * @return Вакансия без навыков или null, если таких нет
     */
    @Query(
        """
        SELECT v FROM Vacancy v
        LEFT JOIN VacancyAnalysis va ON v.id = va.vacancyId
        WHERE v.skillsExtractedAt IS NULL
        ORDER BY 
            CASE WHEN va.isRelevant = true THEN 0 ELSE 1 END,
            v.fetchedAt ASC
    """,
    )
    fun findOneVacancyWithoutSkills(pageable: Pageable): List<Vacancy>

    override fun existsById(id: String): Boolean

    /**
     * Подсчитывает количество вакансий в ожидании обработки (NEW и QUEUED)
     */
    @Query("SELECT COUNT(v) FROM Vacancy v WHERE v.status IN ('NEW', 'QUEUED')")
    fun countPendingVacancies(): Long

    /**
     * Подсчитывает количество вакансий со статусом SKIPPED
     */
    @Query("SELECT COUNT(v) FROM Vacancy v WHERE v.status = 'SKIPPED'")
    fun countSkippedVacancies(): Long
}
