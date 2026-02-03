package com.hhassistant.service.exclusion

import com.hhassistant.domain.entity.ExclusionRule
import com.hhassistant.repository.ExclusionRuleRepository
import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing exclusion rules (keywords and phrases) for vacancy filtering
 */
@Service
class ExclusionRuleService(
    private val exclusionRuleRepository: ExclusionRuleRepository,
) {
    private val log = KotlinLogging.logger {}

    companion object {
        const val KEYWORDS_CACHE = "exclusionKeywords"
        const val PHRASES_CACHE = "exclusionPhrases"
    }

    /**
     * Gets all exclusion keywords (cached)
     * Cache is invalidated when keywords are added/removed
     */
    @Cacheable(value = [KEYWORDS_CACHE])
    fun getAllKeywords(): List<String> {
        log.trace("[ExclusionRuleService] Loading exclusion keywords from database (cache miss)")
        val keywords = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD)
            .map { it.text }
        log.debug("[ExclusionRuleService] Loaded ${keywords.size} exclusion keywords from database")
        return keywords
    }

    /**
     * Gets all exclusion phrases (cached)
     * Cache is invalidated when phrases are added/removed
     */
    @Cacheable(value = [PHRASES_CACHE])
    fun getAllPhrases(): List<String> {
        log.trace("[ExclusionRuleService] Loading exclusion phrases from database (cache miss)")
        val phrases = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.PHRASE)
            .map { it.text }
        log.debug("[ExclusionRuleService] Loaded ${phrases.size} exclusion phrases from database")
        return phrases
    }

    /**
     * Gets case sensitivity setting (defaults to false)
     * Uses cached keywords/phrases to avoid DB query if possible
     */
    @Cacheable(value = ["exclusionCaseSensitive"])
    fun isCaseSensitive(): Boolean {
        // Get from first rule or default to false
        // Try to get from cached data first to avoid DB query
        val allRules = exclusionRuleRepository.findAll()
        return allRules.firstOrNull()?.caseSensitive ?: false
    }

    /**
     * Adds a new exclusion keyword
     */
    @Transactional
    @CacheEvict(value = [KEYWORDS_CACHE, "exclusionCaseSensitive"], allEntries = true)
    fun addKeyword(keyword: String, caseSensitive: Boolean = false): ExclusionRule {
        val existing = exclusionRuleRepository.findByTextAndType(keyword, ExclusionRule.ExclusionRuleType.KEYWORD)
        if (existing != null) {
            log.warn("[ExclusionRuleService] Keyword '$keyword' already exists")
            return existing
        }

        val rule = ExclusionRule(
            text = keyword,
            type = ExclusionRule.ExclusionRuleType.KEYWORD,
            caseSensitive = caseSensitive,
        )
        val saved = exclusionRuleRepository.save(rule)
        log.info("[ExclusionRuleService] Added exclusion keyword: '$keyword'")
        return saved
    }

    /**
     * Adds a new exclusion phrase
     */
    @Transactional
    @CacheEvict(value = [PHRASES_CACHE, "exclusionCaseSensitive"], allEntries = true)
    fun addPhrase(phrase: String, caseSensitive: Boolean = false): ExclusionRule {
        val existing = exclusionRuleRepository.findByTextAndType(phrase, ExclusionRule.ExclusionRuleType.PHRASE)
        if (existing != null) {
            log.warn("[ExclusionRuleService] Phrase '$phrase' already exists")
            return existing
        }

        val rule = ExclusionRule(
            text = phrase,
            type = ExclusionRule.ExclusionRuleType.PHRASE,
            caseSensitive = caseSensitive,
        )
        val saved = exclusionRuleRepository.save(rule)
        log.info("[ExclusionRuleService] Added exclusion phrase: '$phrase'")
        return saved
    }

    /**
     * Removes an exclusion keyword
     */
    @Transactional
    @CacheEvict(value = [KEYWORDS_CACHE, "exclusionCaseSensitive"], allEntries = true)
    fun removeKeyword(keyword: String): Boolean {
        val rule = exclusionRuleRepository.findByTextAndType(keyword, ExclusionRule.ExclusionRuleType.KEYWORD)
        if (rule != null) {
            exclusionRuleRepository.delete(rule)
            log.info("[ExclusionRuleService] Removed exclusion keyword: '$keyword'")
            return true
        }
        log.warn("[ExclusionRuleService] Keyword '$keyword' not found")
        return false
    }

    /**
     * Removes an exclusion phrase
     */
    @Transactional
    @CacheEvict(value = [PHRASES_CACHE, "exclusionCaseSensitive"], allEntries = true)
    fun removePhrase(phrase: String): Boolean {
        val rule = exclusionRuleRepository.findByTextAndType(phrase, ExclusionRule.ExclusionRuleType.PHRASE)
        if (rule != null) {
            exclusionRuleRepository.delete(rule)
            log.info("[ExclusionRuleService] Removed exclusion phrase: '$phrase'")
            return true
        }
        log.warn("[ExclusionRuleService] Phrase '$phrase' not found")
        return false
    }

    /**
     * Lists all exclusion rules (uses cached data to avoid DB queries)
     */
    @Transactional(readOnly = true)
    fun listAll(): Map<String, List<String>> {
        // Use cached methods instead of direct DB access
        val keywords = getAllKeywords()
        val phrases = getAllPhrases()
        return mapOf(
            "keywords" to keywords,
            "phrases" to phrases,
        )
    }
}
