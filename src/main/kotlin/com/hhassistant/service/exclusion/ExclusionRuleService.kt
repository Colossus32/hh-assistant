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
     */
    @Cacheable(value = [KEYWORDS_CACHE])
    fun getAllKeywords(): List<String> {
        log.debug("[ExclusionRuleService] Loading exclusion keywords from database")
        return exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD)
            .map { it.text }
    }

    /**
     * Gets all exclusion phrases (cached)
     */
    @Cacheable(value = [PHRASES_CACHE])
    fun getAllPhrases(): List<String> {
        log.debug("[ExclusionRuleService] Loading exclusion phrases from database")
        return exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.PHRASE)
            .map { it.text }
    }

    /**
     * Gets case sensitivity setting (defaults to false)
     */
    fun isCaseSensitive(): Boolean {
        // Get from first rule or default to false
        return exclusionRuleRepository.findAll().firstOrNull()?.caseSensitive ?: false
    }

    /**
     * Adds a new exclusion keyword
     */
    @Transactional
    @CacheEvict(value = [KEYWORDS_CACHE], allEntries = true)
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
    @CacheEvict(value = [PHRASES_CACHE], allEntries = true)
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
    @CacheEvict(value = [KEYWORDS_CACHE], allEntries = true)
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
    @CacheEvict(value = [PHRASES_CACHE], allEntries = true)
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
     * Lists all exclusion rules
     */
    @Transactional(readOnly = true)
    fun listAll(): Map<String, List<String>> {
        val keywords = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD).map { it.text }
        val phrases = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.PHRASE).map { it.text }
        return mapOf(
            "keywords" to keywords,
            "phrases" to phrases,
        )
    }
}
