# Docker и DevOps для разработчика

**Java/Kotlin Backend Developer | Middle/Senior**

## Docker

### КЕЙС #1 | Уровень: Middle
**ВОПРОС:** Как оптимизировать Dockerfile для Java/Kotlin приложения? Почему multi-stage builds важны?

**ОТВЕТ:**
```dockerfile
# ПЛОХО: один stage, большой образ
FROM openjdk:17
WORKDIR /app
COPY . .
RUN ./gradlew build
EXPOSE 8080
CMD ["java", "-jar", "build/libs/app.jar"]
# Проблемы: включает Gradle, исходники, тесты → 800MB+

# ХОРОШО: multi-stage build
# Stage 1: Build
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle build --no-daemon

# Stage 2: Runtime (только JAR)
FROM openjdk:17-jre-slim
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Оптимизации JVM
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
# Результат: 250MB вместо 800MB

# АЛЬТЕРНАТИВА: Spring Boot layered JAR (ещё лучше кэширование)
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon  # Кэшируется отдельно
COPY src ./src
RUN gradle bootJar --no-daemon

FROM openjdk:17-jre-slim
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Слои: dependencies меняются редко, application часто
FROM openjdk:17-jre-slim
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
```

### КЕЙС #5 | Уровень: Middle
**ВОПРОС:** Как настроить docker-compose для локальной разработки с PostgreSQL, Redis, Kafka?

**ОТВЕТ:**
```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/mydb
      SPRING_REDIS_HOST: redis
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
      kafka:
        condition: service_started
    volumes:
      - ./logs:/app/logs  # Логи наружу
  
  postgres:
    image: postgres:15
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: mydb
      POSTGRES_USER: user
      POSTGRES_PASSWORD: password
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U user"]
      interval: 5s
      timeout: 3s
      retries: 5
  
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
  
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
  
  kafka:
    image: confluentinc/cp-kafka:latest
    ports:
      - "9092:9092"
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    depends_on:
      - zookeeper

volumes:
  postgres_data:
  redis_data:
```

## CI/CD

### КЕЙС #10 | Уровень: Middle
**ВОПРОС:** Как настроить CI/CD pipeline для Spring Boot приложения в GitHub Actions?

**ОТВЕТ:**
```yaml
# .github/workflows/ci-cd.yml
name: CI/CD Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle
      
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      
      - name: Run tests
        run: ./gradlew test
        env:
          SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/test
          SPRING_DATASOURCE_USERNAME: postgres
          SPRING_DATASOURCE_PASSWORD: test
      
      - name: Build
        run: ./gradlew bootJar
      
      - name: Upload artifact
        uses: actions/upload-artifact@v3
        with:
          name: app-jar
          path: build/libs/*.jar
  
  deploy:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    
    steps:
      - name: Download artifact
        uses: actions/download-artifact@v3
        with:
          name: app-jar
      
      - name: Build Docker image
        run: |
          docker build -t myapp:${{ github.sha }} .
          docker tag myapp:${{ github.sha }} myapp:latest
      
      - name: Push to registry
        run: |
          echo ${{ secrets.DOCKER_PASSWORD }} | docker login -u ${{ secrets.DOCKER_USERNAME }} --password-stdin
          docker push myapp:latest
      
      - name: Deploy to server
        uses: appleboy/ssh-action@master
        with:
          host: ${{ secrets.SERVER_HOST }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SERVER_SSH_KEY }}
          script: |
            cd /app
            docker-compose pull
            docker-compose up -d
            docker-compose logs -f app
```

## Мониторинг

### КЕЙС #15 | Уровень: Senior
**ВОПРОС:** Как настроить мониторинг Spring Boot приложения через Prometheus и Grafana?

**ОТВЕТ:**
```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
}

// application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}

// Кастомные метрики
@Component
class OrderMetrics(meterRegistry: MeterRegistry) {
    
    private val orderCounter = Counter.builder("orders.created")
        .description("Total orders created")
        .register(meterRegistry)
    
    private val orderTimer = Timer.builder("orders.processing.time")
        .description("Order processing time")
        .register(meterRegistry)
    
    fun recordOrderCreated() {
        orderCounter.increment()
    }
    
    fun <T> measureProcessing(block: () -> T): T {
        return orderTimer.recordCallable(block)!!
    }
}

// docker-compose.yml
services:
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
  
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin

// prometheus.yml
scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']
```

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 15 | **Стоимость**: ~$0.75

*Версия: 1.0 | Январь 2026*




