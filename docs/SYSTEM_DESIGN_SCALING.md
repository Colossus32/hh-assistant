# 📈 Паттерны масштабирования и производительности

## 📚 Содержание
1. [Стратегии кеширования](#стратегии-кеширования)
2. [Масштабирование баз данных](#масштабирование-баз-данных)
3. [CDN и статический контент](#cdn-и-статический-контент)
4. [Асинхронная обработка](#асинхронная-обработка)
5. [Оптимизация запросов](#оптимизация-запросов)
6. [Rate Limiting](#rate-limiting)

---

## Стратегии кеширования

### 1. Cache-Aside (Lazy Loading)

**Как работает:**
```
1. Приложение проверяет кеш
2. Если есть (cache hit) → возвращает из кеша
3. Если нет (cache miss) → запрашивает из БД
4. Сохраняет в кеш для следующих запросов
```

**Пример:**
```kotlin
@Service
class UserService {
    @Autowired
    lateinit var userRepository: UserRepository
    
    @Autowired
    lateinit var redisTemplate: RedisTemplate<String, User>
    
    fun getUser(userId: String): User {
        // 1. Проверяем кеш
        val cached = redisTemplate.opsForValue().get("user:$userId")
        if (cached != null) {
            return cached
        }
        
        // 2. Запрашиваем из БД
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException()
        
        // 3. Сохраняем в кеш (TTL = 1 час)
        redisTemplate.opsForValue().set("user:$userId", user, 1, TimeUnit.HOURS)
        
        return user
    }
}
```

**Плюсы:**
- Простота реализации
- Кеш может упасть - приложение продолжит работать
- Гибкость в выборе что кешировать

**Минусы:**
- Cache miss = 2 запроса (кеш + БД)
- Может быть stale data (устаревшие данные)

---

### 2. Write-Through Cache

**Как работает:**
```
1. Запись всегда идет в БД
2. Затем обновляется кеш
3. Гарантирует консистентность
```

**Пример:**
```kotlin
@Service
class UserService {
    fun updateUser(user: User): User {
        // 1. Сохраняем в БД
        val saved = userRepository.save(user)
        
        // 2. Обновляем кеш
        redisTemplate.opsForValue().set("user:${user.id}", saved, 1, TimeUnit.HOURS)
        
        return saved
    }
}
```

**Плюсы:**
- Консистентность данных
- Кеш всегда актуален

**Минусы:**
- Медленнее (2 операции)
- Если БД упала, кеш не обновится

---

### 3. Write-Back (Write-Behind) Cache

**Как работает:**
```
1. Запись идет в кеш
2. Асинхронно записывается в БД
3. Быстрее, но риск потери данных
```

**Пример:**
```kotlin
@Service
class UserService {
    @Autowired
    lateinit var asyncExecutor: ExecutorService
    
    fun updateUser(user: User): User {
        // 1. Сразу в кеш
        redisTemplate.opsForValue().set("user:${user.id}", user, 1, TimeUnit.HOURS)
        
        // 2. Асинхронно в БД
        asyncExecutor.submit {
            userRepository.save(user)
        }
        
        return user
    }
}
```

**Плюсы:**
- Очень быстро
- Меньше нагрузки на БД

**Минусы:**
- Риск потери данных при падении
- Сложнее реализация

---

### 4. Cache Invalidation Strategies

**Проблема:** Как обновлять кеш при изменении данных?

#### Time-Based (TTL)
```kotlin
// Простое решение - данные устаревают через время
redisTemplate.opsForValue().set("user:$userId", user, 1, TimeUnit.HOURS)
```

#### Event-Based
```kotlin
// При изменении данных - инвалидируем кеш
@EventListener
class UserCacheInvalidator {
    fun onUserUpdated(event: UserUpdatedEvent) {
        redisTemplate.delete("user:${event.userId}")
    }
}
```

#### Tag-Based
```kotlin
// Кешируем с тегами, инвалидируем по тегам
fun cacheUser(user: User) {
    redisTemplate.opsForValue().set("user:${user.id}", user)
    redisTemplate.opsForSet().add("tag:users", "user:${user.id}")
}

fun invalidateAllUsers() {
    val keys = redisTemplate.opsForSet().members("tag:users")
    redisTemplate.delete(keys)
}
```

---

## Масштабирование баз данных

### 1. Read Replicas

**Проблема:** Одна БД не справляется с чтением

**Решение:** Реплики только для чтения

```
         Write
         │
         ▼
    ┌─────────┐
    │ Master  │ ──► Replication
    │   DB    │
    └─────────┘
         │
    ┌────┼────┐
    ▼    ▼    ▼
  Read  Read  Read
 Replica1 Replica2 Replica3
```

**Пример (Spring Boot):**
```yaml
# application.yml
spring:
  datasource:
    master:
      url: jdbc:postgresql://master-db:5432/mydb
    replicas:
      - url: jdbc:postgresql://replica1-db:5432/mydb
      - url: jdbc:postgresql://replica2-db:5432/mydb
```

```kotlin
@Configuration
class DatabaseConfig {
    @Bean
    @Primary
    fun masterDataSource(): DataSource {
        return DataSourceBuilder.create()
            .url("jdbc:postgresql://master-db:5432/mydb")
            .build()
    }
    
    @Bean
    fun replicaDataSource(): DataSource {
        val routingDataSource = RoutingDataSource()
        routingDataSource.setTargetDataSources(mapOf(
            "replica1" to DataSourceBuilder.create()
                .url("jdbc:postgresql://replica1-db:5432/mydb")
                .build(),
            "replica2" to DataSourceBuilder.create()
                .url("jdbc:postgresql://replica2-db:5432/mydb")
                .build()
        ))
        return routingDataSource
    }
}

// Использование
@Transactional(readOnly = true)  // Автоматически идет на replica
fun getUser(userId: String): User {
    return userRepository.findById(userId)
}

@Transactional  // Автоматически идет на master
fun createUser(user: User): User {
    return userRepository.save(user)
}
```

---

### 2. Database Sharding

**Проблема:** Одна БД не справляется с объемом данных

**Решение:** Разделить данные на несколько БД

#### Hash-Based Sharding

```
User ID: 12345
Hash: hash(12345) = 789
Shard: 789 % 4 = 1
→ Shard 1
```

**Пример:**
```kotlin
@Service
class ShardedUserService {
    private val shards = listOf(
        "jdbc:postgresql://shard1:5432/mydb",
        "jdbc:postgresql://shard2:5432/mydb",
        "jdbc:postgresql://shard3:5432/mydb",
        "jdbc:postgresql://shard4:5432/mydb"
    )
    
    private fun getShard(userId: String): Int {
        return Math.abs(userId.hashCode()) % shards.size
    }
    
    fun getUser(userId: String): User {
        val shardIndex = getShard(userId)
        val dataSource = getDataSourceForShard(shardIndex)
        return userRepository.findByUserId(userId, dataSource)
    }
}
```

#### Range-Based Sharding

```
User ID 1-1000     → Shard 1
User ID 1001-2000  → Shard 2
User ID 2001-3000  → Shard 3
```

**Проблема:** Неравномерное распределение

#### Directory-Based Sharding

```
Shard Map:
User ID 123 → Shard 1
User ID 456 → Shard 2
User ID 789 → Shard 1
```

**Гибко, но нужна дополнительная таблица**

---

### 3. Database Partitioning

**Проблема:** Большая таблица медленно работает

**Решение:** Разделить таблицу на партиции

#### Range Partitioning (PostgreSQL)

```sql
-- Создание партиционированной таблицы
CREATE TABLE orders (
    id BIGSERIAL,
    user_id BIGINT,
    created_at TIMESTAMP,
    total DECIMAL
) PARTITION BY RANGE (created_at);

-- Партиции по месяцам
CREATE TABLE orders_2024_01 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');

CREATE TABLE orders_2024_02 PARTITION OF orders
    FOR VALUES FROM ('2024-02-01') TO ('2024-03-01');

-- Запросы автоматически идут в нужную партицию
SELECT * FROM orders WHERE created_at >= '2024-01-15';
-- Использует только orders_2024_01
```

#### Hash Partitioning

```sql
CREATE TABLE users (
    id BIGSERIAL,
    email VARCHAR,
    name VARCHAR
) PARTITION BY HASH (id);

CREATE TABLE users_0 PARTITION OF users
    FOR VALUES WITH (modulus 4, remainder 0);

CREATE TABLE users_1 PARTITION OF users
    FOR VALUES WITH (modulus 4, remainder 1);
```

**Преимущества:**
- Быстрее запросы (меньше данных сканировать)
- Проще управление (можно удалять старые партиции)
- Параллельная обработка

---

## CDN и статический контент

### Content Delivery Network (CDN)

**Проблема:** Статические файлы (изображения, CSS, JS) загружаются медленно из-за расстояния

**Решение:** Кешировать на серверах близко к пользователям

```
User (Moscow) ──► CDN Edge (Moscow) ──► Origin Server (USA)
                (cache hit)              (cache miss)
```

**Что кешировать в CDN:**
- Изображения
- CSS, JavaScript
- Видео
- HTML (если статический)

**Пример (CloudFlare, AWS CloudFront):**
```kotlin
@RestController
class ImageController {
    @GetMapping("/images/{imageId}")
    fun getImage(@PathVariable imageId: String): ResponseEntity<Resource> {
        val image = imageService.getImage(imageId)
        
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS))
            .eTag(image.etag)
            .body(image.resource)
    }
}
```

**Cache Headers:**
```
Cache-Control: public, max-age=31536000
ETag: "abc123"
Last-Modified: Wed, 21 Oct 2024 07:28:00 GMT
```

---

## Асинхронная обработка

### 1. Message Queue для тяжелых операций

**Проблема:** Долгие операции блокируют пользователя

**Решение:** Асинхронная обработка через очередь

```
User Request ──► API ──► Message Queue ──► Worker ──► Result
                │                              │
                └──► Response (202 Accepted)   └──► Notification
```

**Пример (Kafka):**
```kotlin
// Producer (API)
@RestController
class OrderController {
    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>
    
    @PostMapping("/orders")
    fun createOrder(@RequestBody order: Order): ResponseEntity<OrderResponse> {
        val orderId = UUID.randomUUID().toString()
        
        // Сохраняем в БД
        orderRepository.save(order.copy(id = orderId))
        
        // Отправляем в очередь для обработки
        kafkaTemplate.send("order-processing", orderId, order.toJson())
        
        return ResponseEntity.accepted()
            .body(OrderResponse(orderId, "PROCESSING"))
    }
}

// Consumer (Worker)
@KafkaListener(topics = ["order-processing"])
fun processOrder(message: String) {
    val order = parseOrder(message)
    
    // Долгая операция
    paymentService.charge(order.total)
    inventoryService.reserve(order.items)
    notificationService.sendEmail(order.userId)
    
    // Обновляем статус
    orderRepository.updateStatus(order.id, "COMPLETED")
}
```

---

### 2. Background Jobs

**Проблема:** Периодические задачи (отчеты, очистка, синхронизация)

**Решение:** Scheduled tasks

**Пример (Spring @Scheduled):**
```kotlin
@Component
class ScheduledTasks {
    @Autowired
    lateinit var orderService: OrderService
    
    // Каждый час
    @Scheduled(fixedRate = 3600000)
    fun generateHourlyReport() {
        val report = orderService.generateReport(LocalDateTime.now().minusHours(1))
        reportService.save(report)
    }
    
    // Каждый день в 2:00
    @Scheduled(cron = "0 0 2 * * ?")
    fun cleanupOldData() {
        orderService.deleteOrdersOlderThan(90, ChronoUnit.DAYS)
    }
}
```

---

## Оптимизация запросов

### 1. Database Indexing

**Проблема:** Медленные запросы

**Решение:** Индексы для часто используемых полей

```sql
-- Создание индекса
CREATE INDEX idx_user_email ON users(email);

-- Составной индекс
CREATE INDEX idx_order_user_date ON orders(user_id, created_at);

-- Частичный индекс (только для активных заказов)
CREATE INDEX idx_active_orders ON orders(user_id) 
WHERE status = 'ACTIVE';
```

**Когда создавать индексы:**
- Часто используемые в WHERE
- Используемые в JOIN
- Используемые для сортировки (ORDER BY)

**Когда НЕ создавать:**
- Часто обновляемые таблицы (индексы замедляют INSERT/UPDATE)
- Маленькие таблицы (< 1000 строк)

---

### 2. Query Optimization

#### N+1 Problem

**Плохо:**
```kotlin
// Запрос 1: получить все заказы
val orders = orderRepository.findAll()

// Запросы 2-N: для каждого заказа получить пользователя
orders.forEach { order ->
    val user = userRepository.findById(order.userId)  // N запросов!
}
```

**Хорошо (JOIN):**
```kotlin
// Один запрос с JOIN
@Query("""
    SELECT o FROM Order o 
    JOIN FETCH o.user 
    WHERE o.status = :status
""")
fun findOrdersWithUsers(status: OrderStatus): List<Order>
```

#### Pagination

**Плохо:**
```kotlin
// Загружает все данные в память
val allOrders = orderRepository.findAll()
val page = allOrders.drop(offset).take(limit)
```

**Хорошо:**
```kotlin
// Использует LIMIT/OFFSET в SQL
fun getOrders(page: Int, size: Int): Page<Order> {
    return orderRepository.findAll(
        PageRequest.of(page, size, Sort.by("createdAt").descending())
    )
}
```

**Еще лучше (Cursor-based pagination):**
```kotlin
// Использует WHERE id > lastId вместо OFFSET
fun getOrdersAfter(lastId: String, limit: Int): List<Order> {
    return orderRepository.findByIdGreaterThan(lastId, PageRequest.of(0, limit))
}
```

---

### 3. Denormalization

**Проблема:** Сложные JOIN медленные

**Решение:** Денормализация данных (дублирование)

**Пример:**
```kotlin
// Нормализованная структура
Order {
    id: String
    userId: String  // FK
    items: List<OrderItem>
}

User {
    id: String
    name: String
    email: String
}

// Денормализованная (для быстрого чтения)
OrderView {
    id: String
    userId: String
    userName: String  // Дублируется из User
    userEmail: String  // Дублируется из User
    items: List<OrderItem>
}
```

**Обновление при изменении:**
```kotlin
@EventListener
fun onUserUpdated(event: UserUpdatedEvent) {
    // Обновляем все OrderView с этим пользователем
    orderViewRepository.updateUserName(event.userId, event.newName)
}
```

---

## Rate Limiting

**Проблема:** Защита от злоупотреблений и DDoS

**Решение:** Ограничение количества запросов

### Token Bucket Algorithm

```
Bucket с токенами (capacity = 100)
Каждый запрос = 1 токен
Каждую секунду добавляется 10 токенов

Если токенов нет → 429 Too Many Requests
```

**Пример (Redis):**
```kotlin
@Service
class RateLimiter {
    @Autowired
    lateinit var redisTemplate: RedisTemplate<String, String>
    
    fun isAllowed(userId: String, limit: Int, windowSeconds: Int): Boolean {
        val key = "rate_limit:$userId"
        val current = redisTemplate.opsForValue().increment(key) ?: 1
        
        if (current == 1L) {
            // Первый запрос - устанавливаем TTL
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS)
        }
        
        return current <= limit
    }
}

@RestController
class ApiController {
    @Autowired
    lateinit var rateLimiter: RateLimiter
    
    @GetMapping("/api/data")
    fun getData(@RequestHeader("X-User-Id") userId: String): ResponseEntity<Any> {
        if (!rateLimiter.isAllowed(userId, limit = 100, windowSeconds = 60)) {
            return ResponseEntity.status(429)
                .body(mapOf("error" to "Rate limit exceeded"))
        }
        
        return ResponseEntity.ok(dataService.getData())
    }
}
```

### Sliding Window Log

**Более точный, но требует больше памяти**

```kotlin
fun isAllowed(userId: String, limit: Int, windowSeconds: Int): Boolean {
    val key = "rate_limit:$userId"
    val now = System.currentTimeMillis()
    val windowStart = now - (windowSeconds * 1000)
    
    // Удаляем старые записи
    redisTemplate.opsForZSet().removeRangeByScore(key, 0.0, windowStart.toDouble())
    
    // Считаем текущие запросы
    val count = redisTemplate.opsForZSet().count(key, windowStart.toDouble(), Double.MAX_VALUE)
    
    if (count < limit) {
        // Добавляем текущий запрос
        redisTemplate.opsForZSet().add(key, now.toString(), now.toDouble())
        redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS)
        return true
    }
    
    return false
}
```

---

## Резюме стратегий масштабирования

| Проблема | Решение | Технология |
|----------|---------|------------|
| Медленное чтение | Кеширование | Redis, Memcached |
| Медленная БД (чтение) | Read Replicas | PostgreSQL Replication |
| Большой объем данных | Sharding | Multiple DB instances |
| Большие таблицы | Partitioning | PostgreSQL Partitions |
| Статические файлы | CDN | CloudFlare, AWS CloudFront |
| Долгие операции | Message Queue | Kafka, RabbitMQ |
| Медленные запросы | Индексы | Database Indexes |
| N+1 проблема | JOIN, Eager Loading | JPA, SQL |
| Злоупотребления | Rate Limiting | Redis, Nginx |

---

**Следующий шаг:** [Практические примеры проектирования](./SYSTEM_DESIGN_EXAMPLES.md)
