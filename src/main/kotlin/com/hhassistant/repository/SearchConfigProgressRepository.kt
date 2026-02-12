package com.hhassistant.repository

import com.hhassistant.domain.entity.SearchConfigProgress
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface SearchConfigProgressRepository : JpaRepository<SearchConfigProgress, Long> {
    /**
     * Находит прогресс по ключу конфигурации
     */
    fun findByConfigKey(configKey: String): Optional<SearchConfigProgress>

    /**
     * Удаляет прогресс по ключу конфигурации
     */
    fun deleteByConfigKey(configKey: String)
}
