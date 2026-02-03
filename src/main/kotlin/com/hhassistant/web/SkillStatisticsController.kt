package com.hhassistant.web

import com.hhassistant.service.skill.SkillStatistics
import com.hhassistant.service.skill.SkillStatisticsService
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API контроллер для получения статистики навыков.
 */
@RestController
@RequestMapping("/api/skills")
class SkillStatisticsController(
    private val skillStatisticsService: SkillStatisticsService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Получает топ навыков по популярности.
     *
     * GET /api/skills/top?limit=20
     *
     * @param limit Максимальное количество навыков для возврата (по умолчанию 20)
     * @return Список навыков с их статистикой
     */
    @GetMapping("/top")
    fun getTopSkills(
        @RequestParam(defaultValue = "20") limit: Int,
    ): ResponseEntity<TopSkillsResponse> {
        log.info("📊 [SkillStatistics API] Getting top $limit skills")

        return try {
            val skills = skillStatisticsService.getTopSkills(limit)
            val totalVacancies = skillStatisticsService.getTotalAnalyzedVacancies()

            val response = TopSkillsResponse(
                skills = skills,
                totalVacanciesAnalyzed = totalVacancies.toInt(),
            )

            log.info("✅ [SkillStatistics API] Returning ${skills.size} top skills (total vacancies: $totalVacancies)")
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            log.error("❌ [SkillStatistics API] Error getting top skills: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Получает статистику для конкретного навыка.
     *
     * GET /api/skills/{skillName}
     *
     * @param skillName Название навыка
     * @return Статистика навыка или 404, если навык не найден
     */
    @GetMapping("/{skillName}")
    fun getSkillStatistics(
        @PathVariable skillName: String,
    ): ResponseEntity<SkillStatistics> {
        log.info("📊 [SkillStatistics API] Getting statistics for skill: $skillName")

        return try {
            val statistics = skillStatisticsService.getSkillStatistics(skillName)

            if (statistics == null) {
                log.warn("⚠️ [SkillStatistics API] Skill not found: $skillName")
                ResponseEntity.notFound().build()
            } else {
                log.info("✅ [SkillStatistics API] Returning statistics for skill: $skillName")
                ResponseEntity.ok(statistics)
            }
        } catch (e: Exception) {
            log.error("❌ [SkillStatistics API] Error getting skill statistics: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    /**
     * Получает общую статистику по навыкам.
     *
     * GET /api/skills/stats
     *
     * @return Общая статистика (количество навыков, проанализированных вакансий)
     */
    @GetMapping("/stats")
    fun getOverallStatistics(): ResponseEntity<OverallStatisticsResponse> {
        log.info("📊 [SkillStatistics API] Getting overall statistics")

        return try {
            val totalSkills = skillStatisticsService.getTotalSkillsCount()
            val totalVacancies = skillStatisticsService.getTotalAnalyzedVacancies()

            val response = OverallStatisticsResponse(
                totalSkills = totalSkills.toInt(),
                totalVacanciesAnalyzed = totalVacancies.toInt(),
            )

            log.info(
                "✅ [SkillStatistics API] Returning overall statistics: $totalSkills skills, $totalVacancies vacancies",
            )
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            log.error("❌ [SkillStatistics API] Error getting overall statistics: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }
}

/**
 * Response DTO для топ навыков.
 */
data class TopSkillsResponse(
    /**
     * Список навыков с их статистикой
     */
    val skills: List<SkillStatistics>,

    /**
     * Общее количество проанализированных вакансий
     */
    val totalVacanciesAnalyzed: Int,
)

/**
 * Response DTO для общей статистики.
 */
data class OverallStatisticsResponse(
    /**
     * Общее количество уникальных навыков в базе
     */
    val totalSkills: Int,

    /**
     * Общее количество проанализированных вакансий
     */
    val totalVacanciesAnalyzed: Int,
)
