# NoSQL базы данных для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## MongoDB

### КЕЙС #1 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Когда использовать embedding vs referencing в MongoDB? Как это влияет на производительность?

**ОТВЕТ:**
**Embedding**: хранение связанных данных в одном документе
**Referencing**: ссылки между документами (как FK в SQL)

**Правило выбора:**
- Embed: данные читаются/обновляются вместе, 1:1 или 1:few
- Reference: данные независимы, many:many, большой размер

**ПОЧЕМУ ЭТО ВАЖНО:**
- Embedding: 1 запрос vs Reference: N запросов
- Embedding: атомарность vs Reference: нет транзакций (до MongoDB 4.0)
- Embedding: лимит 16MB на документ

**ПРИМЕР КОДА:**
```kotlin
// EMBEDDING: данные в одном документе (денормализация)
@Document(collection = "users")
data class UserEmbedded(
    @Id val id: ObjectId = ObjectId(),
    val name: String,
    val email: String,
    
    val address: Address,  // Вложенный документ
    
    val orders: List<Order> = emptyList()  // Вложенный массив
)

data class Address(
    val street: String,
    val city: String,
    val zipCode: String,
    val country: String
)

data class Order(
    val orderId: String,
    val total: BigDecimal,
    val createdAt: LocalDateTime
)

// MongoDB document:
// {
//   "_id": ObjectId("..."),
//   "name": "John",
//   "email": "john@example.com",
//   "address": {
//     "street": "123 Main St",
//     "city": "New York",
//     "country": "USA"
//   },
//   "orders": [
//     {"orderId": "ORD-001", "total": 100.00, "createdAt": "2026-01-15"},
//     {"orderId": "ORD-002", "total": 200.00, "createdAt": "2026-01-20"}
//   ]
// }

// ✅ Плюсы: 
//   - 1 запрос загружает всё
//   - Атомарные операции (update address + orders в одной транзакции)
// ❌ Минусы: 
//   - Дублирование (если Order нужен отдельно)
//   - Ограничение 16MB на документ
//   - Растущие массивы (orders может стать огромным)

// REFERENCING: ссылки между документами (нормализация)
@Document(collection = "users")
data class UserReferenced(
    @Id val id: ObjectId = ObjectId(),
    val name: String,
    val email: String,
    val addressId: ObjectId  // Ссылка на Address
)

@Document(collection = "addresses")
data class AddressDocument(
    @Id val id: ObjectId = ObjectId(),
    val street: String,
    val city: String,
    val country: String
)

@Document(collection = "orders")
data class OrderDocument(
    @Id val id: ObjectId = ObjectId(),
    val userId: ObjectId,  // Ссылка на User
    val total: BigDecimal,
    val createdAt: LocalDateTime
)

// ✅ Плюсы: 
//   - Нет дублирования данных
//   - Независимые обновления
//   - Нет лимита 16MB
// ❌ Минусы: 
//   - Несколько запросов (нет JOIN в MongoDB <3.2)
//   - Нет FK constraints (можно удалить User, оставив Orders)

// Гибридный подход (BEST PRACTICE)
@Document(collection = "orders")
data class OrderHybrid(
    @Id val id: ObjectId = ObjectId(),
    val userId: ObjectId,  // Reference
    
    // Денормализация часто используемых полей
    val userName: String,
    val userEmail: String,
    
    val total: BigDecimal,
    val items: List<OrderItem>,  // Embedded
    val createdAt: LocalDateTime
)

// Обновление денормализованных данных
@Service
class UserService(
    private val userRepository: MongoRepository<User>,
    private val orderRepository: MongoRepository<Order>
) {
    
    @Transactional
    fun updateUserName(userId: ObjectId, newName: String) {
        // 1. Обновляем User
        val user = userRepository.findById(userId).orElseThrow()
        user.name = newName
        userRepository.save(user)
        
        // 2. Обновляем денормализованные данные в Orders
        val query = Query(Criteria.where("userId").`is`(userId))
        val update = Update().set("userName", newName)
        
        mongoTemplate.updateMulti(query, update, Order::class.java)
        // Eventual consistency: orders обновятся асинхронно
    }
}

// Aggregation pipeline с $lookup (JOIN)
db.orders.aggregate([
    {
        $lookup: {
            from: "users",
            localField: "userId",
            foreignField: "_id",
            as: "user"
        }
    },
    {
        $unwind: "$user"
    },
    {
        $match: {
            "createdAt": { $gte: ISODate("2026-01-01") }
        }
    }
])
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #2 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работают индексы в MongoDB? Что такое compound index и covered query?

**ОТВЕТ:**
**MongoDB индексы** похожи на PostgreSQL B-tree.

**Compound index**: индекс по нескольким полям
**Covered query**: все данные из индекса (без чтения документа)

**ПРИМЕР КОДА:**
```kotlin
@Document(collection = "products")
@CompoundIndex(name = "category_price_idx", def = "{'category': 1, 'price': -1}")
data class Product(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val name: String,
    val category: String,
    val price: BigDecimal,
    val description: String,
    val stock: Int
)

// Создание индексов
db.products.createIndex({ category: 1, price: -1 })
// 1 = ascending, -1 = descending

// Covered query: все поля из индекса
db.products.find(
    { category: "Electronics", price: { $gte: 100 } },
    { _id: 0, category: 1, price: 1 }  // Projection: только индексированные поля
)

// COVERED: все данные из индекса
// PLAN: Index Scan (IXSCAN) без обращения к документу

db.products.find(
    { category: "Electronics" },
    { _id: 0, category: 1, price: 1, name: 1 }  // name НЕ в индексе!
)
// PLAN: Index Scan + Fetch (нужно читать документ для name)

