package com.hhassistant.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.client.hh.dto.KeySkillDto
import com.hhassistant.client.ollama.OllamaClient
import com.hhassistant.client.ollama.dto.ChatMessage
import com.hhassistant.config.PromptConfig
import com.hhassistant.domain.entity.Skill
import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancySkill
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.SkillRepository
import com.hhassistant.repository.VacancyRepository
import com.hhassistant.repository.VacancySkillRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для извлечения навыков из вакансий.
 *
 * Стратегия извлечения:
 * 1. Приоритет: использовать key_skills из API HH.ru (если есть)
 * 2. Fallback: извлечение через LLM из описания (если key_skills недостаточно)
 * 3. Нормализация и сохранение навыков в БД
 */
@Service
class SkillExtractionService(
    private val skillRepository: SkillRepository,
    private val vacancySkillRepository: VacancySkillRepository,
    private val vacancyRepository: VacancyRepository,
    private val ollamaClient: OllamaClient,
    private val promptConfig: PromptConfig,
    private val objectMapper: ObjectMapper,
    private val ollamaCircuitBreaker: CircuitBreaker,
    private val ollamaRetry: Retry,
    private val hhVacancyClient: HHVacancyClient,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Минимальное количество навыков для использования key_skills без дополнения через LLM
     */
    private val minSkillsFromApi = 3

    /**
     * Извлекает навыки из вакансии и сохраняет их в БД.
     *
     * @param vacancy Вакансия для извлечения навыков
     * @param keySkillsFromApi Навыки из API HH.ru (если есть)
     * @return Список извлеченных и сохраненных навыков
     */
    @Transactional
    suspend fun extractAndSaveSkills(
        vacancy: Vacancy,
        keySkillsFromApi: List<KeySkillDto>? = null,
    ): List<Skill> {
        log.info("🔍 [SkillExtraction] Extracting skills for vacancy: ${vacancy.id} - '${vacancy.name}'")

        val extractedSkills = mutableListOf<String>()

        // Шаг 1: Использовать key_skills из API (если есть)
        if (!keySkillsFromApi.isNullOrEmpty()) {
            val apiSkills = keySkillsFromApi.map { it.name.trim() }.filter { it.isNotBlank() }
            extractedSkills.addAll(apiSkills)
            log.info("📋 [SkillExtraction] Found ${apiSkills.size} skills from API key_skills: ${apiSkills.take(5)}...")
        }

        // Шаг 2: Если навыков недостаточно - дополнить через LLM из описания
        if (extractedSkills.size < minSkillsFromApi && !vacancy.description.isNullOrBlank()) {
            log.info("🤖 [SkillExtraction] Only ${extractedSkills.size} skills from API, extracting additional skills from description via LLM...")
            val llmSkills = extractSkillsFromDescription(vacancy)
            extractedSkills.addAll(llmSkills)
            log.info("✅ [SkillExtraction] Extracted ${llmSkills.size} additional skills from description via LLM")
        }

        if (extractedSkills.isEmpty()) {
            log.warn("⚠️ [SkillExtraction] No skills extracted for vacancy ${vacancy.id}")
            return emptyList()
        }

        // Шаг 3: Нормализация навыков
        val normalizedSkills = extractedSkills
            .distinct()
            .map { normalizeSkillName(it) }
            .filter { it.isNotBlank() }
            .distinct()

        log.info("📊 [SkillExtraction] Normalized ${normalizedSkills.size} unique skills: ${normalizedSkills.take(10)}...")

        // Шаг 4: Сохранение навыков в БД
        val savedSkills = normalizedSkills.map { skillName ->
            saveOrUpdateSkill(skillName)
        }

        // Шаг 5: Создание связей VacancySkill
        savedSkills.forEach { skill ->
            val skillId = skill.id ?: return@forEach
            if (!vacancySkillRepository.existsByVacancyIdAndSkillId(vacancy.id, skillId)) {
                val vacancySkill = VacancySkill(
                    vacancyId = vacancy.id,
                    skillId = skillId,
                    extractedAt = java.time.LocalDateTime.now(),
                )
                vacancySkillRepository.save(vacancySkill)
                log.debug("💾 [SkillExtraction] Created VacancySkill link: vacancy=${vacancy.id}, skill=$skillId (${skill.name})")
            }
        }

        log.info("✅ [SkillExtraction] Successfully extracted and saved ${savedSkills.size} skills for vacancy ${vacancy.id}")
        return savedSkills
    }

    /**
     * Извлекает навыки из описания вакансии через LLM.
     */
    private suspend fun extractSkillsFromDescription(vacancy: Vacancy): List<String> {
        return try {
            val prompt = buildSkillExtractionPrompt(vacancy)

            val response = ollamaRetry.executeSuspendFunction {
                ollamaCircuitBreaker.executeSuspendFunction {
                    ollamaClient.chat(
                        listOf(
                            ChatMessage(
                                role = "system",
                                content = promptConfig.skillExtractionSystem,
                            ),
                            ChatMessage(
                                role = "user",
                                content = prompt,
                            ),
                        ),
                    )
                }
            }

            parseSkillsFromLLMResponse(response)
        } catch (e: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.error("❌ [SkillExtraction] Circuit Breaker is OPEN for vacancy ${vacancy.id}: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            log.error("❌ [SkillExtraction] Failed to extract skills from description for vacancy ${vacancy.id}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Строит промпт для извлечения навыков из описания.
     */
    private fun buildSkillExtractionPrompt(vacancy: Vacancy): String {
        return promptConfig.skillExtractionTemplate
            .replace("{vacancyName}", vacancy.name)
            .replace("{description}", vacancy.description ?: "")
            .replace("{employer}", vacancy.employer)
    }

    /**
     * Парсит ответ LLM и извлекает список навыков.
     * Ожидаемый формат: JSON с полем "skills" (массив строк)
     */
    private fun parseSkillsFromLLMResponse(response: String): List<String> {
        return try {
            // Пытаемся извлечь JSON из ответа (может быть обернут в markdown)
            val jsonText = extractJsonFromResponse(response)

            // Парсим JSON
            val jsonNode = objectMapper.readTree(jsonText)
            val skillsArray = jsonNode.get("skills") ?: return emptyList()

            if (skillsArray.isArray) {
                skillsArray.map { it.asText().trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            log.warn("⚠️ [SkillExtraction] Failed to parse LLM response: ${e.message}. Response: ${response.take(200)}")
            emptyList()
        }
    }

    /**
     * Извлекает JSON из ответа LLM (может быть обернут в markdown блоки).
     */
    private fun extractJsonFromResponse(response: String): String {
        var text = response.trim()

        // Удаляем markdown блоки кода
        text = text.replace(Regex("```json\\s*"), "")
        text = text.replace(Regex("```\\s*"), "")
        text = text.trim()

        // Ищем JSON объект в тексте
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')

        return if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text.substring(jsonStart, jsonEnd + 1)
        } else {
            text
        }
    }

    /**
     * Нормализует название навыка (приведение к единому виду).
     */
    private fun normalizeSkillName(skill: String): String {
        return skill
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ") // Множественные пробелы -> один
            .trim()
    }

    /**
     * Сохраняет или обновляет навык в БД.
     * Если навык уже существует (по normalizedName) - увеличивает счетчик встречаемости.
     */
    private fun saveOrUpdateSkill(skillName: String): Skill {
        val normalizedName = normalizeSkillName(skillName)

        val existingSkill = skillRepository.findByNormalizedName(normalizedName).orElse(null)

        return if (existingSkill != null) {
            // Навык уже существует - увеличиваем счетчик
            val updated = existingSkill.incrementOccurrence()
            skillRepository.save(updated)
        } else {
            // Новый навык - создаем
            val newSkill = Skill(
                name = skillName,
                normalizedName = normalizedName,
                occurrenceCount = 1,
                lastSeenAt = java.time.LocalDateTime.now(),
            )
            skillRepository.save(newSkill)
        }
    }

    /**
     * Проверяет, есть ли навыки для вакансии.
     */
    fun hasSkillsForVacancy(vacancyId: String): Boolean {
        return vacancySkillRepository.findByVacancyId(vacancyId).isNotEmpty()
    }

    /**
     * Получает список вакансий, для которых еще не извлечены навыки.
     */
    fun getVacanciesWithoutSkills(allVacancies: List<Vacancy>): List<Vacancy> {
        return allVacancies.filter { vacancy ->
            !hasSkillsForVacancy(vacancy.id)
        }
    }

    /**
     * Извлекает навыки из всех вакансий, для которых они еще не извлечены.
     *
     * @param vacancies Список вакансий для обработки
     * @return Количество обработанных вакансий
     */
    suspend fun extractSkillsForAllVacancies(vacancies: List<Vacancy>): Int {
        log.info("🔍 [SkillExtraction] Starting skill extraction for ${vacancies.size} vacancies")

        var processedCount = 0
        var errorCount = 0

        for (vacancy in vacancies) {
            try {
                // Пропускаем, если навыки уже есть
                if (hasSkillsForVacancy(vacancy.id)) {
                    log.debug("⏭️ [SkillExtraction] Vacancy ${vacancy.id} already has skills, skipping")
                    continue
                }

                log.info("📋 [SkillExtraction] Processing vacancy ${vacancy.id}: '${vacancy.name}'")

                // Получаем key_skills из API (если доступны)
                val keySkills = try {
                    val vacancyDto = hhVacancyClient.getVacancyDetails(vacancy.id)
                    vacancyDto.keySkills
                } catch (e: HHAPIException.NotFoundException) {
                    // Вакансия не найдена на HH.ru - удаляем из БД
                    log.warn("🗑️ [SkillExtraction] Vacancy ${vacancy.id} not found on HH.ru (404), deleting from database")
                    deleteVacancyAndSkills(vacancy.id)
                    errorCount++
                    continue
                } catch (e: HHAPIException.RateLimitException) {
                    log.warn("⏸️ [SkillExtraction] Rate limit exceeded while checking vacancy ${vacancy.id}, skipping")
                    errorCount++
                    continue
                } catch (e: Exception) {
                    log.debug("⚠️ [SkillExtraction] Could not fetch key_skills from API for vacancy ${vacancy.id}: ${e.message}")
                    null
                }

                // Извлекаем и сохраняем навыки
                extractAndSaveSkills(vacancy, keySkills)
                processedCount++

                log.info("✅ [SkillExtraction] Successfully extracted skills for vacancy ${vacancy.id} ($processedCount/${vacancies.size})")
            } catch (e: Exception) {
                errorCount++
                log.error("❌ [SkillExtraction] Failed to extract skills for vacancy ${vacancy.id}: ${e.message}", e)
            }
        }

        log.info("✅ [SkillExtraction] Completed: processed $processedCount, errors $errorCount out of ${vacancies.size} vacancies")
        return processedCount
    }

    /**
     * Удаляет вакансию и связанные навыки.
     * Примечание: анализы вакансий удаляются через VacancyCleanupService для единообразия.
     */
    @Transactional
    private fun deleteVacancyAndSkills(vacancyId: String) {
        try {
            // Удаляем связи вакансия-навык
            vacancySkillRepository.deleteByVacancyId(vacancyId)

            // Удаляем саму вакансию
            vacancyRepository.deleteById(vacancyId)

            log.info("✅ [SkillExtraction] Deleted vacancy $vacancyId and related skills")
        } catch (e: Exception) {
            log.error("❌ [SkillExtraction] Failed to delete vacancy $vacancyId: ${e.message}", e)
        }
    }
}
