package com.hhassistant.repository

import com.hhassistant.domain.entity.SearchConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SearchConfigRepository : JpaRepository<SearchConfig, Long> {
    
    fun findByIsActiveTrue(): List<SearchConfig>
}

