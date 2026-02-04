package com.hhassistant.service.exclusion

import com.hhassistant.domain.entity.ExclusionRule
import com.hhassistant.repository.ExclusionRuleRepository
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.ahocorasick.trie.Trie
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

/**
 * Сервис для управления словами-блокерами (только KEYWORD, не фразы)
 * Загружает слова из БД при старте и хранит в памяти для быстрой проверки
 * Использует алгоритм Aho-Corasick для эффективного множественного поиска подстрок
 */
@Service
class ExclusionKeywordService(
    private val exclusionRuleRepository: ExclusionRuleRepository,
) {
    private val log = KotlinLogging.logger {}

    // In-memory Set для быстрой проверки слов-блокеров
    private val exclusionKeywords: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Aho-Corasick Trie для быстрого поиска всех ключевых слов за один проход
    @Volatile
    private var keywordTrie: Trie? = null

    companion object {
        const val KEYWORDS_CACHE = "exclusionKeywords"
    }

    /**
     * Загружает слова-блокеры из БД при старте приложения
     */
    @PostConstruct
    fun loadKeywordsOnStartup() {
        log.info("🔄 [ExclusionKeywordService] Loading exclusion keywords from database on startup...")
        reloadKeywords()
        log.info("✅ [ExclusionKeywordService] Loaded ${exclusionKeywords.size} exclusion keywords")
    }

    /**
     * Перезагружает слова-блокеры из БД и перестраивает Aho-Corasick Trie
     */
    @CacheEvict(value = [KEYWORDS_CACHE], allEntries = true)
    fun reloadKeywords() {
        val keywords = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD)
            .map { it.text.lowercase() } // Нормализуем к нижнему регистру для сравнения
        exclusionKeywords.clear()
        exclusionKeywords.addAll(keywords)

        // Перестраиваем Aho-Corasick Trie для быстрого поиска
        rebuildTrie(keywords)

        log.debug(
            "[ExclusionKeywordService] Reloaded ${exclusionKeywords.size} exclusion keywords and rebuilt Aho-Corasick trie",
        )
    }

    /**
     * Перестраивает Aho-Corasick Trie из списка ключевых слов
     */
    private fun rebuildTrie(keywords: List<String>) {
        if (keywords.isEmpty()) {
            keywordTrie = null
            return
        }

        val trieBuilder = Trie.builder()
        keywords.forEach { keyword ->
            trieBuilder.addKeyword(keyword)
        }
        keywordTrie = trieBuilder.build()
    }

    /**
     * Получает все слова-блокеры (только для чтения)
     */
    fun getAllKeywords(): Set<String> = exclusionKeywords.toSet()

    /**
     * Проверяет, содержит ли текст запрещенные слова
     * Использует алгоритм Aho-Corasick для эффективного множественного поиска
     *
     * Преимущества Aho-Corasick:
     * - Поиск всех ключевых слов за один проход по тексту (O(n + m + z))
     * - Автомат строится один раз при инициализации
     * - Эффективен даже при большом количестве ключевых слов
     *
     * @param text Текст для проверки (название вакансии)
     * @return true если содержит запрещенное слово как отдельное слово (с границами слов)
     */
    fun containsExclusionKeyword(text: String): Boolean {
        val trie = keywordTrie ?: return false

        val normalizedText = text.lowercase()

        // Aho-Corasick находит все вхождения ключевых слов за один проход по тексту
        // Возвращает коллекцию Emit объектов с информацией о найденных совпадениях
        val matches = trie.parseText(normalizedText)

        // Проверяем, что найденные слова являются отдельными словами (имеют границы слов)
        // Граница слова = символ до и после не является буквой или цифрой
        return matches.any { match ->
            val start = match.start
            val end = match.end
            val before = if (start > 0) normalizedText[start - 1] else ' '
            val after = if (end < normalizedText.length) normalizedText[end] else ' '

            // Слово считается отдельным, если до и после него не буквы/цифры
            !before.isLetterOrDigit() && !after.isLetterOrDigit()
        }
    }

    /**
     * Результат добавления ключевых слов
     */
    data class AddKeywordsResult(
        val added: Int, // Количество добавленных слов
        val skipped: Int, // Количество пропущенных слов (уже существуют)
        val total: Int, // Общее количество слов в базе
    )

    /**
     * Добавляет одно или несколько слов-блокеров
     * Если в строке есть пробелы, парсит её и добавляет каждое слово отдельно
     * Слова, которые уже есть в базе, пропускаются
     * @param keywords Строка с одним или несколькими словами через пробел
     * @param caseSensitive Учитывать ли регистр (по умолчанию false)
     * @return Результат добавления с количеством добавленных и пропущенных слов
     */
    @Transactional
    @CacheEvict(value = [KEYWORDS_CACHE], allEntries = true)
    fun addKeyword(keywords: String, caseSensitive: Boolean = false): AddKeywordsResult {
        val trimmed = keywords.trim()
        if (trimmed.isEmpty()) {
            log.warn("[ExclusionKeywordService] Attempted to add empty keywords")
            return AddKeywordsResult(added = 0, skipped = 0, total = exclusionKeywords.size)
        }

        // Парсим строку по пробелам (поддерживаем множественные пробелы)
        val words = trimmed.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct() // Убираем дубликаты в одной строке

        if (words.isEmpty()) {
            log.warn("[ExclusionKeywordService] No valid keywords found after parsing")
            return AddKeywordsResult(added = 0, skipped = 0, total = exclusionKeywords.size)
        }

        var addedCount = 0
        var skippedCount = 0
        val addedKeywords = mutableListOf<String>()

        // Добавляем каждое слово отдельно
        for (word in words) {
            val normalizedKeyword = word.lowercase()

            // Проверяем, существует ли уже в БД (поиск без учета регистра)
            val existing = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD)
                .firstOrNull { it.text.lowercase() == normalizedKeyword }

            if (existing != null) {
                log.debug("[ExclusionKeywordService] Keyword '$word' already exists in database, skipping")
                skippedCount++
                // Обновляем Set на всякий случай
                exclusionKeywords.add(normalizedKeyword)
                continue
            }

            // Сохраняем в БД (сохраняем оригинальное написание слова)
            val rule = ExclusionRule(
                text = word,
                type = ExclusionRule.ExclusionRuleType.KEYWORD,
                caseSensitive = caseSensitive,
            )
            exclusionRuleRepository.save(rule)

            // Обновляем in-memory Set
            exclusionKeywords.add(normalizedKeyword)
            addedKeywords.add(word)
            addedCount++
        }

        // Перестраиваем Trie со всеми ключевыми словами
        rebuildTrie(exclusionKeywords.toList())

        log.info(
            "[ExclusionKeywordService] Added $addedCount exclusion keyword(s): ${addedKeywords.joinToString(", ")} " +
                "(skipped $skippedCount, total: ${exclusionKeywords.size})",
        )

        return AddKeywordsResult(added = addedCount, skipped = skippedCount, total = exclusionKeywords.size)
    }

    /**
     * Удаляет слово-блокер
     * @param keyword Слово для удаления
     * @return true если слово удалено, false если не найдено
     */
    @Transactional
    @CacheEvict(value = [KEYWORDS_CACHE], allEntries = true)
    fun removeKeyword(keyword: String): Boolean {
        val normalizedKeyword = keyword.trim().lowercase()

        // Ищем в БД (поиск без учета регистра)
        val rule = exclusionRuleRepository.findByType(ExclusionRule.ExclusionRuleType.KEYWORD)
            .firstOrNull { it.text.lowercase() == normalizedKeyword }

        if (rule == null) {
            log.debug("[ExclusionKeywordService] Keyword '$keyword' not found in database")
            // Удаляем из Set на всякий случай
            exclusionKeywords.remove(normalizedKeyword)
            return false
        }

        // Удаляем из БД
        exclusionRuleRepository.delete(rule)

        // Удаляем из in-memory Set
        exclusionKeywords.remove(normalizedKeyword)

        // Перестраиваем Trie без удаленного ключевого слова
        rebuildTrie(exclusionKeywords.toList())

        log.info("[ExclusionKeywordService] Removed exclusion keyword: '$keyword' (total: ${exclusionKeywords.size})")
        return true
    }

    /**
     * Получает количество слов-блокеров
     */
    fun getKeywordsCount(): Int = exclusionKeywords.size
}
