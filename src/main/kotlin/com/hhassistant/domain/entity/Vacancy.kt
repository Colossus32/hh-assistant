package com.hhassistant.domain.entity

import com.hhassistant.domain.model.VacancySource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(name = "vacancies")
data class Vacancy(
    @Id
    @Column(name = "id", length = 50)
    val id: String,

    @Column(name = "name", length = 500, nullable = false)
    val name: String,

    @Column(name = "employer", length = 255, nullable = false)
    val employer: String,

    @Column(name = "salary", length = 100)
    val salary: String?,

    @Column(name = "area", length = 255)
    val area: String,

    @Column(name = "url", length = 1000, nullable = false)
    val url: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "experience", length = 100)
    val experience: String?,

    @Column(name = "published_at")
    val publishedAt: LocalDateTime?,

    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "sent_to_telegram_at")
    val sentToTelegramAt: LocalDateTime? = null,

    @Column(name = "skills_extracted_at")
    val skillsExtractedAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    val status: VacancyStatus = VacancyStatus.NEW,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    val source: VacancySource = VacancySource.HH_RU,

    /**
     * Для Telegram вакансий: ID сообщения для отслеживания дубликатов
     */
    @Column(name = "message_id", length = 100)
    val messageId: String? = null,  // null для HH.ru

    /**
     * Для Telegram вакансий: канал-источник
     */
    @Column(name = "channel_username", length = 255)
    val channelUsername: String? = null, // null для HH.ru

    /**
     * Версия записи для optimistic locking.
     * Автоматически увеличивается при каждом обновлении записи.
     * Предотвращает lost updates при параллельном обновлении.
     */
    @Version
    @Column(name = "version", nullable = false)
    val version: Long = 0,
) {
    /**
     * Rich Domain Model: бизнес-логика внутри entity
     * Проверяет, является ли вакансия новой (еще не обработанной)
     */
    fun isNew(): Boolean = status == VacancyStatus.NEW

    /**
     * Проверяет, была ли вакансия отправлена пользователю
     */
    fun isSentToUser(): Boolean = status == VacancyStatus.SENT_TO_USER && sentToTelegramAt != null

    /**
     * Проверяет, является ли вакансия из Telegram канала
     */
    fun isFromTelegram(): Boolean = source == VacancySource.TELEGRAM_CHANNEL

    /**
     * Проверяет, была ли вакансия пропущена (не релевантна)
     */
    fun isSkipped(): Boolean = status == VacancyStatus.SKIPPED

    /**
     * Проверяет, была ли вакансия отмечена как неинтересная
     */
    fun isNotInterested(): Boolean = status == VacancyStatus.NOT_INTERESTED

    /**
     * Проверяет, была ли вакансия помечена как неподходящая (не релевантна по результатам LLM анализа)
     */
    fun isNotSuitable(): Boolean = status == VacancyStatus.NOT_SUITABLE

    /**
     * Проверяет, находится ли вакансия в архиве (недоступна на HH.ru)
     */
    fun isInArchive(): Boolean = status == VacancyStatus.IN_ARCHIVE

    /**
     * Проверяет, можно ли обрабатывать вакансию (не помечена как неинтересная, неподходящая, отклоненная или в архиве)
     */
    fun canBeProcessed(): Boolean = status != VacancyStatus.NOT_INTERESTED &&
        status != VacancyStatus.NOT_SUITABLE &&
        status != VacancyStatus.IN_ARCHIVE &&
        status != VacancyStatus.REJECTED_BY_VALIDATOR

    /**
     * Проверяет, находится ли вакансия в очереди на обработку
     */
    fun isQueued(): Boolean = status == VacancyStatus.QUEUED

    /**
     * Создает копию вакансии с новым статусом (immutability)
     */
    fun withStatus(newStatus: VacancyStatus): Vacancy = copy(status = newStatus)

    /**
     * Создает копию вакансии с отметкой времени отправки в Telegram
     */
    fun withSentToTelegramAt(sentAt: LocalDateTime): Vacancy = copy(
        status = VacancyStatus.SENT_TO_USER,
        sentToTelegramAt = sentAt,
    )

    /**
     * Проверяет, были ли извлечены навыки для вакансии
     */
    fun hasSkillsExtracted(): Boolean = skillsExtractedAt != null

    /**
     * Создает копию вакансии с отметкой времени извлечения навыков
     */
    fun withSkillsExtractedAt(extractedAt: LocalDateTime): Vacancy = copy(
        skillsExtractedAt = extractedAt,
    )
}

