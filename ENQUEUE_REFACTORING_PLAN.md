# План рефакторинга VacancyProcessingQueueService.enqueue()

## 📋 Текущая ситуация

### Проблема
В методе `enqueue()` есть **2 использования `runBlocking`** для вызова suspend функции `findByVacancyId()`:
- Строка 183: внутри блока `if (checkDuplicate)`
- Строка 258: внутри блока `else` (когда `checkDuplicate = false`)

### Текущая логика
```kotlin
fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
    // ... проверки ...
    
    if (processedVacancyCacheService.isProcessed(vacancyId)) {
        val existingAnalysis = runBlocking {  // ❌ БЛОКИРУЕТ ПОТОК
            vacancyAnalysisService.findByVacancyId(vacancyId)
        }
        // Обновление статуса на основе анализа
    }
}
```

### Места вызова `enqueue()`
1. ✅ `loadPendingVacanciesOnStartup()` - уже внутри `queueScope.launch`
2. ✅ `enqueueBatch()` - уже suspend функция
3. Возможно другие места (нужно проверить)

---

## 🎯 Варианты решения

### Вариант 1: Асинхронное обновление статуса (РЕКОМЕНДУЕТСЯ)

**Идея:** Запускать получение анализа и обновление статуса асинхронно, не блокируя основной поток.

**Преимущества:**
- ✅ Не требует изменения сигнатуры `enqueue()`
- ✅ Не блокирует поток
- ✅ Минимальные изменения в коде
- ✅ Обратная совместимость

**Недостатки:**
- ⚠️ Обновление статуса происходит асинхронно (может быть небольшая задержка)
- ⚠️ Нужно обрабатывать ошибки в корутине

**Реализация:**
```kotlin
fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
    // ... проверки ...
    
    if (processedVacancyCacheService.isProcessed(vacancyId)) {
        // Запускаем асинхронное обновление статуса
        queueScope.launch {
            updateStatusIfAnalysisExists(vacancyId, vacancy)
        }
        queuedVacancies.remove(vacancyId)
        return false
    }
}

private suspend fun updateStatusIfAnalysisExists(vacancyId: String, vacancy: Vacancy) {
    try {
        val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancyId)
        if (existingAnalysis != null) {
            // Обновление статуса
        } else {
            // Удаление из кэша
        }
    } catch (e: Exception) {
        log.error("Failed to update status for vacancy $vacancyId", e)
    }
}
```

---

### Вариант 2: Сделать `enqueue()` suspend функцией

**Идея:** Преобразовать `enqueue()` в suspend функцию.

**Преимущества:**
- ✅ Полностью асинхронный код
- ✅ Можно использовать suspend функции напрямую
- ✅ Более идиоматичный Kotlin код

**Недостатки:**
- ❌ Требует изменения всех мест вызова
- ❌ `loadPendingVacanciesOnStartup()` уже в корутине - ок
- ❌ `enqueueBatch()` уже suspend - ок
- ⚠️ Нужно проверить все остальные места вызова

**Реализация:**
```kotlin
suspend fun enqueue(vacancyId: String, checkDuplicate: Boolean = true): Boolean {
    // ... проверки ...
    
    if (processedVacancyCacheService.isProcessed(vacancyId)) {
        val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancyId) // ✅ Без runBlocking
        // Обновление статуса
    }
}
```

---

### Вариант 3: Гибридный подход

**Идея:** Создать две версии - синхронную и асинхронную.

**Преимущества:**
- ✅ Обратная совместимость
- ✅ Гибкость использования

**Недостатки:**
- ❌ Дублирование кода
- ❌ Сложнее поддерживать

---

## 🎯 Рекомендуемый план (Вариант 1)

