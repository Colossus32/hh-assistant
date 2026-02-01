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

    override fun existsById(id: String): Boolean
}
