# Анализ использования runBlocking в проекте

## 📋 Сводка

Найдено **множество использований `runBlocking`**. Ниже анализ каждого случая с рекомендациями.

---

## 1. VacancyProcessingQueueService

### 1.1. `loadPendingVacanciesOnStartup()` (строка 125)
**Контекст:** `@EventListener(ApplicationReadyEvent::class)` - вызывается при старте приложения

**Текущий код:**
```kotlin
@EventListener(ApplicationReadyEvent::class)
fun loadPendingVacanciesOnStartup() {
    runBlocking {
        val queuedVacancies = vacancyRepository.findByStatus(VacancyStatus.QUEUED)
        for (vacancy in queuedVacancies) {
            enqueue(vacancy.id, checkDuplicate = false)
        }
    }
}
```

**Проблема:**
- `enqueue()` - обычная функция (не suspend)
- Блокирует поток при старте приложения

**Рекомендация:** ✅ **МОЖНО УЛУЧШИТЬ**
- Использовать `queueScope.launch` для асинхронной загрузки
- Не блокировать старт приложения

---

### 1.2. `enqueue()` - два места (строки 191, 266)
**Контекст:** Внутри обычной функции `enqueue()`, вызывается `findByVacancyId()` (suspend)

**Текущий код:**
```kotlin
fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
    // ...
    if (processedVacancyCacheService.isProcessed(vacancyId)) {
        val existingAnalysis = runBlocking {
            vacancyAnalysisService.findByVacancyId(vacancyId) // suspend функция
        }
        // ...
    }
}
```

**Проблема:**
- `enqueue()` - обычная функция, но вызывает suspend функцию
- Блокирует поток при каждом вызове

**Рекомендация:** ⚠️ **СЛОЖНО УЛУЧШИТЬ**
- `enqueue()` вызывается из разных мест (включая синхронные)
- Можно сделать `enqueue()` suspend функцией, но потребуются изменения во всех местах вызова
- **Альтернатива:** Использовать `queueScope.launch` для асинхронного получения анализа

---

### 1.3. `markProcessingVacanciesAsSkipped()` (строка 815)
**Контекст:** `@PreDestroy` - вызывается при закрытии приложения

**Текущий код:**
```kotlin
@PreDestroy
fun shutdown() {
    // ...
    markProcessingVacanciesAsSkipped()
}

private fun markProcessingVacanciesAsSkipped() {
    runBlocking {
        // Помечает вакансии как SKIPPED
    }
}
```

**Проблема:**
- Блокирует завершение приложения
- Но это может быть приемлемо для cleanup операций

**Рекомендация:** ⚠️ **ОСТАВИТЬ КАК ЕСТЬ**
- При закрытии приложения допустимо блокировать поток
- Важно завершить все операции перед shutdown

---

## 2. ProcessedVacancyCacheService

### 2.1. `loadCacheOnStartup()` (строка 157)
**Контекст:** `@EventListener(ApplicationReadyEvent::class)` - при старте

**Текущий код:**
```kotlin
@EventListener(ApplicationReadyEvent::class)
fun loadCacheOnStartup() {
    runBlocking {
        loadCacheFromDatabase() // suspend функция
    }
}
```

**Проблема:**
- Блокирует старт приложения
- `loadCacheFromDatabase()` - suspend функция

**Рекомендация:** ✅ **МОЖНО УЛУЧШИТЬ**
- Использовать `CoroutineScope.launch` для асинхронной загрузки
- Кэш может загружаться в фоне, не блокируя старт

---

### 2.2. `invalidateAndRebuildCache()` (строка 188)
**Контекст:** `@Scheduled(cron = "0 0 0 * * *")` - по расписанию в полночь

**Текущий код:**
```kotlin
@Scheduled(cron = "0 0 0 * * *")
fun invalidateAndRebuildCache() {
    runBlocking {
        loadCacheFromDatabase() // suspend функция
    }
}
```

**Проблема:**
- Блокирует поток планировщика Spring
- Выполняется в полночь, когда нагрузка низкая

