# Docker Setup Guide

Инструкция по запуску приложения в Docker.

## Предварительные требования

1. **Docker Desktop** (для Windows/Mac) или **Docker Engine** (для Linux)
2. **Ollama** установлена локально и запущена
   - Скачать: https://ollama.ai/download
   - Запустить: `ollama serve`
   - Загрузить модель: `ollama pull qwen2.5:7b`

## Быстрый старт

### 1. Настройка переменных окружения

Скопируйте `env.example` в `.env` и заполните необходимые переменные:

```bash
cp env.example .env
```

Отредактируйте `.env` и укажите:
- `HH_ACCESS_TOKEN` - токен доступа к HH.ru API
- `TELEGRAM_BOT_TOKEN` - токен Telegram бота
- `TELEGRAM_CHAT_ID` - ID чата в Telegram
- `TELEGRAM_ALLOWED_USER_IDS` - ваш Telegram user ID

### 2. Запуск приложения

#### Вариант 1: Использование PowerShell скрипта (рекомендуется)

```powershell
# Запуск на стандартном порту 8080
.\scripts\start-docker.ps1

# Запуск на кастомном порту (например, 8081)
.\scripts\start-docker.ps1 -Port 8081

# Запуск с пересборкой образа
.\scripts\start-docker.ps1 -Port 8081 -Build

# Остановка контейнеров
.\scripts\start-docker.ps1 -Stop
```

Скрипт автоматически:
- ✅ Проверяет, что Docker запущен
- ✅ Проверяет доступность Ollama и пытается запустить, если не работает
- ✅ Проверяет, что порт свободен
- ✅ Запускает контейнеры
- ✅ Ждёт готовности приложения и проверяет health check
- ✅ Показывает статус всех компонентов

#### Вариант 2: Ручной запуск через docker-compose

```bash
# Установите порт (опционально, по умолчанию 8080)
$env:APP_PORT = "8081"

# Сборка и запуск всех сервисов
docker-compose up -d

# Просмотр логов
docker-compose logs -f app

# Остановка
docker-compose down

# Остановка с удалением volumes (удалит данные БД)
docker-compose down -v
```

### 3. Проверка работы

После запуска скрипта приложение будет доступно:
- Приложение: http://localhost:8080 (или указанный порт)
- Health check: http://localhost:8080/actuator/health
- База данных: localhost:5432

Скрипт автоматически проверит health check и покажет статус всех компонентов.

## Доступ к Ollama из Docker

Приложение в Docker обращается к локально запущенной Ollama через `host.docker.internal:11434`.

### Windows/Mac
Работает из коробки - Docker Desktop автоматически настраивает `host.docker.internal`.

### Linux
Если `host.docker.internal` не работает, добавьте в `docker-compose.yml` в секцию `app`:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

Или используйте `network_mode: host` (но это менее безопасно):

```yaml
network_mode: host
```

## Структура сервисов

- **postgres** - База данных PostgreSQL
  - Порт: 5432
  - Данные сохраняются в volume `postgres_data`

- **app** - Приложение HH Assistant
  - Порт: 8080
  - Volumes:
    - `./resumes` - резюме (read-only)
    - `./logs` - логи приложения

- **ollama** - Запускается локально (не в Docker)
  - Порт: 11434
  - Доступ из контейнера: `http://host.docker.internal:11434`

## Полезные команды

```bash
# Пересобрать образ приложения
docker-compose build app

# Перезапустить сервис
docker-compose restart app

# Просмотр логов конкретного сервиса
docker-compose logs -f postgres
docker-compose logs -f app

# Выполнить команду в контейнере
docker-compose exec app sh

# Просмотр статуса сервисов
docker-compose ps

# Очистка неиспользуемых ресурсов
docker system prune -a
```

## Troubleshooting

### Приложение не может подключиться к Ollama

1. Убедитесь, что Ollama запущен локально:
   ```bash
   ollama serve
   ```

2. Проверьте доступность Ollama:
   ```bash
   curl http://localhost:11434/api/tags
   ```

3. На Linux добавьте `extra_hosts` в docker-compose.yml (см. выше)

### Приложение не может подключиться к базе данных

1. Проверьте, что PostgreSQL контейнер запущен:
   ```bash
   docker-compose ps
   ```

2. Проверьте логи PostgreSQL:
   ```bash
   docker-compose logs postgres
   ```

3. Убедитесь, что healthcheck прошёл успешно перед запуском приложения

### Проблемы с переменными окружения

1. Убедитесь, что файл `.env` существует в корне проекта
2. Проверьте синтаксис `.env` (без пробелов вокруг `=`)
3. Перезапустите контейнеры:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

### Проблемы с портами

Если порты 5432 или 8080 заняты, измените их в `docker-compose.yml`:

```yaml
ports:
  - "5433:5432"  # Внешний:Внутренний
```

## Обновление приложения

```bash
# Остановить контейнеры
docker-compose down

# Пересобрать образ
docker-compose build app

# Запустить снова
docker-compose up -d
```

## Резервное копирование базы данных

```bash
# Создать backup
docker-compose exec postgres pg_dump -U hh_user hh_assistant > backup.sql

# Восстановить из backup
docker-compose exec -T postgres psql -U hh_user hh_assistant < backup.sql
```