// Multikey index: для массивов
@Document(collection = "products")
data class Product(
    @Id val id: ObjectId,
    @Indexed val tags: List<String>  // Multikey index
)

db.products.createIndex({ tags: 1 })

// Поиск по элементу массива
db.products.find({ tags: "electronics" })
// Использует индекс!

// Text index: полнотекстовый поиск
@Document(collection = "articles")
@TextIndexed
data class Article(
    @Id val id: ObjectId,
    @TextIndexed val title: String,
    @TextIndexed val content: String
)

db.articles.createIndex({ title: "text", content: "text" })

// Поиск
db.articles.find({ $text: { $search: "mongodb performance" } })

// Wildcard index: для динамических полей
db.products.createIndex({ "metadata.$**": 1 })

// Поиск по любому полю в metadata
db.products.find({ "metadata.color": "red" })  // Использует wildcard index
db.products.find({ "metadata.size": "large" }) // Использует тот же индекс
```
```kotlin
// Spring Data MongoDB
@Repository
interface ProductRepository : MongoRepository<Product, ObjectId> {
    
    // Использует индекс category_price_idx
    fun findByCategoryAndPriceGreaterThan(
        category: String,
        price: BigDecimal
    ): List<Product>
    
    // Text search
    @Query("{ \$text: { \$search: ?0 } }")
    fun fullTextSearch(searchText: String): List<Product>
}