**Рекомендация:** ✅ **МОЖНО УЛУЧШИТЬ**
- Использовать `CoroutineScope.launch` для асинхронной пересборки
- Не блокировать планировщик

---

## 3. HealthCheckService

### 3.1. `performHealthCheck()` (строка 60)
**Контекст:** `@Scheduled` - периодическая проверка здоровья

**Текущий код:**
```kotlin
@Scheduled(cron = "...")
fun performHealthCheck() {
    runBlocking {
        val ollamaHealth = ollamaHealthIndicator.health() // обычная функция
        val hhapiHealth = hhapiHealthIndicator.health() // обычная функция
        telegramClient.sendMessage(message) // suspend функция
    }
}
```

**Проблема:**
- `health()` - обычные функции (не suspend)
- `sendMessage()` - suspend функция
- Блокирует поток планировщика

**Рекомендация:** ✅ **МОЖНО УЛУЧШИТЬ**
- Использовать `CoroutineScope.launch` для асинхронной отправки
- `health()` вызовы можно оставить синхронными

---

## 4. Другие сервисы

### 4.1. LogAnalysisService, TokenRefreshService, VacancyCleanupService
**Контекст:** Различные `@Scheduled` методы

**Рекомендация:** ⚠️ **АНАЛИЗИРОВАТЬ КАЖДЫЙ СЛУЧАЙ**
- Зависит от того, вызывают ли они suspend функции
- Если да - можно улучшить через `CoroutineScope.launch`

---

### 4.2. Controllers (OAuthController, VacancyTestController, TokenTestController)
**Контекст:** HTTP endpoints

**Рекомендация:** ⚠️ **ОСТОРОЖНО**
- В контроллерах `runBlocking` блокирует HTTP потоки
- Лучше использовать suspend функции в контроллерах
- Или `Deferred` для асинхронной обработки

---

### 4.3. Health Indicators (HHAPIHealthIndicator, TelegramHealthIndicator, OllamaHealthIndicator)
**Контекст:** Spring Boot Actuator health checks

**Рекомендация:** ⚠️ **ОСТАВИТЬ КАК ЕСТЬ**
- Health indicators должны быть быстрыми
- `runBlocking` здесь может быть приемлем, если операции быстрые
- Но лучше использовать suspend функции если возможно

---

## 🎯 Приоритетные улучшения

### Высокий приоритет:
1. ✅ **ProcessedVacancyCacheService.loadCacheOnStartup()** - блокирует старт
2. ✅ **ProcessedVacancyCacheService.invalidateAndRebuildCache()** - блокирует планировщик
3. ✅ **VacancyProcessingQueueService.loadPendingVacanciesOnStartup()** - блокирует старт
4. ✅ **HealthCheckService.performHealthCheck()** - блокирует планировщик

### Средний приоритет:
5. ⚠️ **VacancyProcessingQueueService.enqueue()** - требует рефакторинга
6. ⚠️ **Controllers** - блокируют HTTP потоки

### Низкий приоритет:
7. ⚠️ **@PreDestroy методы** - допустимо блокировать при shutdown
8. ⚠️ **Health Indicators** - если операции быстрые

---

## 📝 Рекомендации по реализации

### Для @EventListener и @Scheduled методов:
```kotlin
// Вместо:
@EventListener(ApplicationReadyEvent::class)
fun loadCacheOnStartup() {
    runBlocking {
        loadCacheFromDatabase()
    }
}

// Использовать:
@EventListener(ApplicationReadyEvent::class)
fun loadCacheOnStartup() {
    applicationScope.launch {
        loadCacheFromDatabase()
    }
}
```

### Для обычных функций, вызывающих suspend:
```kotlin
// Вместо:
fun enqueue(vacancyId: String): Boolean {
    val analysis = runBlocking {
        vacancyAnalysisService.findByVacancyId(vacancyId)
    }
}

// Использовать:
fun enqueue(vacancyId: String): Boolean {
    // Запускаем асинхронно, не ждем результат
    queueScope.launch {
        val analysis = vacancyAnalysisService.findByVacancyId(vacancyId)
        // Обработка результата
    }
    // Или сделать enqueue suspend функцией
}
```

