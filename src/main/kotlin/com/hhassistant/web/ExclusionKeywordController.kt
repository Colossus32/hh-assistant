package com.hhassistant.web

import com.hhassistant.service.exclusion.ExclusionKeywordService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * REST API для управления бан-словами валидатора вакансий.
 */
@RestController
@RequestMapping("/api/exclusion")
class ExclusionKeywordController(
    private val exclusionKeywordService: ExclusionKeywordService,
) {

    /**
     * GET /api/exclusion/keywords — список всех бан-слов
     */
    @GetMapping("/keywords")
    fun getKeywords(): ResponseEntity<Map<String, Any>> {
        val keywords = exclusionKeywordService.getAllKeywords().toList().sorted()
        return ResponseEntity.ok(
            mapOf(
                "keywords" to keywords,
                "count" to keywords.size,
            ),
        )
    }

    /**
     * POST /api/exclusion/keywords — добавить одно или несколько слов (через пробел)
     */
    @PostMapping("/keywords")
    fun addKeywords(@RequestBody body: AddKeywordsRequest): ResponseEntity<Map<String, Any>> {
        val result = exclusionKeywordService.addKeyword(
            keywords = body.keywords ?: "",
            caseSensitive = body.caseSensitive ?: false,
        )
        return ResponseEntity.ok(
            mapOf(
                "added" to result.added,
                "skipped" to result.skipped,
                "total" to result.total,
            ),
        )
    }

    /**
     * DELETE /api/exclusion/keywords?keyword=... — удалить слово
     * Query param используется для корректной передачи кириллицы и пробелов (URL-encoded).
     */
    @DeleteMapping("/keywords")
    fun removeKeyword(@RequestParam keyword: String): ResponseEntity<Map<String, Any>> {
        val removed = exclusionKeywordService.removeKeyword(keyword)
        return if (removed) {
            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "Keyword removed",
                ),
            )
        } else {
            ResponseEntity.notFound().build()
        }
    }

    data class AddKeywordsRequest(
        val keywords: String? = null,
        val caseSensitive: Boolean? = null,
    )
}
