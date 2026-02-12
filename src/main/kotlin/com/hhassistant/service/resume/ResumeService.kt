package com.hhassistant.service.resume

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.client.hh.HHResumeClient
import com.hhassistant.domain.entity.Resume
import com.hhassistant.domain.entity.ResumeSource
import com.hhassistant.repository.ResumeRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class ResumeService(
    private val repository: ResumeRepository,
    private val pdfParser: PDFParserService,
    private val hhResumeClient: HHResumeClient,
    private val objectMapper: ObjectMapper,
    @Value("\${app.resume.path:./resumes/resume.pdf}") private val resumePath: String?,
    @Qualifier("resumeStructureCache") private val resumeStructureCache: Cache<String, com.hhassistant.domain.model.ResumeStructure>,
) {
    private val log = KotlinLogging.logger {}

    // Финальный путь к резюме (по умолчанию из конфигурации)
    private val finalResumePath: String = resumePath ?: "./resumes/resume.pdf"

    // Кэш резюме в памяти - загружается один раз при старте
    @Volatile
    private var cachedResume: Resume? = null

    /**
     * Загружает резюме из кэша или из источника (БД, HH.ru API, PDF)
     * Если резюме уже загружено в память, возвращает кэшированное
     */
    suspend fun loadResume(): Resume {
        // Используем кэшированное резюме, если оно уже загружено
        cachedResume?.let {
            log.debug("Using cached resume from memory: ${it.fileName}")
            return it
        }

        // Если кэша нет, загружаем резюме
        return loadResumeInternal()
    }

    /**
     * Внутренний метод для загрузки резюме из источника
     */
    private suspend fun loadResumeInternal(): Resume {
        // 1. Проверяем, есть ли активное резюме в БД
        val existingResume = repository.findFirstByIsActiveTrue()
        if (existingResume != null) {
            log.debug("Using existing resume from database: ${existingResume.fileName}")
            // Кэшируем резюме
            cachedResume = existingResume
            // Кэш структуры резюме управляется через Caffeine cache
            return existingResume
        }

        // 2. Пытаемся загрузить из HH.ru API
        try {
            val hhResume = loadFromHHAPI()
            if (hhResume != null) {
                log.info("Resume loaded from HH.ru API")
                // Кэшируем резюме
                cachedResume = hhResume
                // Кэш структуры резюме управляется через Caffeine cache
                return hhResume
            }
        } catch (e: Exception) {
            log.warn("Failed to load resume from HH.ru API: ${e.message}", e)
        }

        // 3. Fallback: загружаем из локального PDF
        try {
            val pdfResume = loadFromPDF()
            // Кэшируем резюме
            cachedResume = pdfResume
            // Кэш структуры резюме управляется через Caffeine cache
            return pdfResume
        } catch (e: Exception) {
            log.error("Failed to load resume from PDF: ${e.message}", e)
            // Если не удалось загрузить, создаем пустое резюме
            log.warn("Creating empty resume as fallback")
            val emptyResume = Resume(
                fileName = "empty_resume.txt",
                rawText = "Резюме не загружено. Пожалуйста, добавьте резюме в БД, загрузите PDF или настройте доступ к HH.ru API.",
                structuredData = null,
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            )
            cachedResume = emptyResume
            // Очищаем кэш структуры резюме
            resumeStructureCache.invalidateAll()
            return emptyResume
        }
    }

    /**
     * Предзагружает резюме в память при старте приложения
     * Вызывается из ApplicationReadyEvent или @PostConstruct
     */
    suspend fun preloadResume() {
        log.info("🔄 [ResumeService] Preloading resume into memory...")
        try {
            val resume = loadResumeInternal()
            log.info(
                "✅ [ResumeService] Resume preloaded successfully: ${resume.fileName} (${resume.rawText.length} chars)",
            )
            // Проверяем структуру резюме через кэш
            val structure = getResumeStructure(resume)
            if (structure != null) {
                log.info("✅ [ResumeService] Resume structure parsed: ${structure.skills.size} skills")
            }
        } catch (e: Exception) {
            log.error("❌ [ResumeService] Failed to preload resume: ${e.message}", e)
            // Не падаем с ошибкой, просто логируем
        }
    }

    private suspend fun loadFromHHAPI(): Resume? {
        return try {
            val resumes = hhResumeClient.getMyResumes()
            if (resumes.isEmpty()) {
                log.info("No resumes found in HH.ru API")
                return null
            }

            val hhResume = resumes.first()
            val resumeDetails = hhResumeClient.getResumeDetails(hhResume.id)

            // Конвертируем HH.ru ResumeDto в наш Resume entity
            val resumeText = buildResumeText(resumeDetails)
            val structuredData = pdfParser.extractStructuredData(resumeText)

            repository.save(
                Resume(
                    fileName = "hh_resume_${resumeDetails.id}.txt",
                    rawText = resumeText,
                    structuredData = objectMapper.writeValueAsString(structuredData),
                    source = ResumeSource.HH_API,
                    isActive = true,
                ),
            )
        } catch (e: Exception) {
            log.error("Error loading resume from HH.ru API", e)
            null
        }
    }

    private fun buildResumeText(resumeDto: com.hhassistant.client.hh.dto.ResumeDto): String {
        val sb = StringBuilder()

        sb.appendLine("${resumeDto.firstName ?: ""} ${resumeDto.lastName ?: ""}".trim())
        sb.appendLine(resumeDto.title)
        sb.appendLine()

        if (resumeDto.skills?.isNotEmpty() == true) {
            sb.appendLine("Навыки:")
            resumeDto.skills.forEach { skill ->
                sb.appendLine("- ${skill.name}")
            }
            sb.appendLine()
        }

        if (resumeDto.experience?.isNotEmpty() == true) {
            sb.appendLine("Опыт работы:")
            resumeDto.experience.forEach { exp ->
                sb.appendLine("${exp.position ?: ""} в ${exp.company ?: ""}")
                exp.description?.let { sb.appendLine(it) }
            }
            sb.appendLine()
        }

        if (resumeDto.education?.isNotEmpty() == true) {
            sb.appendLine("Образование:")
            resumeDto.education.forEach { edu ->
                sb.appendLine("${edu.name ?: ""} ${edu.year ?: ""}")
            }
        }

        return sb.toString()
    }

    private fun loadFromPDF(): Resume {
        val pdfFile = File(finalResumePath)
        require(pdfFile.exists()) {
            "Resume PDF file not found at: ${pdfFile.absolutePath}. " +
                "Please place your resume.pdf in the resumes/ directory."
        }

        log.info("Loading resume from PDF: ${pdfFile.absolutePath}")

        val rawText = pdfParser.extractText(pdfFile)
        val structuredData = pdfParser.extractStructuredData(rawText)

        return repository.save(
            Resume(
                fileName = pdfFile.name,
                rawText = rawText,
                structuredData = objectMapper.writeValueAsString(structuredData),
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            ),
        )
    }

    fun getResumeStructure(resume: Resume): com.hhassistant.domain.model.ResumeStructure? {
        // Используем Caffeine cache для кэширования структуры резюме
        val cacheKey = (resume.id ?: "default").toString()

        return resumeStructureCache.get(cacheKey) {
            // Парсим структуру из JSON, если её нет в кэше
            resume.structuredData?.let {
                try {
                    val structure = objectMapper.readValue(it, com.hhassistant.domain.model.ResumeStructure::class.java)
                    log.debug("[ResumeService] Cached resume structure for resume ${resume.id}")
                    structure
                } catch (e: Exception) {
                    log.warn("Failed to parse structured data for resume ${resume.id}", e)
                    null
                }
            }
        }
    }

    /**
     * Сохраняет резюме из PDF байтов (например, из Telegram)
     */
    suspend fun saveResumeFromBytes(
        pdfBytes: ByteArray,
        fileName: String,
    ): Resume {
        log.info("💾 [ResumeService] Saving resume from bytes: $fileName (${pdfBytes.size} bytes)")

        // Деактивируем все существующие резюме
        repository.findByIsActiveTrue().forEach { resume ->
            repository.save(resume.copy(isActive = false))
        }

        // Извлекаем текст из PDF
        val rawText = pdfParser.extractTextFromBytes(pdfBytes)
        val structuredData = pdfParser.extractStructuredData(rawText)

        // Сохраняем новое резюме
        val savedResume = repository.save(
            Resume(
                fileName = fileName,
                rawText = rawText,
                structuredData = objectMapper.writeValueAsString(structuredData),
                source = ResumeSource.MANUAL_UPLOAD,
                isActive = true,
            ),
        )

        // Обновляем кэш
        cachedResume = savedResume
        // Инвалидируем кэш структуры резюме, чтобы при следующем запросе загрузилась новая структура
        resumeStructureCache.invalidateAll()

        log.info(
            "✅ [ResumeService] Resume saved successfully: ${savedResume.fileName} (${rawText.length} chars, ${structuredData.skills.size} skills)",
        )
        return savedResume
    }

    /**
     * Проверяет, есть ли активное резюме в системе
     */
    fun hasActiveResume(): Boolean {
        val activeResume = repository.findFirstByIsActiveTrue()
        if (activeResume != null) {
            // Проверяем, что это не пустое резюме
            return activeResume.fileName != "empty_resume.txt" &&
                !activeResume.rawText.contains("Резюме не загружено")
        }
        return false
    }

    /**
     * Очищает кэш резюме (полезно для тестирования или перезагрузки)
     */
    fun clearCache() {
        log.info("🔄 [ResumeService] Clearing resume cache")
        cachedResume = null
        resumeStructureCache.invalidateAll()
    }
}
