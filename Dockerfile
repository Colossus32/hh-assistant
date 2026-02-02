FROM gradle:8-jdk17 AS builder
WORKDIR /app

# Копируем файлы конфигурации Gradle для кеширования слоев
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Загружаем зависимости (этот слой будет закеширован если build.gradle.kts не изменился)
RUN gradle dependencies --no-daemon || true

# Копируем исходный код
COPY src ./src

# Форматируем код перед сборкой (исправляет проблемы форматирования)
RUN gradle ktlintFormat --no-daemon || true

# Собираем приложение (используем bootJar вместо build, чтобы пропустить ktlintCheck)
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Устанавливаем curl для healthcheck
RUN apk add --no-cache curl

# Создаем директорию для логов
RUN mkdir -p /app/logs

# Копируем собранный JAR
COPY --from=builder /app/build/libs/*.jar app.jar

# Открываем порт приложения
EXPOSE 8080

# Healthcheck для проверки работоспособности
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]