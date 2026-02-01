package com.hhassistant.repository

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
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

    override fun existsById(id: String): Boolean
}