/**
 * Статусы вакансии с допустимыми переходами (state machine).
 *
 * Диаграмма переходов:
 * ```
 * NEW ──────────┬──► QUEUED ──► ANALYZED ──► SENT_TO_USER ──┬──► APPLIED
 *               │            │              │               └──► NOT_INTERESTED
 *               │            │              │
 *               │            ├──► NOT_SUITABLE (final)
 *               │            ├──► SKIPPED ◄──┐
 *               │            ├──► IN_ARCHIVE (final)  │
 *               │            └──► REJECTED_BY_VALIDATOR (final)   │
 *               │                                              recovery
 *               └──► SKIPPED ───────────────────────────────────┘
 *                       │
 *                       └──► NEW (recovery), NOT_SUITABLE (recovery), IN_ARCHIVE (recovery)
 * ```
 */
enum class VacancyStatus {
    /** Новая вакансия, ожидает постановки в очередь */
    NEW,
    /** В очереди на обработку LLM */
    QUEUED,
    /** Проанализирована LLM, релевантна */
    ANALYZED,
    /** Отправлена пользователю в Telegram */
    SENT_TO_USER,
    /** Не удалось обработать (технические ошибки), можно восстановить */
    SKIPPED,
    /** Не подходит по результатам LLM (финальный) */
    NOT_SUITABLE,
    /** Недоступна на HH.ru (404, финальный) */
    IN_ARCHIVE,
    /** Откликнулся (финальный, действие пользователя) */
    APPLIED,
    /** Неинтересна (финальный, действие пользователя) */
    NOT_INTERESTED,
    /** Отклонена валидатором, бан-слова (финальный) */
    REJECTED_BY_VALIDATOR;

    companion object {
        /** Финальные статусы — из них нет переходов */
        val FINAL_STATUSES = setOf(
            NOT_SUITABLE,
            IN_ARCHIVE,
            APPLIED,
            NOT_INTERESTED,
            REJECTED_BY_VALIDATOR,
        )

        /** Допустимые переходы: from -> set of valid to */
        private val VALID_TRANSITIONS: Map<VacancyStatus, Set<VacancyStatus>> = mapOf(
            NEW to setOf(QUEUED, SKIPPED),
            QUEUED to setOf(
                ANALYZED,
                NOT_SUITABLE,
                SENT_TO_USER,
                SKIPPED,
                IN_ARCHIVE,
                REJECTED_BY_VALIDATOR,
            ),
            ANALYZED to setOf(SENT_TO_USER, NOT_SUITABLE), // NOT_SUITABLE — sync из кэша
            SENT_TO_USER to setOf(APPLIED, NOT_INTERESTED),
            SKIPPED to setOf(NEW, NOT_SUITABLE, IN_ARCHIVE), // recovery
            NOT_SUITABLE to emptySet(),
            IN_ARCHIVE to emptySet(),
            APPLIED to emptySet(),
            NOT_INTERESTED to emptySet(),
            REJECTED_BY_VALIDATOR to emptySet(),
        )

        /** Проверяет, допустим ли переход из from в to */
        fun canTransition(from: VacancyStatus, to: VacancyStatus): Boolean {
            if (from == to) return true
            return VALID_TRANSITIONS[from]?.contains(to) == true
        }

        /** Проверяет, является ли статус финальным */
        fun isFinal(status: VacancyStatus): Boolean = status in FINAL_STATUSES
    }
}