// Explain для MongoDB query
@Service
class ProductService(
    private val mongoTemplate: MongoTemplate
) {
    
    fun findProductsWithExplain(category: String): List<Product> {
        val query = Query(Criteria.where("category").`is`(category))
        
        // Explain
        val explain = mongoTemplate.executeCommand("""
            {
                explain: {
                    find: "products",
                    filter: { category: "$category" }
                }
            }
        """)
        
        logger.info("Query plan: $explain")
        
        return mongoTemplate.find(query, Product::class.java)
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #3 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Aggregation Pipeline в MongoDB? Когда использовать вместо find()?

**ОТВЕТ:**
**Aggregation Pipeline**: многоэтапная обработка данных (как Stream API).

**Используется для:**
- Группировки (GROUP BY)
- JOIN ($lookup)
- Сложные вычисления
- Трансформации данных

**ПРИМЕР КОДА:**
```kotlin
// Задача: топ-10 пользователей по сумме заказов

// ПЛОХО: загрузить всё в память
@Service
class UserStatsServiceBad(
    private val orderRepository: MongoRepository<Order>
) {
    
    fun getTopUsers(): List<UserStats> {
        val orders = orderRepository.findAll()  // Загружает ВСЕ заказы!
        
        return orders
            .groupBy { it.userId }
            .map { (userId, userOrders) ->
                UserStats(
                    userId = userId,
                    orderCount = userOrders.size,
                    totalSpent = userOrders.sumOf { it.total }
                )
            }
            .sortedByDescending { it.totalSpent }
            .take(10)
    }
}

// ХОРОШО: aggregation pipeline (в БД)
db.orders.aggregate([
    // 1. Группировка по user_id
    {
        $group: {
            _id: "$userId",
            orderCount: { $sum: 1 },
            totalSpent: { $sum: "$total" }
        }
    },
    // 2. Сортировка
    {
        $sort: { totalSpent: -1 }
    },
    // 3. Лимит
    {
        $limit: 10
    },
    // 4. JOIN с users
    {
        $lookup: {
            from: "users",
            localField: "_id",
            foreignField: "_id",
            as: "user"
        }
    },
    {
        $unwind: "$user"
    },
    // 5. Проекция
    {
        $project: {
            userId: "$_id",
            userName: "$user.name",
            userEmail: "$user.email",
            orderCount: 1,
            totalSpent: 1
        }
    }
])

// Spring Data MongoDB
@Repository
interface OrderRepository : MongoRepository<Order, ObjectId> {
    
    @Aggregation("""
        [
            { $match: { createdAt: { $gte: ?0 } } },
            { $group: {
                _id: '$userId',
                orderCount: { $sum: 1 },
                totalSpent: { $sum: '$total' },
                avgOrder: { $avg: '$total' }
            }},
            { $sort: { totalSpent: -1 } },
            { $limit: ?1 }
        ]
    """)
    fun getTopUsersStats(
        since: LocalDateTime,
        limit: Int
    ): List<UserStatsAggregation>
}

interface UserStatsAggregation {
    val userId: ObjectId
    val orderCount: Int
    val totalSpent: BigDecimal
    val avgOrder: BigDecimal
}

// Сложный pipeline с несколькими JOIN
@Aggregation("""
    [
        { $match: { status: 'COMPLETED' } },
        { $lookup: {
            from: 'users',
            localField: 'userId',
            foreignField: '_id',
            as: 'user'
        }},
        { $unwind: '$user' },
        { $lookup: {
            from: 'products',
            localField: 'items.productId',
            foreignField: '_id',
            as: 'productDetails'
        }},
        { $addFields: {
            itemsWithDetails: {
                $map: {
                    input: '$items',
                    as: 'item',
                    in: {
                        $mergeObjects: [
                            '$$item',
                            {
                                product: {
                                    $arrayElemAt: [
                                        {
                                            $filter: {
                                                input: '$productDetails',
                                                cond: { $eq: ['$$this._id', '$$item.productId'] }
                                            }
                                        },
                                        0
                                    ]
                                }
                            }
                        ]
                    }
                }
            }
        }},
        { $project: {
            orderId: '$_id',
            userName: '$user.name',
            items: '$itemsWithDetails',
            total: 1
        }}
    ]
""")
fun findCompletedOrdersWithDetails(): List<OrderWithDetails>

// Faceted search (несколько агрегаций)
db.products.aggregate([
    {
        $facet: {
            // Фасет 1: статистика по категориям
            byCategory: [
                { $group: { _id: "$category", count: { $sum: 1 } } },
                { $sort: { count: -1 } }
            ],
            // Фасет 2: ценовые диапазоны
            priceRanges: [
                {
                    $bucket: {
                        groupBy: "$price",
                        boundaries: [0, 100, 500, 1000, 5000],
                        default: "Other",
                        output: { count: { $sum: 1 } }
                    }
                }
            ],
            // Фасет 3: топ продуктов
            topProducts: [
                { $sort: { salesCount: -1 } },
                { $limit: 10 }
            ]
        }
    }
])
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #4 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работают транзакции в MongoDB? В чём отличие от PostgreSQL?

**ОТВЕТ:**
**MongoDB 4.0+**: поддержка multi-document ACID транзакций

**Отличия от PostgreSQL:**
- Только для Replica Set / Sharded cluster
- Медленнее чем single-document операции
- Timeout по умолчанию: 60 секунд

**ПРИМЕР КОДА:**
```kotlin
// Single-document: атомарно БЕЗ транзакции
@Service
class OrderService(
    private val mongoTemplate: MongoTemplate
) {
    
    fun createOrder(userId: ObjectId, items: List<OrderItem>) {
        val order = Order(
            userId = userId,
            items = items,
            total = items.sumOf { it.price * it.quantity.toBigDecimal() },
            status = "PENDING"
        )
        
        mongoTemplate.save(order)
        // Атомарно: либо весь document сохраняется, либо нет
    }
}

// Multi-document: нужна транзакция
@Service
class TransferService(
    private val mongoTemplate: MongoTemplate
) {
    
    fun transfer(fromAccountId: ObjectId, toAccountId: ObjectId, amount: BigDecimal) {
        // Начинаем сессию
        val session = mongoTemplate.mongoDatabaseFactory.session
        
        session.startTransaction()
        
        try {
            // Операция 1: списание
            val query1 = Query(Criteria.where("_id").`is`(fromAccountId))
            val update1 = Update().inc("balance", -amount)
            mongoTemplate.updateFirst(query1, update1, Account::class.java)
            
            // Операция 2: зачисление
            val query2 = Query(Criteria.where("_id").`is`(toAccountId))
            val update2 = Update().inc("balance", amount)
            mongoTemplate.updateFirst(query2, update2, Account::class.java)
            
            session.commitTransaction()
            
        } catch (e: Exception) {
            session.abortTransaction()
            throw e
        } finally {
            session.close()
        }
    }
}

// Spring @Transactional работает с MongoDB!
@Service
class OrderTransactionalService(
    private val orderRepository: MongoRepository<Order>,
    private val inventoryRepository: MongoRepository<Inventory>
) {
    
    @Transactional  // MongoDB транзакция
    fun createOrderWithInventoryUpdate(orderDto: OrderDto) {
        // 1. Создаём заказ
        val order = orderRepository.save(orderDto.toEntity())
        
        // 2. Обновляем inventory
        orderDto.items.forEach { item ->
            val query = Query(Criteria.where("productId").`is`(item.productId))
            val update = Update().inc("stock", -item.quantity)
            
            val result = mongoTemplate.updateFirst(query, update, Inventory::class.java)
            
            if (result.modifiedCount == 0L) {
                throw IllegalStateException("Failed to update inventory")
                // Автоматический rollback!
            }
        }
    }
}

// Конфигурация для транзакций
@Configuration
class MongoConfig {
    
    @Bean
    fun transactionManager(factory: MongoDatabaseFactory): MongoTransactionManager {
        return MongoTransactionManager(factory)
    }
}

// Ограничения транзакций MongoDB
// - Максимум 16MB операций в одной транзакции
// - Timeout: 60 секунд (configurable)
// - Работает только с Replica Set (НЕ standalone)
```
───────────────────────────────────────────────────────────────────────────────

## Redis

### КЕЙС #5 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Какие структуры данных есть в Redis? Когда использовать String vs Hash vs Sorted Set?

**ОТВЕТ:**
**5 основных структур:**
1. **String**: простые значения, счётчики, кэш
2. **Hash**: объекты с полями
3. **List**: очереди, логи, стеки
4. **Set**: уникальные значения, tags
5. **Sorted Set**: рейтинги, временные ряды

**ПОЧЕМУ ЭТО ВАЖНО:**
- String для простого кэша (быстро)
- Hash для объектов (можно обновлять отдельные поля)
- Sorted Set для leaderboards (автоматическая сортировка)

**ПРИМЕР КОДА:**
```kotlin
// 1. STRING: простые значения, счетчики
redisTemplate.opsForValue().set("user:123:name", "John")
redisTemplate.opsForValue().set("session:abc", "user_data", Duration.ofHours(1))

// Атомарный счётчик
redisTemplate.opsForValue().increment("page:views")  // INCR
redisTemplate.opsForValue().increment("page:views", 5)  // INCRBY 5

// Distributed counter
val views = redisTemplate.opsForValue().get("page:views")?.toLong() ?: 0

// 2. HASH: объекты (альтернатива JSON String)
redisTemplate.opsForHash<String, String>().putAll(
    "user:123",
    mapOf(
        "name" to "John",
        "email" to "john@example.com",
        "age" to "30"
    )
)

// Обновление одного поля (БЕЗ чтения всего объекта)
redisTemplate.opsForHash<String, String>().put("user:123", "age", "31")

// Чтение одного поля
val email = redisTemplate.opsForHash<String, String>().get("user:123", "email")

// Increment поля в Hash
redisTemplate.opsForHash<String, Long>().increment("user:123", "loginCount", 1)

// 3. LIST: очереди, логи
// FIFO queue
redisTemplate.opsForList().rightPush("queue:tasks", "task1")
redisTemplate.opsForList().rightPush("queue:tasks", "task2")
val task = redisTemplate.opsForList().leftPop("queue:tasks")  // "task1"

// LIFO stack
redisTemplate.opsForList().rightPush("stack", "item1")
redisTemplate.opsForList().rightPush("stack", "item2")
val item = redisTemplate.opsForList().rightPop("stack")  // "item2"

// Blocking pop (для worker'ов)
val task = redisTemplate.opsForList().leftPop("queue:tasks", Duration.ofSeconds(5))
// Ждёт до 5 секунд, если очередь пуста

// Recent activity log (ограниченный размер)
redisTemplate.opsForList().leftPush("user:123:activity", "Logged in")
redisTemplate.opsForList().trim("user:123:activity", 0, 99)  // Только последние 100

// 4. SET: уникальные значения
redisTemplate.opsForSet().add("user:123:tags", "kotlin", "java", "spring")
redisTemplate.opsForSet().add("user:123:tags", "kotlin")  // Дубликат игнорируется

// Проверка наличия
val hasTags = redisTemplate.opsForSet().isMember("user:123:tags", "kotlin")  // true

// Пересечение (общие друзья)
redisTemplate.opsForSet().add("user:1:friends", "user:2", "user:3", "user:4")
redisTemplate.opsForSet().add("user:5:friends", "user:3", "user:4", "user:6")

val commonFriends = redisTemplate.opsForSet().intersect("user:1:friends", "user:5:friends")
// ["user:3", "user:4"]

// Union, Diff
val allFriends = redisTemplate.opsForSet().union("user:1:friends", "user:5:friends")
val uniqueToUser1 = redisTemplate.opsForSet().difference("user:1:friends", "user:5:friends")

// 5. SORTED SET: рейтинги, лидерборды
redisTemplate.opsForZSet().add("leaderboard", "player1", 1000.0)
redisTemplate.opsForZSet().add("leaderboard", "player2", 1500.0)
redisTemplate.opsForZSet().add("leaderboard", "player3", 800.0)

// Топ 10 (по убыванию score)
val top10 = redisTemplate.opsForZSet().reverseRange("leaderboard", 0, 9)
// ["player2", "player1", "player3"]

// Ранг игрока
val rank = redisTemplate.opsForZSet().reverseRank("leaderboard", "player1")
// 1 (второе место, индекс с 0)

// Score игрока
val score = redisTemplate.opsForZSet().score("leaderboard", "player1")
// 1000.0

// Increment score
redisTemplate.opsForZSet().incrementScore("leaderboard", "player1", 100.0)
// Новый score: 1100.0

// Range by score
val playersAbove1000 = redisTemplate.opsForZSet().rangeByScore("leaderboard", 1000.0, Double.MAX_VALUE)

// Временные ряды (score = timestamp)
val now = System.currentTimeMillis()
redisTemplate.opsForZSet().add("user:123:events", "login", now.toDouble())

// События за последний час
val hourAgo = now - 3600000
val recentEvents = redisTemplate.opsForZSet().rangeByScore(
    "user:123:events",
    hourAgo.toDouble(),
    now.toDouble()
)

// Удаление старых событий
redisTemplate.opsForZSet().removeRangeByScore("user:123:events", 0.0, hourAgo.toDouble())
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #6 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Redis persistence (RDB vs AOF)? Что выбрать для production?

**ОТВЕТ:**
**RDB (Redis Database)**: snapshot в файл (dump.rdb)
**AOF (Append Only File)**: лог всех команд

**Trade-off:**
- RDB: компактный, быстрый restore, но может потерять данные
- AOF: надёжнее, но больше размер, медленнее restore

**ПРИМЕР КОДА:**
```bash
# redis.conf: RDB (snapshot)
save 900 1      # Сохранять каждые 15 мин если >= 1 изменение
save 300 10     # Каждые 5 мин если >= 10 изменений
save 60 10000   # Каждую минуту если >= 10000 изменений

# Ручной snapshot
redis-cli BGSAVE  # Background save (не блокирует)

# AOF (append-only file)
appendonly yes
appendfsync everysec  # Flush каждую секунду (баланс)
# appendfsync always  # Каждая команда (медленно, макс надёжность)
# appendfsync no      # ОС решает (быстро, но может потерять данные)

# AOF rewrite (сжатие лога)
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb

# Hybrid: RDB + AOF (рекомендуется)
save 900 1
appendonly yes
appendfsync everysec
```
```kotlin
// Kotlin: проверка persistence
@Service
class RedisHealthService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun checkPersistence(): PersistenceInfo {
        val info = redisTemplate.execute { connection ->
            val props = connection.serverCommands().info("persistence")
            props
        }
        
        return PersistenceInfo(
            rdbLastSaveTime = info?.get("rdb_last_save_time") ?: "unknown",
            aofEnabled = info?.get("aof_enabled") == "1",
            aofLastRewriteTime = info?.get("aof_last_rewrite_time_sec") ?: "0"
        )
    }
    
    // Принудительный save
    fun forceSave() {
        redisTemplate.execute { connection ->
            connection.serverCommands().bgSave()
        }
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #7 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как реализовать distributed lock через Redis? Что такое Redlock алгоритм?

**ОТВЕТ:**
**Distributed Lock**: координация между несколькими инстансами приложения.

**Проблема простого SETNX:**
- Приложение падает → lock висит вечно
- Нет продления lock

**Redlock**: алгоритм для надёжных distributed locks.

**ПРИМЕР КОДА:**
```kotlin
// ПЛОХО: простой lock через SETNX
@Service
class SimpleLockService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun acquireLock(key: String, ttl: Duration): Boolean {
        return redisTemplate.opsForValue()
            .setIfAbsent(key, "locked", ttl) ?: false
    }
    
    fun releaseLock(key: String) {
        redisTemplate.delete(key)
    }
}

// Проблема 1: другой поток может удалить чужой lock
fun processTask() {
    if (acquireLock("task:lock", Duration.ofSeconds(10))) {
        try {
            doWork()  // Занимает 15 секунд
            // Lock истёк через 10 секунд!
            // Другой поток получил lock и тоже работает!
        } finally {
            releaseLock("task:lock")
            // Удаляем lock ДРУГОГО потока!
        }
    }
}

// ХОРОШО: Redisson (реализация Redlock)
@Service
class DistributedLockService(
    private val redissonClient: RedissonClient
) {
    
    fun <T> withLock(
        key: String,
        waitTime: Long = 5,
        leaseTime: Long = 30,
        block: () -> T
    ): T? {
        val lock = redissonClient.getLock(key)
        
        return if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
            try {
                block()
            } finally {
                if (lock.isHeldByCurrentThread) {
                    lock.unlock()
                }
            }
        } else {
            null  // Не получили lock
        }
    }
}

