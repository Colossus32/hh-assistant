# Флоу изменения статусов вакансий

Документ описывает, как меняется статус вакансии в процессе работы приложения.

## Статусы вакансий

```kotlin
enum class VacancyStatus {
    NEW,              // Новая вакансия
    QUEUED,           // В очереди на обработку (добавлена в очередь, но еще не обработана)
    ANALYZED,         // Проанализирована LLM
    SENT_TO_USER,     // Отправлена в Telegram
    SKIPPED,          // Не релевантна или не удалось обработать (можно восстановить)
    APPLIED,          // Откликнулся на вакансию
    NOT_INTERESTED,   // Неинтересная вакансия (не удалять, но не показывать повторно)
}
```

## Основной флоу обработки вакансии

### 1. Получение вакансий из HH.ru API

**Сервис:** `VacancyFetchService.fetchAndSaveNewVacancies()`

**Процесс:**
- Получение вакансий из HH.ru API по конфигурациям поиска
- Фильтрация по опыту (> 6 лет исключаются)
- Фильтрация по бан-словам (exclusion keywords)
- Проверка на дубликаты (по ID из кэша)

**Статус:** `QUEUED` (устанавливается при создании)

**Код:**
```kotlin
// VacancyFetchService.kt:249
.map { it.copy(status = VacancyStatus.QUEUED) }
```

**Действия:**
- Сохранение в БД со статусом `QUEUED`
- Добавление в очередь обработки через `VacancyProcessingQueueService.enqueueBatch()`

---

### 2. Добавление в очередь обработки

**Сервис:** `VacancyProcessingQueueService.enqueue()`

**Процесс:**
- Проверка на дубликаты (уже обрабатывается или в очереди)
- Проверка существующего анализа (если есть - обновление статуса)
- Если статус не `QUEUED`, обновление на `QUEUED`
- Добавление в приоритетную очередь (приоритет по дате публикации)

**Статус:** `QUEUED` (остается или устанавливается)

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:224-226
if (vacancy.status != VacancyStatus.QUEUED) {
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.QUEUED))
}
```

---

### 3. Обработка из очереди

**Сервис:** `VacancyProcessingQueueService.processQueueItem()`

**Процесс:**
- Проверка Circuit Breaker (если OPEN - статус → `SKIPPED`)
- Анализ вакансии через LLM (Ollama)
- Обновление статуса на основе результата анализа
- **Удаление из очереди** после обработки (всегда)

**Возможные переходы:**
- `QUEUED` → `ANALYZED` (если релевантна)
- `QUEUED` → `SKIPPED` (если не релевантна или ошибка)
- `QUEUED` → **удаление из БД** (если отклонена валидатором или невалидный URL)

**Удаление из очереди:**
После обработки вакансия всегда удаляется из очереди:
```kotlin
// VacancyProcessingQueueService.kt:475-481
processingVacancies.remove(item.vacancyId)
queuedVacancies.remove(item.vacancyId)
queue.remove(item)
metricsService.setQueueSize(queue.size)
```

**Удаление из базы данных:**
Вакансия удаляется из БД только в следующих случаях:
1. **Отклонена валидатором** (содержит бан-слова) - `analysis == null`
2. **Невалидный URL** (вакансия не найдена на HH.ru - 404)

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:497-510
val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

// Если анализ вернул null - вакансия была отклонена валидатором и удалена из БД
if (analysis == null) {
    log.info("Vacancy ${vacancy.id} was rejected by validator and deleted from database")
    return
}

// Обновляем статус вакансии
val newStatus = if (analysis.isRelevant) {
    VacancyStatus.ANALYZED
} else {
    VacancyStatus.SKIPPED  // Обычные нерелевантные вакансии остаются в БД со статусом SKIPPED
}
vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
```

**Важно:**
- Обычные нерелевантные вакансии (низкий relevance score) **НЕ удаляются** из базы данных
- Они остаются в БД со статусом `SKIPPED` и могут быть восстановлены через механизм retry
- Удаляются только вакансии с бан-словами или невалидным URL

---

### 4. Отправка в Telegram (для релевантных вакансий)

**Сервис:** `VacancyNotificationService.sendVacancyToTelegram()`

**Процесс:**
- Отправка уведомления в Telegram с информацией о вакансии
- Обновление статуса и времени отправки

