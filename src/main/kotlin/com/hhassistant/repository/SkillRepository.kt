package com.hhassistant.repository

import com.hhassistant.domain.entity.Skill
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Репозиторий для работы с навыками.
 *
 * Предоставляет методы для:
 * - Поиска навыков по названию (оригинальному и нормализованному)
 * - Получения топ навыков по популярности
 * - Фильтрации навыков по количеству встречаемости
 */
@Repository
interface SkillRepository : JpaRepository<Skill, Long> {
    /**
     * Находит навык по оригинальному названию
     */
    fun findByName(name: String): Optional<Skill>

    /**
     * Находит навык по нормализованному названию
     * Используется для поиска синонимов и объединения одинаковых навыков
     */
    fun findByNormalizedName(normalizedName: String): Optional<Skill>

    /**
     * Получает топ навыков по количеству встречаемости (по убыванию)
     *
     * @return Список навыков, отсортированный по occurrenceCount (от большего к меньшему)
     * Используйте Pageable для ограничения количества результатов
     */
    @Query("SELECT s FROM Skill s ORDER BY s.occurrenceCount DESC, s.name ASC")
    fun findTopSkills(): List<Skill>

    /**
     * Находит все навыки, которые встречаются не менее указанного количества раз
     *
     * @param minCount Минимальное количество встречаемости
     * @return Список навыков, отсортированный по occurrenceCount (от большего к меньшему)
     */
    @Query("SELECT s FROM Skill s WHERE s.occurrenceCount >= :minCount ORDER BY s.occurrenceCount DESC")
    fun findAllByOccurrenceCountGreaterThanEqual(minCount: Int): List<Skill>

    /**
     * Подсчитывает общее количество уникальных навыков в базе
     */
    @Query("SELECT COUNT(s) FROM Skill s")
    fun countAllSkills(): Long

    /**
     * Подсчитывает общее количество встречаемости всех навыков
     * (сумма всех occurrenceCount)
     */
    @Query("SELECT COALESCE(SUM(s.occurrenceCount), 0) FROM Skill s")
    fun sumAllOccurrences(): Long
}
