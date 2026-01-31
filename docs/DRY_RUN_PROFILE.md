# Dry-Run Profile

Профиль `dry-run` позволяет запускать приложение в тестовом режиме без реальных запросов к HH.ru API.

## Что включает профиль

- **DryRunDemoController** - REST endpoint для тестирования полного пайплайна (HH → LLM → Telegram)
- **Отключение реальных запросов к HH.ru** - `VacancySchedulerService` пропускает проверку вакансий
- **Health checks** - работают, но HH.ru API помечается как `UNKNOWN` в dry-run режиме

## Запуск с профилем dry-run

### Вариант 1: Через переменную окружения

```powershell
$env:SPRING_PROFILES_ACTIVE="dry-run"
./gradlew bootRun
```

### Вариант 2: Через параметр JVM

```powershell
./gradlew bootRun --args='--spring.profiles.active=dry-run'
```

### Вариант 3: Через application-dry-run.yml

Профиль автоматически активируется, если указать:

```powershell
./gradlew bootRun --args='--spring.config.location=classpath:application-dry-run.yml'
```

## Использование Dry-Run Demo Endpoint

После запуска с профилем `dry-run` доступен endpoint:

```
GET http://localhost:8080/api/dry-run/sample-analysis
```

Этот endpoint:
1. Создает тестовую вакансию (Senior Kotlin Developer) в памяти
2. Анализирует её через Ollama LLM
3. Отправляет результат в Telegram (если вакансия релевантна)

**Важно:** Этот endpoint доступен **только** при активном профиле `dry-run`.

## Основной профиль (без dry-run)

По умолчанию приложение запускается **без** профиля `dry-run`:
- `DryRunDemoController` не создается
- `app.dry-run: false` (из `application.yml`)
- Реальные запросы к HH.ru API выполняются по расписанию

## Конфигурация

Настройки dry-run находятся в `application-dry-run.yml`:

```yaml
app:
  dry-run: true
  # ... остальные настройки
```

Основные настройки в `application.yml`:

```yaml
app:
  dry-run: false  # По умолчанию выключен
```

## Проверка активного профиля

В логах при запуске будет видно:

```
The following profiles are active: dry-run
```

Или при отсутствии профиля:

```
No active profile set, falling back to 1 default profile: "default"
```