**Статус:** `ANALYZED` → `SENT_TO_USER`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:526-529
val sentSuccessfully = vacancyNotificationService.sendVacancyToTelegram(vacancy, analysis)
if (sentSuccessfully) {
    val sentAt = LocalDateTime.now()
    vacancyStatusService.updateVacancyStatus(vacancy.withSentToTelegramAt(sentAt))
}
```

**Метод `withSentToTelegramAt()` автоматически устанавливает статус `SENT_TO_USER`:**
```kotlin
// Vacancy.kt:94-97
fun withSentToTelegramAt(sentAt: LocalDateTime): Vacancy = copy(
    status = VacancyStatus.SENT_TO_USER,
    sentToTelegramAt = sentAt,
)
```

---

### 5. Действия пользователя

**Контроллер:** `VacancyManagementController`

#### 5.1. Отметка как "Откликнулся"

**Endpoint:** `POST /api/vacancies/{id}/mark-applied`

**Статус:** Любой → `APPLIED`

**Код:**
```kotlin
// VacancyManagementController.kt:74-76
fun markAsApplied(@PathVariable id: String) {
    return updateVacancyStatus(id, VacancyStatus.APPLIED, "откликнулся")
}
```

#### 5.2. Отметка как "Неинтересная"

**Endpoint:** `POST /api/vacancies/{id}/mark-not-interested`

**Статус:** Любой → `NOT_INTERESTED`

**Код:**
```kotlin
// VacancyManagementController.kt:96-98
fun markAsNotInterested(@PathVariable id: String) {
    return updateVacancyStatus(id, VacancyStatus.NOT_INTERESTED, "неинтересная")
}
```

---

## Восстановление пропущенных вакансий

### Автоматическое восстановление SKIPPED вакансий

**Сервис:** `VacancySchedulerService.retrySkippedVacancies()`

**Расписание:** Каждые 5 минут (если Circuit Breaker закрыт)

**Процесс:**
- Поиск `SKIPPED` вакансий в окне 48 часов
- Проверка на бан-слова (если есть - удаление из БД)
- Сброс статуса на `NEW` для повторной обработки

**Статус:** `SKIPPED` → `NEW`

**Код:**
```kotlin
// VacancySchedulerService.kt:144-146
val oldStatus = vacancy.status
vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
log.info("[Scheduler] Reset vacancy ${vacancy.id} status from $oldStatus to NEW for retry")
```

---

## Обработка ошибок

### Circuit Breaker OPEN

**Когда:** При перегрузке LLM (Ollama)

**Действие:** Статус → `SKIPPED`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:390-451
if (circuitBreakerState == "OPEN") {
    // Ждем завершения активных запросов или помечаем как SKIPPED
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
}
```

### Ошибка анализа

**Когда:** Ошибка при анализе через LLM

**Действие:** Статус → `SKIPPED`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:560-599
catch (e: OllamaException) {
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
}
```

### Отклонение валидатором

**Когда:** Вакансия содержит бан-слова или не прошла валидацию

**Действие:** 
- Удаление из БД (возврат `null` из анализа)
- Удаление из очереди (автоматически при завершении обработки)

**Код:**
```kotlin
// VacancyAnalysisService.kt:137-158
val contentValidation = vacancyContentValidator.validate(vacancy)
if (!contentValidation.isValid) {
    // Удаляем вакансию из БД, так как она содержит бан-слова
    skillExtractionService.deleteVacancyAndSkills(vacancy.id)
    return null  // Возвращаем null, чтобы показать, что вакансия была удалена
}

// VacancyProcessingQueueService.kt:497-510
val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)
if (analysis == null) {
    log.info("Vacancy ${vacancy.id} was rejected by validator and deleted from database")
    return  // Вакансия удалена, обработка завершена, удаление из очереди произойдет автоматически
}
```

### Невалидный URL (404)

**Когда:** Вакансия не найдена на HH.ru (404)

**Действие:**
- Удаление из БД
- Удаление из очереди (автоматически при завершении обработки)

**Код:**
```kotlin
// VacancyAnalysisService.kt:161-193
try {
    hhVacancyClient.getVacancyDetails(vacancy.id)
} catch (e: HHAPIException.NotFoundException) {
    // Вакансия не найдена (404) - удаляем из БД
    skillExtractionService.deleteVacancyAndSkills(vacancy.id)
    return null
}
```

---

## Диаграмма флоу

```
┌─────────────────────────────────────────────────────────────────┐
│                    HH.ru API                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyFetchService.fetchAndSaveNewVacancies()                 │
│  - Получение вакансий                                           │
│  - Фильтрация (опыт, бан-слова)                                 │
│  - Статус: QUEUED                                               │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyProcessingQueueService.enqueue()                        │
│  - Добавление в очередь                                         │
│  - Статус: QUEUED (остается)                                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyProcessingQueueService.processQueueItem()               │
│  - Анализ через LLM (Ollama)                                    │
│  - Статус: ANALYZED (релевантна) или SKIPPED (не релевантна)   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │             │
                    ▼             ▼
        ┌──────────────────┐  ┌──────────────────┐
        │   ANALYZED       │  │   SKIPPED        │
        │   (релевантна)   │  │   (не релевантна)│
        └────────┬──────────┘  └────────┬─────────┘
                 │                      │
                 │                      │ (через 48ч)
                 │                      ▼
                 │              ┌──────────────────┐
                 │              │   NEW (retry)     │
                 │              └──────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyNotificationService.sendVacancyToTelegram()            │
