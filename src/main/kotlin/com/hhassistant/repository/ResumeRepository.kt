package com.hhassistant.repository

import com.hhassistant.domain.entity.Resume
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ResumeRepository : JpaRepository<Resume, Long> {

    fun findByIsActiveTrue(): List<Resume>

    fun findFirstByIsActiveTrue(): Resume?
}