### Шаг 1: Создать вспомогательную suspend функцию
```kotlin
private suspend fun updateStatusIfAnalysisExists(
    vacancyId: String,
    vacancy: Vacancy,
    checkDuplicate: Boolean
) {
    try {
        log.debug("📊 [VacancyProcessingQueue] Cache hit for vacancy $vacancyId, fetching analysis from DB for status update")
        val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancyId)
        
        if (existingAnalysis != null) {
            log.warn(
                "⚠️ [VacancyProcessingQueue] Vacancy $vacancyId already has analysis (analyzed at ${existingAnalysis.analyzedAt}), " +
                    "but status is ${vacancy.status}. Updating status and skipping.",
            )
            
            val correctStatus = if (existingAnalysis.isRelevant) {
                VacancyStatus.ANALYZED
            } else {
                if (checkDuplicate) VacancyStatus.NOT_SUITABLE else VacancyStatus.SKIPPED
            }
            
            if (vacancy.status != correctStatus) {
                vacancyStatusService.updateVacancyStatus(vacancy.withStatus(correctStatus))
                log.info(" [VacancyProcessingQueue] Updated vacancy $vacancyId status from ${vacancy.status} to $correctStatus")
            }
        } else {
            // Кэш говорит, что обработана, но анализа нет - возможно кэш устарел
            log.warn(
                "⚠️ [VacancyProcessingQueue] Vacancy $vacancyId marked as processed in cache, but analysis not found. Removing from cache.",
            )
            processedVacancyCacheService.removeFromCache(vacancyId)
        }
    } catch (e: Exception) {
        log.error(" [VacancyProcessingQueue] Failed to update status for vacancy $vacancyId: ${e.message}", e)
    }
}
```

### Шаг 2: Заменить `runBlocking` на асинхронный вызов
```kotlin
if (processedVacancyCacheService.isProcessed(vacancyId)) {
    // Запускаем асинхронное обновление статуса, не блокируя поток
    queueScope.launch {
        updateStatusIfAnalysisExists(vacancyId, vacancy, checkDuplicate)
    }
    queuedVacancies.remove(vacancyId)
    return false
}
```

### Шаг 3: Удалить дублирование кода
- Объединить две одинаковые проверки (для `checkDuplicate = true` и `false`)
- Использовать одну функцию `updateStatusIfAnalysisExists()`

---

## 📝 Детальный план реализации

### 1. Создать вспомогательную функцию `updateStatusIfAnalysisExists()`
   - Вынести логику получения анализа и обновления статуса
   - Обработать ошибки внутри функции
   - Учесть разницу в статусах для `checkDuplicate = true/false`

### 2. Заменить первый `runBlocking` (строка 183)
   - В блоке `if (checkDuplicate)`
   - Заменить на `queueScope.launch { updateStatusIfAnalysisExists(...) }`

### 3. Заменить второй `runBlocking` (строка 258)
   - В блоке `else` (когда `checkDuplicate = false`)
   - Заменить на `queueScope.launch { updateStatusIfAnalysisExists(...) }`

### 4. Удалить дублирование кода
   - Объединить две одинаковые проверки
   - Использовать единую логику

### 5. Тестирование
   - Проверить, что статусы обновляются корректно
   - Проверить обработку ошибок
   - Проверить, что поток не блокируется

---

## ⚠️ Важные моменты

1. **Асинхронность обновления статуса:**
   - Статус будет обновляться асинхронно, с небольшой задержкой
   - Это приемлемо, так как основная цель - не блокировать поток

2. **Обработка ошибок:**
   - Все ошибки должны логироваться внутри корутины
   - Не должны влиять на основной поток

3. **Обратная совместимость:**
   - Сигнатура `enqueue()` остается прежней
   - Все существующие вызовы продолжают работать

4. **Производительность:**
   - Поток не блокируется при вызове `enqueue()`
   - Обновление статуса происходит в фоне

---

## ✅ Ожидаемый результат

После рефакторинга:
- ✅ Нет блокирующих вызовов `runBlocking` в `enqueue()`
- ✅ Все операции выполняются асинхронно
- ✅ Код более чистый и поддерживаемый
- ✅ Обратная совместимость сохранена