│  - Отправка в Telegram                                          │
│  - Статус: SENT_TO_USER                                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │             │
                    ▼             ▼
        ┌──────────────────┐  ┌──────────────────┐
        │   APPLIED        │  │   NOT_INTERESTED │
        │   (откликнулся)  │  │   (неинтересная) │
        └──────────────────┘  └──────────────────┘
```

**Примечания к диаграмме:**
- После обработки в `processQueueItem()` вакансия **всегда удаляется из очереди** (из `queue`, `processingVacancies`, `queuedVacancies`)
- Вакансия **удаляется из базы данных** только если:
  - Отклонена валидатором (содержит бан-слова)
  - Невалидный URL (404 на HH.ru)
- Обычные нерелевантные вакансии (низкий relevance score) **остаются в БД** со статусом `SKIPPED` и могут быть восстановлены через retry

---

## Ключевые сервисы

### VacancyStatusService
Центральный сервис для обновления статусов вакансий.

**Методы:**
- `updateVacancyStatus(updatedVacancy: Vacancy)` - обновление статуса
- `updateVacancyStatusById(vacancyId: String, newStatus: VacancyStatus)` - обновление по ID

**Особенности:**
- Инвалидация кэша при обновлении
- Логирование изменений статуса

### VacancyProcessingQueueService
Очередь обработки вакансий с приоритетом.

**Особенности:**
- Приоритетная очередь (по дате публикации)
- Ограничение параллелизма (semaphore)
- Обработка Circuit Breaker
- Автоматическое восстановление при перезапуске

### VacancySchedulerService
Планировщик задач для обработки вакансий.

**Задачи:**
- `checkNewVacancies()` - получение новых вакансий (каждые 15 минут)
- `processQueuedVacancies()` - обработка QUEUED вакансий из БД (каждые 10 минут)
- `retrySkippedVacancies()` - восстановление SKIPPED вакансий (каждые 5 минут)

---

## Особые случаи

### При перезапуске приложения

1. Загрузка `QUEUED` вакансий из БД в очередь
2. Проверка существующих анализов и обновление статусов
3. Продолжение обработки с места остановки

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:110-141
@EventListener(ApplicationReadyEvent::class)
fun loadPendingVacanciesOnStartup() {
    val queuedVacancies = vacancyRepository.findByStatus(VacancyStatus.QUEUED)
    // Добавление в очередь и запуск обработки
}
```

### При остановке приложения

Если есть активные запросы к LLM:
- Вакансии в обработке помечаются как `SKIPPED`
- При следующем запуске они будут восстановлены через `retrySkippedVacancies()`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:724-791
@PreDestroy
fun shutdown() {
    if (activeRequests > 0) {
        markProcessingVacanciesAsSkipped()
    }
}
```

---

## Резюме переходов статусов

| Из статуса | В статус | Условие/Триггер |
|------------|----------|-----------------|
| - | `QUEUED` | Создание вакансии из HH.ru API |
| `NEW` | `QUEUED` | Добавление в очередь обработки |
| `QUEUED` | `ANALYZED` | Успешный анализ, вакансия релевантна |
| `QUEUED` | `SKIPPED` | Анализ показал нерелевантность или ошибка |
| `ANALYZED` | `SENT_TO_USER` | Успешная отправка в Telegram |
| `SENT_TO_USER` | `APPLIED` | Пользователь отметил как "откликнулся" |
| `SENT_TO_USER` | `NOT_INTERESTED` | Пользователь отметил как "неинтересная" |
| `SKIPPED` | `NEW` | Восстановление через retry (через 48 часов) |
| Любой | `SKIPPED` | Ошибка обработки или Circuit Breaker OPEN |

---

## Файлы для изучения

- `src/main/kotlin/com/hhassistant/domain/entity/Vacancy.kt` - Entity и enum статусов
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyStatusService.kt` - Сервис управления статусами
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyFetchService.kt` - Получение вакансий из API
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyProcessingQueueService.kt` - Очередь обработки
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancySchedulerService.kt` - Планировщик задач
- `src/main/kotlin/com/hhassistant/web/VacancyManagementController.kt` - API для управления статусами

