# HH Assistant - AI Job Assistant MVP

AI-powered ассистент для поиска работы на HH.ru с использованием локальной LLM (Ollama + Qwen 2.5).

## Технологический стек

- **Backend**: Kotlin 1.9+ + Spring Boot 3.2+
- **Database**: PostgreSQL 16 + Liquibase
- **AI/LLM**: Ollama (Qwen 2.5 7B quantized)
- **HTTP Client**: Spring WebClient
- **PDF Parsing**: Apache PDFBox
- **Testing**: JUnit 5, MockK, TestContainers
- **Containerization**: Docker Compose

## Быстрый старт

### Предварительные требования

- Docker Desktop (с WSL2 на Windows)
- NVIDIA GPU с 6GB+ VRAM (для Ollama)
- JDK 17+
- Git

### Установка

1. Клонируйте репозиторий:
```bash
git clone <repository-url>
cd hh-assistant
```

2. Установите и запустите Ollama локально:
   - Скачайте с https://ollama.ai/download
   - Установите Ollama
   - Загрузите модель: `ollama pull qwen2.5:7b`
   - Ollama запустится автоматически как сервис
   - Подробнее: см. [docs/OLLAMA_SETUP.md](docs/OLLAMA_SETUP.md)

3. Создайте `.env` файл:
```bash
cp .env.example .env
# Отредактируйте .env и заполните токены
```

4. Запустите PostgreSQL:
```bash
docker-compose up -d postgres
```

5. Запустите приложение:
```bash
./gradlew bootRun
```

## Конфигурация

Основные настройки в `application.yml` и `.env`:

- `HH_ACCESS_TOKEN` - токен доступа HH.ru API
- `TELEGRAM_BOT_TOKEN` - токен Telegram бота
- `TELEGRAM_CHAT_ID` - ваш Chat ID в Telegram
- `OLLAMA_BASE_URL` - URL Ollama сервиса
- `OLLAMA_MODEL` - модель для использования (по умолчанию: qwen2.5:7b)

## Структура проекта

```
hh-assistant/
├── src/
│   ├── main/
│   │   ├── kotlin/com/hhassistant/
│   │   └── resources/
│   └── test/
├── resumes/          # Папка для PDF резюме
├── docs/             # Документация
└── docker-compose.yml
```

## Разработка

### Запуск тестов

```bash
./gradlew test
```

### Сборка

```bash
./gradlew build
```

### Запуск в Docker

```bash
docker-compose up --build
```

## Лицензия

MIT

