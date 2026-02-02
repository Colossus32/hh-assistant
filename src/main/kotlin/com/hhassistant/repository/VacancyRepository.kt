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
     * Статус SKIPPED автоматически исключает NOT_INTERESTED (разные статусы).
     *
     * @param pageable Пагинация для ограничения количества результатов
     * @return Список вакансий со статусом SKIPPED, отсортированных по fetchedAt
     */
    @Query("SELECT v FROM Vacancy v WHERE v.status = 'SKIPPED' ORDER BY v.fetchedAt ASC")
    fun findSkippedVacanciesForRetry(pageable: Pageable): List<Vacancy>

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
    @Query("""
        SELECT v FROM Vacancy v
        INNER JOIN VacancyAnalysis va ON v.id = va.vacancyId
        WHERE va.isRelevant = true
        AND v.skillsExtractedAt IS NULL
        ORDER BY va.relevanceScore DESC, va.analyzedAt DESC
    """)
    fun findRelevantVacanciesWithoutSkills(): List<Vacancy>

    override fun existsById(id: String): Boolean
}
