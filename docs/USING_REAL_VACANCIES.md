# Использование реальных вакансий с HH.ru API

## Быстрый старт

Если у вас уже есть токен доступа от HH.ru, вы можете сразу начать получать реальные вакансии.

## Шаг 1: Настройка токена

Добавьте токен в файл `.env`:

```env
HH_ACCESS_TOKEN=ваш_токен_здесь
```

**Важно:** Токен должен быть валидным и не истекшим.

## Шаг 2: Проверка токена

Запустите приложение:

```powershell
./gradlew bootRun
```

После запуска проверьте health endpoint:

```powershell
curl http://localhost:8080/actuator/health
```

В ответе `HHAPI` должен иметь статус `UP`:

```json
{
  "status": "UP",
  "components": {
    "HHAPI": {
      "status": "UP",
      "details": {...}
    }
  }
}
```

Если статус `DOWN` или `UNAUTHORIZED`, проверьте:
- Правильность токена в `.env`
- Не истек ли токен
- Правильно ли загружается `.env` файл

## Шаг 3: Тестирование поиска вакансий

Используйте тестовый endpoint для проверки работы с реальными вакансиями:

### Поиск вакансий

```powershell
# Простой поиск
curl "http://localhost:8080/api/vacancies/search?keywords=Kotlin"

# Поиск с фильтрами
curl "http://localhost:8080/api/vacancies/search?keywords=Kotlin%20Developer&area=Москва&minSalary=150000"

# Поиск с опытом
curl "http://localhost:8080/api/vacancies/search?keywords=Java&experience=От%203%20лет"
```

### Получение деталей вакансии

```powershell
curl "http://localhost:8080/api/vacancies/{vacancy_id}"
```

Замените `{vacancy_id}` на реальный ID вакансии из HH.ru.

## Шаг 4: Настройка автоматического поиска

Для автоматического получения вакансий по расписанию:

### 1. Создайте конфигурацию поиска в БД

Подключитесь к PostgreSQL:

```powershell
docker exec -it hh-postgres psql -U hh_user -d hh_assistant
```

Создайте конфигурацию:

```sql
INSERT INTO search_configs (keywords, min_salary, area, experience, is_active)
VALUES ('Kotlin Developer', 150000, 'Москва', 'От 3 лет', true);
```

Или для другого запроса:

```sql
INSERT INTO search_configs (keywords, min_salary, area, experience, is_active)
VALUES ('Java Developer', 200000, 'Санкт-Петербург', 'От 5 лет', true);
```

### 2. Убедитесь, что dry-run выключен

В `application.yml` или `.env`:

```yaml
app:
  dry-run: false
```

Или не используйте профиль `dry-run` при запуске.

### 3. Приложение будет автоматически:

- Проверять новые вакансии каждые 15 минут (настраивается в `app.schedule.vacancy-check`)
- Анализировать их через Ollama LLM
- Отправлять релевантные вакансии в Telegram (если настроено)

## Параметры поиска

### Поддерживаемые параметры в SearchConfig:

- **keywords** (обязательно) - ключевые слова для поиска
- **minSalary** - минимальная зарплата
- **maxSalary** - максимальная зарплата
- **area** - регион (например, "Москва", "Санкт-Петербург")
- **experience** - требуемый опыт (например, "От 3 лет")

### Примеры конфигураций:

```sql
-- Поиск Kotlin разработчиков в Москве от 150k
INSERT INTO search_configs (keywords, min_salary, area, experience, is_active)
VALUES ('Kotlin', 150000, 'Москва', NULL, true);

-- Поиск Senior Java разработчиков
INSERT INTO search_configs (keywords, min_salary, area, experience, is_active)
VALUES ('Senior Java Developer', 250000, NULL, 'От 5 лет', true);

-- Поиск без фильтров по зарплате
INSERT INTO search_configs (keywords, min_salary, area, experience, is_active)
VALUES ('Spring Boot', NULL, 'Москва', 'От 2 лет', true);
```

## Проверка работы

### Логи приложения

Следите за логами:

```powershell
Get-Content logs/hh-assistant.log -Wait -Tail 20
```

Ожидаемые сообщения:

```
Starting scheduled vacancy check
Fetched X new vacancies from HH.ru
Found Y vacancies to analyze
Vacancy check completed: analyzed Z, relevant W
```

### Проверка в БД

```sql
-- Количество загруженных вакансий
SELECT COUNT(*) FROM vacancies;

-- Новые вакансии
SELECT id, name, employer, status FROM vacancies WHERE status = 'NEW' LIMIT 10;

-- Релевантные вакансии
SELECT v.id, v.name, va.relevance_score 
FROM vacancies v 
JOIN vacancy_analyses va ON v.id = va.vacancy_id 
WHERE va.is_relevant = true 
ORDER BY va.relevance_score DESC;
```

## Troubleshooting

### Ошибка: "Unauthorized access to HH.ru API"

**Причина:** Неверный или истекший токен

**Решение:**
1. Проверьте токен в `.env`
2. Получите новый токен (см. `docs/OAUTH_SETUP.md`)
3. Перезапустите приложение

### Ошибка: "Rate limit exceeded"

**Причина:** Превышен лимит запросов к HH.ru API

**Решение:**
- Подождите несколько минут
- Уменьшите частоту проверки в `app.schedule.vacancy-check`
- Уменьшите `app.max-vacancies-per-cycle`

### Ошибка: "No active search configurations found"

**Причина:** Нет активных конфигураций поиска в БД

**Решение:**
- Создайте конфигурацию поиска (см. Шаг 4)
- Убедитесь, что `is_active = true`

### Вакансии не загружаются

**Проверьте:**
1. Токен валиден (health check показывает UP)
2. Есть активные конфигурации поиска
3. `dry-run: false`
4. Логи на наличие ошибок

## API Endpoints

### Тестовые endpoints:

- `GET /api/vacancies/search` - поиск вакансий (для тестирования)
- `GET /api/vacancies/{id}` - получение деталей вакансии

### Health checks:

- `GET /actuator/health` - общий статус приложения
- `GET /actuator/health/HHAPI` - статус HH.ru API

## Следующие шаги

После настройки получения реальных вакансий:

1. **Настройте Telegram** для получения уведомлений (см. `docs/WINDOWS_SETUP.md`)
2. **Настройте Ollama** для анализа вакансий (см. `docs/OLLAMA_SETUP.md`)
3. **Настройте расписание** под ваши нужды
4. **Мониторьте логи** для отслеживания работы