// Использование
@Service
class OrderProcessingService(
    private val lockService: DistributedLockService
) {
    
    fun processOrder(orderId: Long) {
        val result = lockService.withLock("order:$orderId:lock") {
            // Только ОДИН инстанс приложения обработает этот заказ
            val order = orderRepository.findById(orderId)
            processOrderLogic(order)
        }
        
        if (result == null) {
            throw LockException("Could not acquire lock for order $orderId")
        }
    }
}

// Redlock алгоритм вручную
@Service
class RedlockService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    private val lockValue = UUID.randomUUID().toString()
    
    fun acquireLock(key: String, ttlMs: Long): Boolean {
        // 1. SET key value NX PX ttl
        val script = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            else
                return 0
            end
        """
        
        val result = redisTemplate.execute(
            RedisScript.of(script, Long::class.java),
            listOf(key),
            lockValue,
            ttlMs.toString()
        )
        
        return result == 1L
    }
    
    fun releaseLock(key: String): Boolean {
        // Удаляем ТОЛЬКО если значение совпадает (наш lock)
        val script = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
        """
        
        val result = redisTemplate.execute(
            RedisScript.of(script, Long::class.java),
            listOf(key),
            lockValue
        )
        
        return result == 1L
    }
}

// Fair lock (очередь)
@Service
class FairLockService(
    private val redissonClient: RedissonClient
) {
    
    fun processFairly(key: String) {
        val fairLock = redissonClient.getFairLock(key)
        
        fairLock.lock()  // Гарантирует FIFO порядок
        try {
            // Обработка
        } finally {
            fairLock.unlock()
        }
    }
}

