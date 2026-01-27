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
    
    @Query("SELECT v.id FROM Vacancy v")
    fun findAllIds(): List<String>
    
    override fun existsById(id: String): Boolean
}

