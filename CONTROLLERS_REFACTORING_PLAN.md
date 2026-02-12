# План рефакторинга контроллеров: замена runBlocking на suspend функции

## 📋 Текущая ситуация

### Найдено использований `runBlocking` в контроллерах: **5**

1. **TokenTestController** (1 место):
   - `testToken()` - строка 76: `hhVacancyClient.searchVacancies()`

2. **OAuthController** (2 места):
   - `getApplicationToken()` - строка 67: `oauthService.getApplicationToken()`
   - `callback()` - строка 163: `oauthService.exchangeCodeForToken()`

3. **VacancyTestController** (2 места):
   - `searchVacancies()` - строка 47: `hhVacancyClient.searchVacancies()`
   - `getVacancyDetails()` - строка 81: `hhVacancyClient.getVacancyDetails()`

### Проблема
- `runBlocking` блокирует HTTP потоки Tomcat/Netty
- Снижает пропускную способность сервера
- Неэффективное использование потоков

### Контекст проекта
- ✅ Используется `spring-boot-starter-webflux` (WebFlux)
- ✅ Используется `spring-boot-starter-web` (MVC)
- ✅ Есть `kotlinx-coroutines-reactor` и `kotlinx-coroutines-core`
- ✅ Spring Boot 3.2.0 поддерживает suspend функции в контроллерах

---

## 🎯 Решение: Использовать suspend функции в контроллерах

### Преимущества
- ✅ Не блокирует HTTP потоки
- ✅ Эффективное использование потоков
- ✅ Нативная поддержка в Spring WebFlux
- ✅ Более идиоматичный Kotlin код
- ✅ Лучшая масштабируемость

### Важно
Spring Boot 3.x с Kotlin корутинами поддерживает suspend функции в контроллерах:
- **WebFlux**: полная поддержка из коробки
- **MVC**: поддерживается через `kotlinx-coroutines-reactor`

---

## 📝 План реализации

### Шаг 1: TokenTestController.testToken()

**Текущий код:**
```kotlin
@GetMapping("/test")
fun testToken(
    @RequestParam(required = false, defaultValue = "Java") keywords: String,
): ResponseEntity<Map<String, Any>> {
    // ...
    val vacancies = runBlocking {
        hhVacancyClient.searchVacancies(searchConfig)
    }
    // ...
}
```

**Новый код:**
```kotlin
@GetMapping("/test")
suspend fun testToken(
    @RequestParam(required = false, defaultValue = "Java") keywords: String,
): ResponseEntity<Map<String, Any>> {
    // ...
    val vacancies = hhVacancyClient.searchVacancies(searchConfig) // ✅ Без runBlocking
    // ...
}
```

**Изменения:**
- Добавить `suspend` к функции
- Убрать `runBlocking`
- Вызывать `searchVacancies()` напрямую

---

### Шаг 2: OAuthController.getApplicationToken()

**Текущий код:**
```kotlin
@GetMapping("/application-token")
fun getApplicationToken(
    @Value("\${hh.api.user-agent}") userAgent: String,
): ResponseEntity<Map<String, Any>> {
    val tokenResponse: OAuthTokenResponse = runBlocking {
        oauthService.getApplicationToken(userAgent)
    }
    // ...
}
```

**Новый код:**
```kotlin
@GetMapping("/application-token")
suspend fun getApplicationToken(
    @Value("\${hh.api.user-agent}") userAgent: String,
): ResponseEntity<Map<String, Any>> {
    val tokenResponse: OAuthTokenResponse = oauthService.getApplicationToken(userAgent) // ✅ Без runBlocking
    // ...
}
```

**Изменения:**
- Добавить `suspend` к функции
- Убрать `runBlocking`
- Вызывать `getApplicationToken()` напрямую

---

### Шаг 3: OAuthController.callback()

**Текущий код:**
```kotlin
@GetMapping("/callback")
fun callback(
    @RequestParam("code", required = false) code: String?,
    // ...
): ResponseEntity<Map<String, Any>> {
    // ...
    val tokenResponse: OAuthTokenResponse = runBlocking {
        oauthService.exchangeCodeForToken(code)
    }
    // ...
}
```

**Новый код:**
```kotlin
@GetMapping("/callback")
suspend fun callback(
    @RequestParam("code", required = false) code: String?,
    // ...
): ResponseEntity<Map<String, Any>> {
    // ...
    val tokenResponse: OAuthTokenResponse = oauthService.exchangeCodeForToken(code) // ✅ Без runBlocking
    // ...
}
```