// Read-Write Lock
@Service
class ReadWriteLockService(
    private val redissonClient: RedissonClient
) {
    
    fun readData(key: String): String {
        val rwLock = redissonClient.getReadWriteLock("rw:$key")
        val readLock = rwLock.readLock()
        
        readLock.lock()
        try {
            return redisTemplate.opsForValue().get(key) ?: ""
        } finally {
            readLock.unlock()
        }
    }
    
    fun writeData(key: String, value: String) {
        val rwLock = redissonClient.getReadWriteLock("rw:$key")
        val writeLock = rwLock.writeLock()
        
        writeLock.lock()
        try {
            redisTemplate.opsForValue().set(key, value)
        } finally {
            writeLock.unlock()
        }
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #8 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Redis Pub/Sub? В чём отличие от Kafka? Когда использовать?

**ОТВЕТ:**
**Redis Pub/Sub**: простой message broker (in-memory)

**Отличия от Kafka:**
- Нет persistence (сообщения теряются если нет подписчиков)
- Нет retention (не можно replay старые сообщения)
- Проще, но менее надёжно

**Используется для:**
- Cache invalidation
- Real-time notifications
- Координация между инстансами

**ПРИМЕР КОДА:**
```kotlin
// Publisher
@Service
class CacheInvalidationPublisher(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun invalidateUser(userId: Long) {
        redisTemplate.convertAndSend(
            "cache:invalidation:users",
            userId.toString()
        )
        // Сообщение отправляется ВСЕМ подписчикам
    }
}

// Subscriber
@Component
class CacheInvalidationSubscriber(
    private val cacheManager: CacheManager
) : MessageListener {
    
    override fun onMessage(message: Message, pattern: ByteArray?) {
        val userId = String(message.body).toLong()
        
        logger.info("Invalidating cache for user $userId")
        
        // Очищаем локальный кэш
        cacheManager.getCache("users")?.evict(userId)
    }
}

// Конфигурация
@Configuration
class RedisPubSubConfig {
    
    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        cacheInvalidationSubscriber: CacheInvalidationSubscriber
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        
        container.addMessageListener(
            cacheInvalidationSubscriber,
            ChannelTopic("cache:invalidation:users")
        )
        
        return container
    }
}

// Pattern subscription (wildcard)
container.addMessageListener(
    subscriber,
    PatternTopic("cache:invalidation:*")  // Подписка на все каналы cache:invalidation:*
)

// Redis Streams (альтернатива Pub/Sub с persistence!)
@Service
class OrderEventStreamService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun publishOrderEvent(event: OrderEvent) {
        val record = MapRecord.create(
            "orders:stream",
            mapOf(
                "orderId" to event.orderId.toString(),
                "type" to event.type.name,
                "timestamp" to event.timestamp.toString()
            )
        )
        
        redisTemplate.opsForStream<String, String>().add(record)
        // Сообщение СОХРАНЯЕТСЯ в stream
    }
    
    fun consumeOrderEvents(consumerGroup: String) {
        val streamOffset = StreamOffset.create("orders:stream", ReadOffset.lastConsumed())
        
        val messages = redisTemplate.opsForStream<String, String>()
            .read(
                Consumer.from(consumerGroup, "consumer-1"),
                StreamReadOptions.empty().count(10),
                streamOffset
            )
        
        messages?.forEach { message ->
            val orderId = message.value["orderId"]
            logger.info("Processing order: $orderId")
            
            // Acknowledge
            redisTemplate.opsForStream<String, String>()
                .acknowledge(consumerGroup, message)
        }
    }
}

// Pub/Sub vs Streams сравнение:
// Pub/Sub:
//   ✅ Простота
//   ✅ Низкая latency
//   ❌ Нет persistence
//   ❌ Нет гарантии доставки
//
// Streams:
//   ✅ Persistence
//   ✅ Consumer groups
//   ✅ Acknowledgment
//   ✅ Можно replay
//   ❌ Чуть сложнее
```
───────────────────────────────────────────────────────────────────────────────

## Cassandra

### КЕЙС #10 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** В чём отличие Cassandra от PostgreSQL в плане consistency? Что такое tunable consistency?

**ОТВЕТ:**
**PostgreSQL**: ACID, strong consistency (всегда согласованные данные)
**Cassandra**: AP по CAP теореме (availability + partition tolerance)

**Tunable consistency**: выбор уровня консистентности для каждого запроса.

**ПОЧЕМУ ЭТО ВАЖНО:**
- Strong consistency медленнее
- Eventual consistency быстрее, но может читать старые данные
- Trade-off: скорость vs надёжность

**ПРИМЕР КОДА:**
```kotlin
// Data model в Cassandra
CREATE TABLE users_by_email (
    email TEXT,
    user_id UUID,
    name TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY (email)
) WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};

// 3 реплики данных

// Tunable consistency в Cassandra
val session = CqlSession.builder()
    .addContactPoint(InetSocketAddress("localhost", 9042))
    .withLocalDatacenter("datacenter1")
    .build()

// WRITE с QUORUM (большинство реплик)
session.execute(
    SimpleStatement
        .newInstance("INSERT INTO users_by_email (email, user_id, name) VALUES (?, ?, ?)",
            "john@example.com", UUID.randomUUID(), "John")
        .setConsistencyLevel(ConsistencyLevel.QUORUM)  
        // Запись на 2 из 3 реплик (большинство)
)

// Consistency levels:
// ONE: 1 реплика (быстро, риск потери данных)
// TWO: 2 реплики
// THREE: 3 реплики
// QUORUM: большинство реплик (формула: (replication_factor / 2) + 1)
// ALL: все реплики (медленно, максимальная надёжность)
// LOCAL_QUORUM: большинство в локальном datacenter
// EACH_QUORUM: большинство в КАЖДОМ datacenter

// READ с ONE (быстро, но может быть stale data)
val result = session.execute(
    SimpleStatement
        .newInstance("SELECT * FROM users_by_email WHERE email = ?", "john@example.com")
        .setConsistencyLevel(ConsistencyLevel.ONE)
        // Читаем с 1 реплики (быстро, но может быть неактуально)
)

// Strong consistency: QUORUM write + QUORUM read
// QUORUM write (2/3 реплик) + QUORUM read (2/3 реплик)
// = Гарантия, что прочитаем последние данные

// Eventual consistency: ONE write + ONE read
// Быстро, но может читать старые данные

// Trade-off таблица:
// Write CL | Read CL | Consistency | Latency
// ONE      | ONE     | Eventual    | Низкая
// QUORUM   | ONE     | Eventual    | Средняя
// QUORUM   | QUORUM  | Strong      | Высокая
// ALL      | ALL     | Strong      | Очень высокая

// Spring Data Cassandra
@Table("users_by_email")
data class UserByEmail(
    @PrimaryKey val email: String,
    val userId: UUID,
    val name: String,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

@Repository
interface UserByEmailRepository : CassandraRepository<UserByEmail, String> {
    
    @Query("SELECT * FROM users_by_email WHERE email = ?0")
    @Consistency(value = ConsistencyLevel.QUORUM)
    fun findByEmailStrong(email: String): UserByEmail?
    
    @Consistency(value = ConsistencyLevel.ONE)
    fun findByEmail(email: String): UserByEmail?
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #11 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как правильно моделировать данные в Cassandra? Query-first design?

**ОТВЕТ:**
**Cassandra data modeling**: проектирование под запросы (query-first).

**Правила:**
1. Одна таблица = один тип запроса
2. Денормализация (дублирование данных)
3. Нет JOIN'ов

**ПРИМЕР КОДА:**
```kotlin
// SQL подход (нормализация)
// Table: users (id, name, email)
// Table: orders (id, user_id, total, created_at)
// Query: SELECT * FROM orders WHERE user_id = ? JOIN users ...

// Cassandra подход: несколько таблиц для разных запросов

// Запрос 1: заказы по пользователю
CREATE TABLE orders_by_user (
    user_id UUID,
    order_id UUID,
    user_name TEXT,    -- Денормализация!
    user_email TEXT,   -- Денормализация!
    total DECIMAL,
    created_at TIMESTAMP,
    PRIMARY KEY (user_id, created_at, order_id)
) WITH CLUSTERING ORDER BY (created_at DESC, order_id ASC);

// Запрос 2: заказы по дате
CREATE TABLE orders_by_date (
    date_bucket TEXT,  -- "2026-01-29"
    created_at TIMESTAMP,
    order_id UUID,
    user_id UUID,
    user_name TEXT,
    total DECIMAL,
    PRIMARY KEY (date_bucket, created_at, order_id)
) WITH CLUSTERING ORDER BY (created_at DESC, order_id ASC);

// Запрос 3: заказ по ID
CREATE TABLE orders_by_id (
    order_id UUID,
    user_id UUID,
    user_name TEXT,
    total DECIMAL,
    created_at TIMESTAMP,
    items LIST<FROZEN<order_item>>,  -- Embedded структура
    PRIMARY KEY (order_id)
);

// UDT (User Defined Type) для embedded структуры
CREATE TYPE order_item (
    product_id UUID,
    product_name TEXT,
    quantity INT,
    price DECIMAL
);

// Data model
@Table("orders_by_user")
data class OrderByUser(
    @PrimaryKeyColumn(name = "user_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    val userId: UUID,
    
    @PrimaryKeyColumn(name = "created_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    @CassandraType(type = CassandraType.Name.TIMESTAMP)
    val createdAt: LocalDateTime,
    
    @PrimaryKeyColumn(name = "order_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
    val orderId: UUID,
    
    val userName: String,
    val userEmail: String,
    val total: BigDecimal
)

// Вставка в ВСЕ таблицы при создании заказа
@Service
class OrderService(
    private val session: CqlSession
) {
    
    @Transactional
    fun createOrder(order: Order) {
        val batch = BatchStatement.builder(BatchType.LOGGED)
        
        // 1. orders_by_user
        batch.addStatement(
            SimpleStatement.newInstance("""
                INSERT INTO orders_by_user (user_id, created_at, order_id, user_name, user_email, total)
                VALUES (?, ?, ?, ?, ?, ?)
            """, order.userId, order.createdAt, order.orderId, order.userName, order.userEmail, order.total)
        )
        
        // 2. orders_by_date
        val dateBucket = order.createdAt.toLocalDate().toString()
        batch.addStatement(
            SimpleStatement.newInstance("""
                INSERT INTO orders_by_date (date_bucket, created_at, order_id, user_id, user_name, total)
                VALUES (?, ?, ?, ?, ?, ?)
            """, dateBucket, order.createdAt, order.orderId, order.userId, order.userName, order.total)
        )
        
        // 3. orders_by_id
        batch.addStatement(
            SimpleStatement.newInstance("""
                INSERT INTO orders_by_id (order_id, user_id, user_name, total, created_at)
                VALUES (?, ?, ?, ?, ?)
            """, order.orderId, order.userId, order.userName, order.total, order.createdAt)
        )
        
        session.execute(batch.build())
        // Cassandra гарантирует, что либо ВСЕ записи, либо НИЧЕГО (logged batch)
    }
}

// Partition key выбор
// ПЛОХО: слишком большие партиции
CREATE TABLE orders (
    country TEXT,  -- Partition key
    order_id UUID,
    ...
    PRIMARY KEY (country, order_id)
);
// Все заказы из USA в ОДНОЙ партиции → миллионы строк → медленно!

// ХОРОШО: распределённые партиции
CREATE TABLE orders (
    user_id UUID,  -- Partition key (много пользователей = много партиций)
    created_at TIMESTAMP,
    order_id UUID,
    ...
    PRIMARY KEY (user_id, created_at, order_id)
);
```
───────────────────────────────────────────────────────────────────────────────

## Выбор БД

### КЕЙС #12 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как выбрать между PostgreSQL, MongoDB, Redis, Cassandra для конкретной задачи?

**ОТВЕТ:**
**Decision tree:**

**PostgreSQL** (SQL, ACID):
- Сложные JOIN'ы
- Транзакции критичны
- Структурированные данные
- OLTP workload

**MongoDB** (Document DB):
- Гибкая схема (часто меняется)
- Вложенные документы
- Быстрое прототипирование
- Нет сложных JOIN'ов

**Redis** (In-memory):
- Кэш
- Session storage
- Leaderboards, counters
- Real-time analytics

**Cassandra** (Wide-column):
- Огромный объём данных (петабайты)
- High availability критична
- Write-heavy workload
- Простые запросы по ключу

**ПРИМЕР КОДА:**
```kotlin
// Пример: e-commerce система

// 1. PostgreSQL: основные данные (ACID критичен)
@Entity
@Table(name = "users")
data class User(
    @Id val id: Long,
    val email: String,
    val passwordHash: String,
    val balance: BigDecimal  // Транзакции с балансом — нужен ACID!
)

@Entity
@Table(name = "orders")
data class Order(
    @Id val id: Long,
    val userId: Long,
    val total: BigDecimal,
    val status: OrderStatus
)

// Транзакция: списание баланса + создание заказа
@Transactional
fun createOrder(userId: Long, total: BigDecimal): Order {
    val user = userRepository.findById(userId).orElseThrow()
    
    require(user.balance >= total) { "Insufficient funds" }
    
    user.balance -= total
    userRepository.save(user)
    
    val order = Order(userId = userId, total = total, status = PENDING)
    return orderRepository.save(order)
    
    // Атомарность гарантирована PostgreSQL
}

// 2. MongoDB: каталог продуктов (гибкая схема)
@Document(collection = "products")
data class Product(
    @Id val id: ObjectId,
    val name: String,
    val category: String,
    val price: BigDecimal,
    val specifications: Map<String, Any>,  // Гибкая схема!
    val reviews: List<Review>  // Embedded
)

// У разных категорий разные specifications
// Electronics: {brand, model, warranty}
// Books: {author, publisher, isbn}
// MongoDB позволяет хранить разные структуры

// 3. Redis: кэш, сессии, счётчики
@Service
class ProductCacheService(
    private val redisTemplate: RedisTemplate<String, Product>,
    private val productRepository: ProductRepository
) {
    
    fun getProduct(id: Long): Product {
        val cached = redisTemplate.opsForValue().get("product:$id")
        if (cached != null) return cached
        
        val product = productRepository.findById(id)
        redisTemplate.opsForValue().set("product:$id", product, Duration.ofHours(1))
        
        return product
    }
}

// Session storage
@Service
class SessionService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun createSession(userId: Long): String {
        val sessionId = UUID.randomUUID().toString()
        
        redisTemplate.opsForValue().set(
            "session:$sessionId",
            userId.toString(),
            Duration.ofHours(24)
        )
        
        return sessionId
    }
}

// Real-time analytics
redisTemplate.opsForValue().increment("product:$id:views")

// 4. Cassandra: логи, metrics, time-series
@Table("product_views")
data class ProductView(
    @PrimaryKeyColumn(name = "product_id", ordinal = 0, type = PARTITIONED)
    val productId: Long,
    
    @PrimaryKeyColumn(name = "view_time", ordinal = 1, type = CLUSTERED)
    val viewTime: LocalDateTime,
    
    @PrimaryKeyColumn(name = "user_id", ordinal = 2, type = CLUSTERED)
    val userId: Long,
    
    val sessionId: String,
    val source: String
)

// Миллионы views в день → Cassandra справляется
// Query: views по продукту за диапазон дат
SELECT * FROM product_views 
WHERE product_id = ? 
  AND view_time >= ? 
  AND view_time <= ?;

// Polyglot persistence: комбинация БД
// - PostgreSQL: users, orders, payments (ACID)
// - MongoDB: products, reviews (flexible schema)
// - Redis: cache, sessions, counters
// - Cassandra: logs, analytics, time-series
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #13 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое CAP theorem? Как она влияет на выбор NoSQL БД?

**ОТВЕТ:**
**CAP theorem**: невозможно одновременно гарантировать все 3:
- **C**onsistency: все ноды видят одинаковые данные
- **A**vailability: любой запрос получает ответ
- **P**artition tolerance: работа при сетевых разделениях

**Можно выбрать только 2 из 3.**

**ПРИМЕР КОДА:**
```kotlin
// CP системы (Consistency + Partition tolerance)
// MongoDB (с majority write concern)
@Service
class StrongConsistencyService(
    private val mongoTemplate: MongoTemplate
) {
    
    fun createOrder(order: Order) {
        val options = InsertOneOptions()
            .writeConcern(WriteConcern.MAJORITY)  // Ждём подтверждения от большинства реплик
        
        mongoTemplate.insert(order, options)
        // Медленнее, но данные согласованы
    }
    
    // При network partition:
    // Если большинство реплик недоступно → запись ОТКЛОНИТСЯ
    // Consistency > Availability
}

// AP системы (Availability + Partition tolerance)
// Cassandra, DynamoDB
@Service
class HighAvailabilityService(
    private val session: CqlSession
) {
    
    fun createOrder(order: Order) {
        val statement = SimpleStatement
            .newInstance("INSERT INTO orders (...) VALUES (...)")
            .setConsistencyLevel(ConsistencyLevel.ONE)  // Пишем на 1 реплику
        
        session.execute(statement)
        // Быстро, всегда доступно, но может быть несогласованность
    }
    
    // При network partition:
    // Запись успешна на 1 реплику
    // Другие реплики получат данные позже (eventual consistency)
    // Availability > Consistency
}

// CA системы (Consistency + Availability)
// PostgreSQL, MySQL (single node)
// НО: нет Partition tolerance!
// При сетевом разделении → система недоступна

// Trade-offs в production:
// Финансы (payments): CP (PostgreSQL)
//   - Consistency критична (нельзя потерять деньги)
//   - Лучше отклонить запрос, чем сделать неправильно
//
// Social media (likes, views): AP (Cassandra)
//   - Availability критична (пользователи ждут быстрого ответа)
//   - Eventual consistency допустима (лайк появится через секунду)
//
// E-commerce catalog: AP (MongoDB с ONE write)
//   - Доступность важнее (пользователи должны видеть товары)
//   - Небольшая несогласованность допустима
//
// Banking transactions: CP (PostgreSQL)
//   - Согласованность критична
//   - Лучше временная недоступность, чем ошибка в балансе

// Tunable consistency: баланс между CP и AP
@Service
class TunableConsistencyService(
    private val session: CqlSession
) {
    
    // Критичная операция: CP
    fun criticalWrite(data: Data) {
        val statement = SimpleStatement
            .newInstance("INSERT ...")
            .setConsistencyLevel(ConsistencyLevel.QUORUM)  // Большинство реплик
        
        session.execute(statement)
    }
    
    // Некритичная операция: AP
    fun fastWrite(data: Data) {
        val statement = SimpleStatement
            .newInstance("INSERT ...")
            .setConsistencyLevel(ConsistencyLevel.ONE)  // 1 реплика
        
        session.execute(statement)
    }
}
```
───────────────────────────────────────────────────────────────────────────────

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$2.80

*Версия: 1.0 | Январь 2026*

