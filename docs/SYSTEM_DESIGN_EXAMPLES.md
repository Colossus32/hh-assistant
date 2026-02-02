# 🎓 Практические примеры системного дизайна

## 📚 Содержание
1. [Пример 1: URL Shortener (TinyURL)](#пример-1-url-shortener)
2. [Пример 2: Chat System (WhatsApp)](#пример-2-chat-system)
3. [Пример 3: News Feed (Twitter)](#пример-3-news-feed)
4. [Шаблон решения задач](#шаблон-решения-задач)

---

## Пример 1: URL Shortener

### Задача
Спроектировать систему типа TinyURL или bit.ly

### Требования

**Функциональные:**
- Сокращение длинных URL
- Редирект по короткой ссылке
- Статистика кликов (опционально)

**Нефункциональные:**
- 100 миллионов URL в день
- 100:1 ratio (read:write) - 10 миллиардов редиректов в день
- Latency: создание < 500ms, редирект < 100ms
- Доступность: 99.9%

### Шаг 1: Оценка масштаба

**Запись (Write):**
- 100M URLs/day = 100M / 86400 = ~1,160 URLs/second
- Peak (10x): ~11,600 URLs/second

**Чтение (Read):**
- 10B redirects/day = 10B / 86400 = ~115,740 redirects/second
- Peak: ~1,157,400 redirects/second

**Storage:**
- 100M URLs/day × 365 days = 36.5B URLs/year
- Каждый URL: ~500 bytes (original URL + short URL + metadata)
- 36.5B × 500 bytes = ~18.25 TB/year
- С учетом роста: ~50 TB за 3 года

### Шаг 2: High-Level Design

```
┌─────────┐
│ Client  │
└────┬────┘
     │
     ▼
┌──────────────┐
│  Load        │
│  Balancer    │
└──────┬───────┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
┌─────┐ ┌─────┐
│ API │ │ API │
│Server│ │Server│
└──┬──┘ └──┬──┘
   │       │
   └───┬───┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
┌─────┐ ┌─────┐
│Cache│ │Cache│
│Redis│ │Redis│
└──┬──┘ └──┬──┘
   │       │
   └───┬───┘
       │
       ▼
┌──────────────┐
│   Database   │
│  (Sharded)   │
└──────────────┘
```

### Шаг 3: Детальный дизайн

#### API Endpoints

```
POST /api/v1/shorten
Request: { "longUrl": "https://example.com/very/long/url" }
Response: { "shortUrl": "https://tiny.ly/abc123" }

GET /abc123
Response: 301 Redirect to original URL
```

#### Генерация короткого URL

**Вариант 1: Hash-based**
```kotlin
fun generateShortUrl(longUrl: String): String {
    // MD5 hash
    val hash = md5(longUrl)
    
    // Берем первые 7 символов
    val shortCode = hash.substring(0, 7)
    
    // Проверяем коллизии
    if (urlRepository.existsByShortCode(shortCode)) {
        // Добавляем соль и пробуем снова
        return generateShortUrl(longUrl + System.currentTimeMillis())
    }
    
    return "https://tiny.ly/$shortCode"
}
```

**Вариант 2: Counter-based (лучше для масштабирования)**
```kotlin
// Используем Redis для генерации уникальных ID
fun generateShortUrl(longUrl: String): String {
    // Получаем следующий ID из Redis
    val id = redisTemplate.opsForValue().increment("url_counter")
    
    // Конвертируем в base62 (a-z, A-Z, 0-9)
    val shortCode = base62Encode(id)
    
    return "https://tiny.ly/$shortCode"
}

fun base62Encode(num: Long): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    var n = num
    val result = StringBuilder()
    
    while (n > 0) {
        result.append(chars[(n % 62).toInt()])
        n /= 62
    }
    
    return result.reverse().toString()
}
```

#### База данных

**Схема:**
```sql
CREATE TABLE urls (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(7) UNIQUE NOT NULL,
    long_url TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP,
    click_count BIGINT DEFAULT 0
);

CREATE INDEX idx_short_code ON urls(short_code);
```

**Sharding:**
- Shard по `short_code` (hash-based)
- 4 шарда для начала

#### Кеширование

**Стратегия:**
- Cache-Aside для редиректов
- TTL: 1 час (URL редко меняются)
- LRU eviction

```kotlin
@Service
class UrlService {
    fun getLongUrl(shortCode: String): String {
        // 1. Проверяем кеш
        val cached = redisTemplate.opsForValue().get("url:$shortCode")
        if (cached != null) {
            return cached
        }
        
        // 2. Запрашиваем из БД
        val url = urlRepository.findByShortCode(shortCode)
            ?: throw UrlNotFoundException()
        
        // 3. Сохраняем в кеш
        redisTemplate.opsForValue().set("url:$shortCode", url.longUrl, 1, TimeUnit.HOURS)
        
        return url.longUrl
    }
}
```

#### Масштабирование

**Чтение (Redirect):**
- Read replicas (3-4 реплики)
- Кеширование (Redis cluster)
- CDN для статики

**Запись (Create):**
- Sharded database
- Counter в Redis для генерации ID

**Оценка серверов:**
- API Servers: 10-15 (для 1M+ RPS)
- Redis Cluster: 6 nodes (3 master, 3 replica)
- Database: 4 shards × 2 (master + replica) = 8 servers

#### Детальная оценка серверов

**1. API Servers (10-15 серверов)**

**Расчет:**
```
Peak RPS для чтения: ~1,157,400 redirects/second
Peak RPS для записи: ~11,600 URLs/second
Total Peak RPS: ~1,169,000 requests/second

Производительность одного API сервера:
- Современный сервер (8 CPU, 16GB RAM): ~100,000 RPS
- С учетом overhead (load balancer, network): ~80,000 RPS на сервер
- С учетом резерва (70% utilization): ~56,000 RPS на сервер

Необходимо серверов: 1,169,000 / 56,000 = ~21 сервер

НО! У нас есть кеширование:
- Cache hit rate: ~80% (типично для URL shortener)
- Только 20% запросов идут в БД
- Реальные запросы к API: 1,169,000 × 0.2 = ~234,000 RPS

С учетом кеша: 234,000 / 56,000 = ~4-5 серверов

Плюс резерв для:
- Пиковых нагрузок (Black Friday, вирусные ссылки)
- Отказоустойчивости (если 1-2 сервера упадут)
- Будущего роста

Итого: 10-15 серверов
```

**2. Redis Cluster (6 nodes: 3 master + 3 replica)**

**Зачем Redis:**
- Кеширование URL для редиректов (основная нагрузка)
- Counter для генерации уникальных ID
- Rate limiting (защита от злоупотреблений)

**Расчет:**
```
Cache operations:
- Reads: ~1,157,400 reads/second (80% cache hits)
- Writes: ~11,600 writes/second (новые URL + cache updates)

Производительность Redis:
- Один Redis node: ~100,000 ops/second
- Redis Cluster (3 masters): 3 × 100,000 = 300,000 ops/second

Нагрузка: ~1,169,000 ops/second
С учетом пиков: нужно минимум 4-5 masters

Но Redis Cluster работает так:
- 3 masters обрабатывают запросы
- 3 replicas для отказоустойчивости (каждый master имеет replica)
- Если master упадет, replica становится master

Итого: 6 nodes (3 master + 3 replica)
```

**Redis Cluster Architecture:**
```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│  Redis Cluster  │
│   Coordinator   │
└────────┬────────┘
         │
    ┌────┼────┐
    │    │    │
    ▼    ▼    ▼
┌────┐ ┌────┐ ┌────┐
│ M1 │ │ M2 │ │ M3 │  Masters (обрабатывают запросы)
└──┬─┘ └──┬─┘ └──┬─┘
   │      │      │
   ▼      ▼      ▼
┌────┐ ┌────┐ ┌────┐
│ R1 │ │ R2 │ │ R3 │  Replicas (резервные копии)
└────┘ └────┘ └────┘

Если M1 упадет → R1 автоматически становится master
```

**3. Database: 4 shards × 2 (master + replica) = 8 servers**

**Важно понимать:** Здесь НЕ один мастер на все шарды, а **каждый шард имеет свой master и replica!**

**Архитектура шардирования:**
```
                    Write Requests
                         │
                         ▼
              ┌──────────────────┐
              │  Shard Router    │
              │  (Application)    │
              └────────┬──────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
   ┌────────┐    ┌────────┐    ┌────────┐
   │ Shard 1│    │ Shard 2│    │ Shard 3│    │ Shard 4│
   │        │    │        │    │        │    │        │
   │ Master │    │ Master │    │ Master │    │ Master │
   │   DB1  │    │   DB2  │    │   DB3  │    │   DB4  │
   └───┬────┘    └───┬────┘    └───┬────┘    └───┬────┘
       │             │             │             │
       │ Replication │ Replication │ Replication │ Replication
       │             │             │             │
       ▼             ▼             ▼             ▼
   ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐
   │Replica1│    │Replica2│    │Replica3│    │Replica4│
   │   DB1  │    │   DB2  │    │   DB3  │    │   DB4  │
   └────────┘    └────────┘    └────────┘    └────────┘
       │             │             │             │
       └─────────────┴─────────────┴─────────────┘
                       │
              Read Requests (load balanced)
```

**Как это работает:**

**1. Шардирование (Sharding):**
```kotlin
// Приложение определяет, в какой шард писать
fun getShard(shortCode: String): Int {
    // Hash-based sharding
    val hash = shortCode.hashCode()
    return Math.abs(hash) % 4  // 4 шарда
}

// Запись идет в master соответствующего шарда
fun saveUrl(url: Url) {
    val shard = getShard(url.shortCode)
    val dataSource = shardDataSources[shard]  // Master DB этого шарда
    urlRepository.save(url, dataSource)
}
```

**2. Репликация внутри каждого шарда:**
```
Shard 1:
  Master DB1 ──► Replication ──► Replica DB1
  (принимает запись)              (только чтение, backup)

Shard 2:
  Master DB2 ──► Replication ──► Replica DB2

Shard 3:
  Master DB3 ──► Replication ──► Replica DB3

Shard 4:
  Master DB4 ──► Replication ──► Replica DB4
```

**3. Чтение:**
```kotlin
// Чтение может идти на replica (быстрее, разгружает master)
fun getUrl(shortCode: String): Url {
    val shard = getShard(shortCode)
    
    // Пробуем сначала replica (read replica)
    try {
        return urlRepository.findByShortCode(shortCode, shardReplicas[shard])
    } catch (e: Exception) {
        // Если replica недоступна, идем на master
        return urlRepository.findByShortCode(shortCode, shardMasters[shard])
    }
}
```

**Расчет количества серверов:**
```
Нагрузка на БД (после кеша):
- Writes: ~11,600 writes/second (20% от peak, остальное в кеш)
- Reads: ~234,000 reads/second (20% от peak)

Распределение по шардам (равномерно):
- Writes per shard: 11,600 / 4 = ~2,900 writes/second
- Reads per shard: 234,000 / 4 = ~58,500 reads/second

Производительность PostgreSQL:
- Master: ~10,000 writes/second, ~50,000 reads/second
- Replica: ~100,000 reads/second (только чтение, быстрее)

Нагрузка на один шард:
- Master: 2,900 writes + часть reads = OK
- Replica: 58,500 reads = OK (replica быстрее для чтения)

Итого: 4 шарда × 2 (master + replica) = 8 серверов
```

**Преимущества такой архитектуры:**

1. **Масштабирование записи:**
   - Каждый master обрабатывает только 1/4 записей
   - Можно добавить больше шардов при росте

2. **Масштабирование чтения:**
   - Чтение распределено между master и replica
   - Можно добавить больше replicas на шард

3. **Отказоустойчивость:**
   - Если master шарда упадет, replica становится master
   - Остальные шарды продолжают работать

4. **Изоляция:**
   - Проблема в одном шарде не влияет на другие

**Важно:** Это НЕ multi-master (где все masters принимают запись в одну БД). Это **sharded database**, где каждый шард - это отдельная БД со своим master и replica.

**Multi-Master vs Sharded Database:**

```
Multi-Master (НЕ наш случай):
┌────────┐    ┌────────┐
│Master 1│◄──►│Master 2│  Оба masters работают с ОДНИМИ данными
└────────┘    └────────┘  (репликация в обе стороны)
     │            │
     └─────┬──────┘
           │
      Same Data

Sharded Database (наш случай):
┌────────┐    ┌────────┐
│Master 1│    │Master 2│  Каждый master работает с РАЗНЫМИ данными
│Shard 1 │    │Shard 2 │  (разные шарды)
└────────┘    └────────┘
Different Data  Different Data
```

---

## Пример 2: Chat System

### Задача
Спроектировать систему обмена сообщениями типа WhatsApp

### Требования

**Функциональные:**
- Отправка сообщений (1-to-1, group)
- Доставка сообщений
- Онлайн статус пользователей
- История сообщений

**Нефункциональные:**
- 500 миллионов пользователей
- 50 миллионов активных в день
- 100 сообщений на пользователя в день = 5 миллиардов сообщений/день
- Latency: доставка < 100ms
- Доступность: 99.9%

### Шаг 1: Оценка масштаба

**Сообщения:**
- 5B messages/day = ~58,000 messages/second
- Peak (5x): ~290,000 messages/second

**Storage:**
- 5B messages/day × 365 = 1.825 trillion messages/year
- Каждое сообщение: ~100 bytes
- 1.825T × 100 bytes = ~182.5 TB/year

### Шаг 2: High-Level Design

```
┌─────────┐
│ Mobile  │
│  App    │
└────┬────┘
     │ WebSocket
     ▼
┌──────────────┐
│  Chat        │
│  Server      │
│  (WebSocket) │
└──────┬───────┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
┌─────┐ ┌─────┐
│Msg  │ │Presence│
│Queue│ │Service │
│(Kafka)│ │(Redis)│
└──┬──┘ └──┬──┘
   │       │
   └───┬───┘
       │
       ▼
┌──────────────┐
│   Message    │
│   Storage    │
│  (Cassandra) │
└──────────────┘
```

### Шаг 3: Детальный дизайн

#### WebSocket Connection

**Проблема:** HTTP не подходит для real-time

**Решение:** WebSocket для двусторонней связи

```kotlin
@ServerEndpoint("/chat/{userId}")
class ChatEndpoint {
    private val sessions = ConcurrentHashMap<String, Session>()
    
    @OnOpen
    fun onOpen(session: Session, @PathParam("userId") userId: String) {
        sessions[userId] = session
        presenceService.markOnline(userId)
    }
    
    @OnMessage
    fun onMessage(message: String, @PathParam("userId") userId: String) {
        val chatMessage = parseMessage(message)
        
        // Отправляем в очередь
        kafkaProducer.send("messages", chatMessage)
        
        // Сохраняем в БД
        messageRepository.save(chatMessage)
    }
    
    @OnClose
    fun onClose(@PathParam("userId") userId: String) {
        sessions.remove(userId)
        presenceService.markOffline(userId)
    }
    
    fun sendMessage(userId: String, message: ChatMessage) {
        sessions[userId]?.asyncRemote?.sendText(message.toJson())
    }
}
```

#### Message Queue (Kafka)

**Зачем:** Асинхронная обработка, масштабирование

```
Producer (Chat Server) ──► Kafka Topic "messages" ──► Consumers
                                                          │
                                                          ├─► Notification Service
                                                          ├─► Analytics Service
                                                          └─► Message Storage
```

**Partitioning:**
- По `recipientId` - все сообщения для пользователя в одной партиции
- Гарантирует порядок доставки

```kotlin
@Service
class MessageProducer {
    fun sendMessage(message: ChatMessage) {
        // Партиционируем по recipientId
        kafkaTemplate.send("messages", message.recipientId, message.toJson())
    }
}

@KafkaListener(topics = ["messages"])
fun processMessage(message: String) {
    val chatMessage = parseMessage(message)
    
    // Сохраняем в БД
    messageRepository.save(chatMessage)
    
    // Отправляем получателю если онлайн
    if (presenceService.isOnline(chatMessage.recipientId)) {
        chatEndpoint.sendMessage(chatMessage.recipientId, chatMessage)
    }
}
```

#### Message Storage

**Выбор БД:** Cassandra (write-heavy, масштабируется горизонтально)

**Схема:**
```sql
CREATE TABLE messages (
    chat_id TEXT,           -- user1_user2 или group_id
    message_id TIMEUUID,
    sender_id TEXT,
    content TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY (chat_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

**Sharding:**
- По `chat_id` (hash)
- Равномерное распределение

#### Presence Service (Онлайн статус)

**Redis для быстрого доступа:**
```kotlin
@Service
class PresenceService {
    @Autowired
    lateinit var redisTemplate: RedisTemplate<String, String>
    
    fun markOnline(userId: String) {
        redisTemplate.opsForValue().set("presence:$userId", "online", 60, TimeUnit.SECONDS)
    }
    
    fun markOffline(userId: String) {
        redisTemplate.delete("presence:$userId")
    }
    
    fun isOnline(userId: String): Boolean {
        return redisTemplate.hasKey("presence:$userId")
    }
    
    // Heartbeat каждые 30 секунд
    @Scheduled(fixedRate = 30000)
    fun updateHeartbeat(userId: String) {
        redisTemplate.expire("presence:$userId", 60, TimeUnit.SECONDS)
    }
}
```

#### Доставка сообщений

**Сценарий 1: Получатель онлайн**
```
Sender ──► Chat Server ──► Kafka ──► Consumer ──► WebSocket ──► Recipient
```

**Сценарий 2: Получатель офлайн**
```
Sender ──► Chat Server ──► Kafka ──► Consumer ──► Message Storage
                                                      │
Recipient ──► Chat Server ──► Fetch Messages ──► Message Storage
```

**Pull при подключении:**
```kotlin
@OnOpen
fun onOpen(session: Session, @PathParam("userId") userId: String) {
    // Загружаем непрочитанные сообщения
    val unreadMessages = messageRepository.findUnread(userId)
    unreadMessages.forEach { message ->
        session.asyncRemote.sendText(message.toJson())
    }
}
```

---

## Пример 3: News Feed

### Задача
Спроектировать систему ленты новостей типа Twitter

### Требования

**Функциональные:**
- Публикация постов
- Лента новостей (timeline) - посты от подписок
- Лайки, комментарии
- Подписки/отписки

**Нефункциональные:**
- 300 миллионов пользователей
- 100 миллионов активных в день
- 500 миллионов постов в день
- 23 миллиарда чтений ленты в день (23B / 100M = 230 reads/user/day)
- Latency: чтение ленты < 200ms
- Доступность: 99.9%

### Шаг 1: Оценка масштаба

**Запись:**
- 500M posts/day = ~5,800 posts/second
- Peak: ~58,000 posts/second

**Чтение:**
- 23B reads/day = ~266,000 reads/second
- Peak: ~2,660,000 reads/second

**Storage:**
- 500M posts/day × 365 = 182.5B posts/year
- Каждый пост: ~1KB
- 182.5B × 1KB = ~182.5 TB/year

### Шаг 2: High-Level Design

```
┌─────────┐
│ Client  │
└────┬────┘
     │
     ▼
┌──────────────┐
│  Load        │
│  Balancer    │
└──────┬───────┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
┌─────┐ ┌─────┐
│ Feed│ │ Post│
│Service│ │Service│
└──┬──┘ └──┬──┘
   │       │
   └───┬───┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
┌─────┐ ┌─────┐
│Cache│ │Cache│
│Redis│ │Redis│
└──┬──┘ └──┬──┘
   │       │
   └───┬───┘
       │
       ▼
┌──────────────┐
│   Database   │
│  (Sharded)   │
└──────────────┘
```

### Шаг 3: Детальный дизайн

#### Подходы к генерации ленты

**Вариант 1: Fan-out on Write (Push Model)**

При публикации поста - сразу добавляем в ленты всех подписчиков

```
User A публикует пост
    │
    ▼
Для каждого подписчика User A:
    └─► Добавить пост в его timeline cache
```

**Плюсы:**
- Быстрое чтение (просто берем из кеша)
- Низкая latency

**Минусы:**
- Медленная запись (если 1M подписчиков - 1M записей)
- Много дублирования данных

**Вариант 2: Fan-out on Read (Pull Model)**

При чтении ленты - собираем посты от подписок

```
User запрашивает ленту
    │
    ▼
Для каждой подписки:
    └─► Получить последние посты
    │
    ▼
Объединить и отсортировать
```

**Плюсы:**
- Быстрая запись
- Нет дублирования

**Минусы:**
- Медленное чтение (много запросов)
- Высокая latency

**Вариант 3: Hybrid (Рекомендуется)**

- **Celebrities (много подписчиков):** Fan-out on Read
- **Обычные пользователи:** Fan-out on Write

```kotlin
@Service
class FeedService {
    private val CELEBRITY_THRESHOLD = 1_000_000
    
    fun publishPost(post: Post) {
        val author = userService.getUser(post.authorId)
        
        if (author.followerCount > CELEBRITY_THRESHOLD) {
            // Celebrity - не фан-аутим, только сохраняем
            postRepository.save(post)
        } else {
            // Обычный пользователь - фан-аутим
            fanOutPost(post)
        }
    }
    
    private fun fanOutPost(post: Post) {
        val followers = followService.getFollowers(post.authorId)
        
        followers.forEach { followerId ->
            // Добавляем в timeline cache
            redisTemplate.opsForList().leftPush(
                "timeline:$followerId",
                post.toJson()
            )
            
            // Ограничиваем размер (храним последние 1000 постов)
            redisTemplate.opsForList().trim("timeline:$followerId", 0, 999)
        }
    }
    
    fun getFeed(userId: String, page: Int, size: Int): List<Post> {
        // 1. Берем из кеша (fan-out on write посты)
        val cachedPosts = redisTemplate.opsForList().range(
            "timeline:$userId",
            page * size,
            (page + 1) * size - 1
        )?.map { parsePost(it) } ?: emptyList()
        
        // 2. Добавляем посты от celebrities (fan-out on read)
        val following = followService.getFollowing(userId)
        val celebrities = following.filter { 
            userService.getUser(it).followerCount > CELEBRITY_THRESHOLD 
        }
        
        val celebrityPosts = celebrities.flatMap { celebId ->
            postRepository.findRecentByAuthor(celebId, 10)
        }
        
        // 3. Объединяем и сортируем
        return (cachedPosts + celebrityPosts)
            .sortedByDescending { it.createdAt }
            .take(size)
    }
}
```

#### База данных

**Posts Table:**
```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0
);

CREATE INDEX idx_author_created ON posts(author_id, created_at DESC);
CREATE INDEX idx_created ON posts(created_at DESC);
```

**Timeline Cache (Redis):**
```
Key: timeline:{userId}
Value: List of post JSONs (sorted by time)
TTL: 7 days
Max size: 1000 posts
```

**Sharding:**
- Posts: по `author_id` (hash)
- Timeline cache: по `userId`

#### Оптимизации

**1. Pre-computation для популярных постов:**
```kotlin
// Топ посты за день - предрассчитываем
@Scheduled(cron = "0 0 * * * ?")  // Каждый час
fun updateTrendingPosts() {
    val trending = postRepository.findTopPostsByLikes(
        LocalDateTime.now().minusDays(1),
        100
    )
    redisTemplate.opsForValue().set("trending:posts", trending.toJson())
}
```

**2. Materialized View для статистики:**
```sql
CREATE MATERIALIZED VIEW user_stats AS
SELECT 
    author_id,
    COUNT(*) as post_count,
    SUM(like_count) as total_likes,
    AVG(like_count) as avg_likes
FROM posts
GROUP BY author_id;

-- Обновляем периодически
REFRESH MATERIALIZED VIEW user_stats;
```

---

## Шаблон решения задач

### 1. Уточнение требований

**Функциональные:**
- [ ] Основные функции
- [ ] Edge cases

**Нефункциональные:**
- [ ] Масштаб (пользователи, запросы)
- [ ] Latency требования
- [ ] Availability требования
- [ ] Consistency требования

### 2. Оценка масштаба

- [ ] RPS (Requests Per Second)
- [ ] Storage (сколько данных)
- [ ] Bandwidth (трафик)
- [ ] Memory (кеш)

### 3. High-Level Design

- [ ] Основные компоненты
- [ ] Взаимодействие между компонентами
- [ ] API endpoints

### 4. Детальный дизайн

- [ ] База данных (схема, индексы, шардинг)
- [ ] Кеширование (что, где, стратегия)
- [ ] Load balancing
- [ ] Масштабирование (горизонтальное/вертикальное)

### 5. Оптимизация

- [ ] Узкие места (bottlenecks)
- [ ] Single points of failure
- [ ] Как улучшить производительность
- [ ] Trade-offs

### 6. Дополнительные вопросы

- [ ] Мониторинг
- [ ] Логирование
- [ ] Безопасность
- [ ] Backup и recovery

---

## Полезные формулы

**RPS (Requests Per Second):**
```
RPS = Total Requests / Seconds in Day
Peak RPS = Average RPS × Peak Factor (обычно 3-5x)
```

**Storage:**
```
Storage = Records per Day × Days × Size per Record
With Growth = Storage × (1 + Growth Rate) ^ Years
```

**Servers:**
```
Servers Needed = Peak RPS / (RPS per Server × Utilization)
```

**Cache Hit Rate:**
```
Cache Hit Rate = Cache Hits / (Cache Hits + Cache Misses)
Target: > 80%
```

---

**Теперь вы готовы решать задачи! Попробуйте применить этот шаблон к задачам из первого сообщения.** 🚀