**Изменения:**
- Добавить `suspend` к функции
- Убрать `runBlocking`
- Вызывать `exchangeCodeForToken()` напрямую

---

### Шаг 4: VacancyTestController.searchVacancies()

**Текущий код:**
```kotlin
@GetMapping("/search")
fun searchVacancies(
    @RequestParam("keywords", required = false, defaultValue = "Kotlin Developer") keywords: String,
    // ...
): ResponseEntity<Any> {
    // ...
    val vacancies = runBlocking {
        hhVacancyClient.searchVacancies(config)
    }
    // ...
}
```

**Новый код:**
```kotlin
@GetMapping("/search")
suspend fun searchVacancies(
    @RequestParam("keywords", required = false, defaultValue = "Kotlin Developer") keywords: String,
    // ...
): ResponseEntity<Any> {
    // ...
    val vacancies = hhVacancyClient.searchVacancies(config) // ✅ Без runBlocking
    // ...
}
```

**Изменения:**
- Добавить `suspend` к функции
- Убрать `runBlocking`
- Вызывать `searchVacancies()` напрямую

---

### Шаг 5: VacancyTestController.getVacancyDetails()

**Текущий код:**
```kotlin
@GetMapping("/{id}")
fun getVacancyDetails(@PathVariable id: String): ResponseEntity<Any> {
    val vacancy = runBlocking {
        hhVacancyClient.getVacancyDetails(id)
    }
    // ...
}
```

**Новый код:**
```kotlin
@GetMapping("/{id}")
suspend fun getVacancyDetails(@PathVariable id: String): ResponseEntity<Any> {
    val vacancy = hhVacancyClient.getVacancyDetails(id) // ✅ Без runBlocking
    // ...
}
```

**Изменения:**
- Добавить `suspend` к функции
- Убрать `runBlocking`
- Вызывать `getVacancyDetails()` напрямую

---

## ⚠️ Важные моменты

### 1. Обработка ошибок
- Ошибки будут обрабатываться через `GlobalExceptionHandler` как и раньше
- Suspend функции не меняют механизм обработки исключений

### 2. Обратная совместимость
- API endpoints остаются теми же
- Изменяется только внутренняя реализация
- Клиенты не заметят изменений

### 3. Производительность
- HTTP потоки не блокируются
- Лучшая масштабируемость
- Эффективное использование ресурсов

### 4. Тестирование
- Тесты могут потребовать обновления для работы с suspend функциями
- Использовать `runTest` или `runBlocking` в тестах (это нормально)

---

## 📋 Чеклист реализации

- [ ] TokenTestController.testToken() - добавить suspend, убрать runBlocking
- [ ] OAuthController.getApplicationToken() - добавить suspend, убрать runBlocking
- [ ] OAuthController.callback() - добавить suspend, убрать runBlocking
- [ ] VacancyTestController.searchVacancies() - добавить suspend, убрать runBlocking
- [ ] VacancyTestController.getVacancyDetails() - добавить suspend, убрать runBlocking
- [ ] Удалить неиспользуемые импорты `runBlocking`
- [ ] Проверить компиляцию
- [ ] Проверить ktlint
- [ ] Протестировать endpoints вручную (опционально)

---

## ✅ Ожидаемый результат

После рефакторинга:
- ✅ Нет блокирующих вызовов `runBlocking` в контроллерах
- ✅ Все методы используют suspend функции
- ✅ HTTP потоки не блокируются
- ✅ Улучшенная производительность и масштабируемость
- ✅ Более чистый и идиоматичный Kotlin код

---

## 🔍 Дополнительные замечания

### Почему это работает?
Spring Boot 3.x с Kotlin корутинами автоматически обрабатывает suspend функции в контроллерах:
- WebFlux использует корутины напрямую
- MVC использует адаптер через `kotlinx-coroutines-reactor`
- Spring автоматически создает корутин-контекст для каждого запроса

### Альтернативный подход (не рекомендуется)
Можно было бы использовать `Deferred` и `async`, но это сложнее и менее идиоматично:
```kotlin
// НЕ РЕКОМЕНДУЕТСЯ
fun getVacancy(): Deferred<ResponseEntity<Any>> = async {
    val vacancy = hhVacancyClient.getVacancyDetails(id)
    ResponseEntity.ok(vacancy)
}
```

Suspend функции - это правильный и простой способ для Spring контроллеров.






