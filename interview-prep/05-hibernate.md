# Hibernate для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Ключевые темы
- N+1 Problem & Solutions (Кейсы 1-5)
- Entity Lifecycle (Кейсы 6-10)
- Caching (Кейсы 11-15)

## N+1 Problem

### КЕЙС #1 | Уровень: Middle
**ВОПРОС:** Что такое N+1 проблема? Как диагностировать и исправить?

**ОТВЕТ:**
```kotlin
// ПЛОХО: N+1 проблема (1 + 100 запросов)
@Entity
data class User(
    @Id val id: Long,
    val name: String,
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val orders: List<Order> = emptyList()
)

val users = userRepository.findAll()  // 1 запрос
users.forEach { user ->
    println(user.orders.size)  // 100 запросов (по одному на каждого user)!
}

// ХОРОШО: JOIN FETCH (1 запрос)
@Query("SELECT u FROM User u LEFT JOIN FETCH u.orders WHERE u.id IN :ids")
fun findAllWithOrders(@Param("ids") ids: List<Long>): List<User>

// АЛЬТЕРНАТИВА: @BatchSize (3 запроса: users + orders пачками)
@Entity
data class User(
    @Id val id: Long,
    @OneToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 50)  // Загружать orders по 50 users
    val orders: List<Order> = emptyList()
)

// ДИАГНОСТИКА: включить логи Hibernate
spring.jpa.properties.hibernate.generate_statistics=true
spring.jpa.show-sql=true

// Или datasource-proxy
@Bean
fun dataSource(): DataSource {
    return ProxyDataSourceBuilder
        .create(actualDataSource)
        .countQuery()
        .logQueryToSysOut()
        .build()
}
```

### КЕЙС #2 | Уровень: Senior
**ВОПРОС:** В чём разница между FetchType.LAZY и EAGER? Почему EAGER считается антипаттерном?

**ОТВЕТ:**
```kotlin
// ПЛОХО: EAGER загружает всё всегда
@Entity
data class User(
    @OneToMany(fetch = FetchType.EAGER)  // ВСЕГДА загружает orders
    val orders: List<Order>,
    @OneToMany(fetch = FetchType.EAGER)  // ВСЕГДА загружает addresses
    val addresses: List<Address>
)

// Даже если нужен только name:
val name = userRepository.findById(1L).name  
// Загрузятся orders + addresses (лишние данные)!

// ХОРОШО: LAZY + явная загрузка когда нужно
@Entity
data class User(
    @OneToMany(fetch = FetchType.LAZY)  // Загружаем по требованию
    val orders: List<Order>,
    @OneToMany(fetch = FetchType.LAZY)
    val addresses: List<Address>
)

// Загружаем только когда нужно
@Query("SELECT u FROM User u LEFT JOIN FETCH u.orders WHERE u.id = :id")
fun findByIdWithOrders(@Param("id") id: Long): User?

@Query("SELECT u FROM User u LEFT JOIN FETCH u.addresses WHERE u.id = :id")
fun findByIdWithAddresses(@Param("id") id: Long): User?
```

## Entity Lifecycle

### КЕЙС #6 | Уровень: Middle
**ВОПРОС:** В чём разница между persist(), merge() и save()? Когда использовать каждый?

**ОТВЕТ:**
```kotlin
// persist() — только для новых сущностей
val user = User(name = "John")
entityManager.persist(user)  // user теперь managed
// id присваивается сразу (если strategy = IDENTITY)

// merge() — для detached сущностей
val user = User(id = 1L, name = "Updated")
val managed = entityManager.merge(user)  // Возвращает managed копию
// user остаётся detached, managed — новый объект

// save() (Spring Data JPA) — универсальный
val user = User(name = "John")
userRepository.save(user)  
// Внутри вызывает persist() или merge() в зависимости от наличия id
```

## Entity Lifecycle подробно

### КЕЙС #7 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
В чём разница между состояниями Transient, Persistent, Detached, Removed?
Что происходит при переходах между ними?

**ОТВЕТ:**
**4 состояния Entity:**
1. **Transient**: новый объект, не в БД, не в Persistence Context
2. **Persistent (Managed)**: в Persistence Context, синхронизируется с БД
3. **Detached**: был Persistent, но Persistence Context закрыт
4. **Removed**: помечен на удаление

**ПРИМЕР КОДА:**
```kotlin
@Service
class UserLifecycleService(
    private val entityManager: EntityManager
) {
    
    @Transactional
    fun demonstrateLifecycle() {
        // 1. TRANSIENT: новый объект
        val user = User(name = "John", email = "john@example.com")
        assertFalse(entityManager.contains(user))  // НЕ в Persistence Context
        
        // 2. PERSISTENT: persist делает managed
        entityManager.persist(user)
        assertTrue(entityManager.contains(user))  // В Persistence Context
        
        // ID присваивается (зависит от GenerationType)
        assertNotNull(user.id)
        
        // Изменения автоматически синхронизируются
        user.name = "John Updated"
        entityManager.flush()  // UPDATE user SET name='John Updated'
        
        // 3. DETACHED: clear убирает из Persistence Context
        entityManager.clear()
        assertFalse(entityManager.contains(user))
        
        // Изменения НЕ синхронизируются
        user.name = "John Detached"
        entityManager.flush()  // НЕТ UPDATE!
        
        // 4. PERSISTENT снова: merge возвращает managed копию
        val managedUser = entityManager.merge(user)
        assertTrue(entityManager.contains(managedUser))
        assertFalse(entityManager.contains(user))  // Старый объект detached!
        
        // 5. REMOVED: remove помечает на удаление
        entityManager.remove(managedUser)
        // DELETE выполнится при flush() или commit()
        
        entityManager.flush()  // DELETE FROM user WHERE id = ?
    }
}

// Проблема: работа с detached в контроллере
@RestController
class UserController(
    private val userService: UserService
) {
    
    @PostMapping("/api/users/{id}")
    fun updateUser(@PathVariable id: Long, @RequestBody dto: UserDto): User {
        var user = userService.findById(id)  // Persistent в @Transactional методе
        // Но метод завершился → user стал DETACHED!
        
        user.name = dto.name  // Изменение detached объекта
        
        return userService.save(user)  // merge() внутри
    }
}

// РЕШЕНИЕ 1: @Transactional на контроллере
@RestController
class UserController(
    private val userService: UserService
) {
    
    @PostMapping("/api/users/{id}")
    @Transactional  // Весь метод в одной транзакции
    fun updateUser(@PathVariable id: Long, @RequestBody dto: UserDto): User {
        val user = userService.findById(id)  // Persistent
        user.name = dto.name  // Изменяем managed объект
        return user  // Автоматически сохранится при commit
    }
}

// РЕШЕНИЕ 2: явный save
@PostMapping("/api/users/{id}")
fun updateUser(@PathVariable id: Long, @RequestBody dto: UserDto): User {
    return userService.updateUser(id, dto)  // Вся логика в сервисе
}

@Service
class UserService {
    @Transactional
    fun updateUser(id: Long, dto: UserDto): User {
        val user = userRepository.findById(id)
            ?: throw NotFoundException()
        
        user.name = dto.name
        return userRepository.save(user)  // merge
    }
}
```

### КЕЙС #8 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое LazyInitializationException? Почему возникает и как исправить?

**ОТВЕТ:**
**Причина**: попытка загрузить lazy collection вне Persistence Context (транзакции).

**Решения:**
1. `JOIN FETCH` — загрузить сразу
2. `@Transactional` — расширить границы транзакции
3. `Hibernate.initialize()` — явная инициализация
4. DTO вместо Entity

**ПРИМЕР КОДА:**
```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository
) {
    
    @Transactional
    fun findById(id: Long): Order? {
        return orderRepository.findById(id).orElse(null)
    }  // Транзакция завершается здесь!
}

@RestController
class OrderController(
    private val orderService: OrderService
) {
    
    @GetMapping("/api/orders/{id}")
    fun getOrder(@PathVariable id: Long): OrderDto {
        val order = orderService.findById(id)  // Вне транзакции!
        
        // LazyInitializationException!
        return OrderDto(
            id = order.id,
            items = order.items.map { it.toDto() }  // items — LAZY!
        )
    }
}

// РЕШЕНИЕ 1: JOIN FETCH
@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    fun findByIdWithItems(@Param("id") id: Long): Order?
}

@Service
class OrderService {
    @Transactional(readOnly = true)
    fun findById(id: Long): Order? {
        return orderRepository.findByIdWithItems(id)
    }
}

// РЕШЕНИЕ 2: @Transactional на контроллере (НЕ рекомендуется!)
@RestController
class OrderController {
    
    @GetMapping("/api/orders/{id}")
    @Transactional(readOnly = true)  // Расширяем транзакцию на контроллер
    fun getOrder(@PathVariable id: Long): OrderDto {
        val order = orderService.findById(id)
        return order.toDto()  // items загрузятся внутри транзакции
    }
}

// РЕШЕНИЕ 3: Hibernate.initialize()
@Service
class OrderService {
    
    @Transactional(readOnly = true)
    fun findByIdWithItems(id: Long): Order? {
        val order = orderRepository.findById(id).orElse(null)
        
        if (order != null) {
            Hibernate.initialize(order.items)  // Явная загрузка
            Hibernate.initialize(order.customer)  // Можно несколько
        }
        
        return order
    }
}

// РЕШЕНИЕ 4: DTO Projection (лучшее!)
interface OrderProjection {
    val id: Long
    val total: BigDecimal
    val items: List<OrderItemProjection>
    
    interface OrderItemProjection {
        val id: Long
        val quantity: Int
    }
}

@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query("""
        SELECT o.id as id, o.total as total, 
               i.id as item_id, i.quantity as item_quantity
        FROM Order o 
        LEFT JOIN o.items i 
        WHERE o.id = :id
    """)
    fun findProjectionById(@Param("id") id: Long): OrderProjection?
}

// Нет LazyInitializationException, загружается только нужное!
```

### КЕЙС #9 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое Dirty Checking? Как Hibernate знает, что Entity изменилась?

**ОТВЕТ:**
**Dirty Checking**: автоматическое отслеживание изменений в managed entities.

**Механизм:**
1. При загрузке Entity сохраняется snapshot
2. При `flush()` сравнивается текущее состояние со snapshot
3. Генерируется UPDATE только для изменённых полей

**ПРИМЕР КОДА:**
```kotlin
@Service
class UserService(
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {
    
    @Transactional
    fun updateUser(id: Long, newName: String) {
        val user = userRepository.findById(id).orElseThrow()
        
        // user — managed entity
        user.name = newName
        
        // НЕ нужно вызывать save()!
        // Hibernate автоматически detect изменения при flush()
    }  // При commit автоматически flush() → UPDATE user SET name=? WHERE id=?
    
    @Transactional
    fun demonstrateDirtyChecking() {
        val user = userRepository.findById(1L).orElseThrow()
        // Snapshot: {id=1, name="John", email="john@example.com"}
        
        user.name = "Jane"
        // Dirty: name изменился
        
        entityManager.flush()
        // Hibernate сравнивает snapshot с текущим состоянием
        // Генерирует: UPDATE user SET name='Jane' WHERE id=1
        // Только изменённые поля!
        
        user.email = "jane@example.com"
        // Ещё одно изменение
        
        // При commit() автоматически flush()
        // UPDATE user SET email='jane@example.com' WHERE id=1
    }
}

// Отключение Dirty Checking для read-only
@Service
class ReportService(
    private val entityManager: EntityManager
) {
    
    @Transactional(readOnly = true)
    fun generateReport(): Report {
        // readOnly = true → Hibernate НЕ делает Dirty Checking
        // Производительность лучше для read-only операций
        
        val users = userRepository.findAll()
        // Изменения НЕ отслеживаются → нет overhead
        
        return Report(users)
    }
}

// Ручное управление flush
@Service
class BulkUpdateService {
    
    @Transactional
    fun updateAllUsers(updates: List<UserUpdate>) {
        updates.forEachIndexed { index, update ->
            val user = userRepository.findById(update.id).orElseThrow()
            user.name = update.name
            
            // Flush каждые 50 записей
            if (index % 50 == 0) {
                entityManager.flush()  // Отправляем UPDATE в БД
                entityManager.clear()  // Очищаем Persistence Context (освобождаем память)
            }
        }
    }
}

// Отключение автоматического flush
@Transactional
fun batchInsert(users: List<User>) {
    entityManager.flushMode = FlushModeType.COMMIT  // Только при commit
    
    users.forEach { user ->
        entityManager.persist(user)
        // НЕТ автоматического flush каждые N записей
    }
    
    entityManager.flush()  // Явный flush в конце
}
```

### КЕЙС #10 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как правильно работать с @OneToMany и @ManyToOne? Какая сторона должна быть владельцем?

**ОТВЕТ:**
**Правило**: сторона с `@ManyToOne` должна быть владельцем (mappedBy на противоположной стороне).

**Причина**: foreign key находится на стороне Many.

**ПРИМЕР КОДА:**
```kotlin
// ПЛОХО: неправильное ownership
@Entity
data class User(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @OneToMany(cascade = [CascadeType.ALL])  // БЕЗ mappedBy → владелец!
    val orders: MutableList<Order> = mutableListOf()
)

@Entity
data class Order(
    @Id @GeneratedValue
    val id: Long? = null,
    val total: BigDecimal
)

// Hibernate создаёт ТРЕТЬЮ таблицу user_orders (join table)!
// CREATE TABLE user_orders (user_id BIGINT, orders_id BIGINT)

// ХОРОШО: правильное ownership
@Entity
data class User(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val orders: MutableList<Order> = mutableListOf()
)

@Entity
data class Order(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")  // Foreign key
    val user: User,
    
    val total: BigDecimal
)

// Только 2 таблицы: user и order (с user_id FK)

// Правильное добавление в коллекцию
@Service
class OrderService {
    
    @Transactional
    fun addOrderToUser(userId: Long, orderDto: OrderDto) {
        val user = userRepository.findById(userId).orElseThrow()
        
        val order = Order(
            user = user,  // ОБЯЗАТЕЛЬНО установить обе стороны!
            total = orderDto.total
        )
        
        user.orders.add(order)  // Добавить в коллекцию
        
        // Автоматически сохранится благодаря cascade = ALL
    }
}

// Helper метод для двусторонней связи
@Entity
data class User(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    private val _orders: MutableList<Order> = mutableListOf()
) {
    val orders: List<Order> get() = _orders.toList()
    
    fun addOrder(order: Order) {
        _orders.add(order)
        order.user = this  // Устанавливаем обе стороны!
    }
    
    fun removeOrder(order: Order) {
        _orders.remove(order)
        order.user = null  // Обе стороны!
    }
}

// orphanRemoval: удаление "осиротевших" entities
@Transactional
fun removeOrderFromUser(userId: Long, orderId: Long) {
    val user = userRepository.findById(userId).orElseThrow()
    val order = user.orders.find { it.id == orderId }
    
    if (order != null) {
        user.removeOrder(order)
        // orphanRemoval=true → order автоматически удалится из БД!
    }
}
```

### КЕЙС #11 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Batch Insert в Hibernate? Почему он может не работать?

**ОТВЕТ:**
**Batch Insert**: группировка INSERT в batch для производительности.

**Почему НЕ работает:**
- `GenerationType.IDENTITY` несовместим с batching
- Нужен `SEQUENCE` или `TABLE`

**ПРИМЕР КОДА:**
```kotlin
// ПЛОХО: IDENTITY → нет batching
@Entity
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // ПЛОХО!
    val id: Long? = null,
    val name: String
)

// Hibernate не может batch:
// INSERT INTO product (name) VALUES ('Product 1');  -- получить ID
// INSERT INTO product (name) VALUES ('Product 2');  -- получить ID
// ...

// ХОРОШО: SEQUENCE → batching работает
@Entity
data class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_seq")
    @SequenceGenerator(name = "product_seq", sequenceName = "product_sequence", allocationSize = 50)
    val id: Long? = null,
    val name: String
)

// Hibernate может batch:
// SELECT nextval('product_sequence');  -- получить 50 ID сразу
// INSERT INTO product (id, name) VALUES (1, 'Product 1'), (2, 'Product 2'), ...

// Конфигурация batching
"""
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
"""

// Batch insert в коде
@Service
class ProductImportService(
    private val entityManager: EntityManager
) {
    
    @Transactional
    fun importProducts(products: List<ProductDto>) {
        products.forEachIndexed { index, dto ->
            val product = Product(name = dto.name, price = dto.price)
            entityManager.persist(product)
            
            // Flush и clear каждые 50 записей
            if (index % 50 == 0 && index > 0) {
                entityManager.flush()
                entityManager.clear()
                // Освобождаем память + отправляем batch в БД
            }
        }
    }
}

// JDBC Batch (без Hibernate) — ещё быстрее
@Service
class FastProductImportService(
    private val jdbcTemplate: JdbcTemplate
) {
    
    fun importProducts(products: List<ProductDto>) {
        val sql = "INSERT INTO product (id, name, price) VALUES (?, ?, ?)"
        
        jdbcTemplate.batchUpdate(
            sql,
            products,
            100,  // Batch size
            { ps, product ->
                ps.setLong(1, product.id)
                ps.setString(2, product.name)
                ps.setBigDecimal(3, product.price)
            }
        )
        // 10x быстрее чем через Hibernate!
    }
}
```

## Caching подробно

### КЕЙС #16 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Second Level Cache в Hibernate? В чём разница с First Level Cache?

**ОТВЕТ:**
**First Level Cache (обязательный):**
- Уровень Persistence Context (Session)
- Живёт в рамках одной транзакции
- Невозможно отключить

**Second Level Cache (опциональный):**
- Уровень SessionFactory
- Общий для всех транзакций
- Нужно настраивать (Ehcache, Hazelcast, Redis)

**ПРИМЕР КОДА:**
```kotlin
// Конфигурация Second Level Cache
"""
spring.jpa.properties.hibernate.cache.use_second_level_cache=true
spring.jpa.properties.hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory
spring.jpa.properties.hibernate.cache.use_query_cache=true
spring.cache.jcache.provider=com.hazelcast.cache.HazelcastCachingProvider
"""

// Кэшируемая сущность
@Entity
@Cacheable
@org.hibernate.annotations.Cache(
    usage = CacheConcurrencyStrategy.READ_WRITE,
    region = "products"
)
data class Product(
    @Id val id: Long,
    val name: String,
    val price: BigDecimal
)

// First Level Cache
@Transactional
fun demonstrateFirstLevelCache() {
    val product1 = productRepository.findById(1L)  // SELECT from DB
    val product2 = productRepository.findById(1L)  // From First Level Cache (same session)
    
    assertTrue(product1 === product2)  // Тот же объект!
}  // First Level Cache очищается при закрытии транзакции

// Second Level Cache
fun demonstrateSecondLevelCache() {
    // Транзакция 1
    transactionTemplate.execute {
        productRepository.findById(1L)  // SELECT from DB → в Second Level Cache
    }
    
    // Транзакция 2 (новая session)
    transactionTemplate.execute {
        productRepository.findById(1L)  // From Second Level Cache! Нет SELECT
    }
}

// Кэширование коллекций
@Entity
data class Category(
    @Id val id: Long,
    
    @OneToMany(mappedBy = "category")
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    val products: List<Product> = emptyList()
)

// Query Cache
@Repository
interface ProductRepository : JpaRepository<Product, Long> {
    
    @Query("SELECT p FROM Product p WHERE p.category = :category")
    @QueryHints(
        QueryHint(name = "org.hibernate.cacheable", value = "true"),
        QueryHint(name = "org.hibernate.cacheRegion", value = "productQueries")
    )
    fun findByCategory(@Param("category") category: String): List<Product>
}

// Cache strategies
enum class CacheConcurrencyStrategy {
    NONE,              // Нет кэша
    READ_ONLY,         // Immutable данные (быстрый)
    NONSTRICT_READ_WRITE,  // Soft locks (eventual consistency)
    READ_WRITE,        // Strong consistency (медленнее)
    TRANSACTIONAL      // JTA транзакции (очень медленно)
}

// Управление кэшем
@Service
class CacheManagementService(
    private val entityManager: EntityManager
) {
    
    fun evictProductCache(productId: Long) {
        val cache = entityManager.entityManagerFactory.cache
        cache.evict(Product::class.java, productId)
    }
    
    fun evictAllProducts() {
        val cache = entityManager.entityManagerFactory.cache
        cache.evict(Product::class.java)
    }
    
    fun evictAll() {
        val cache = entityManager.entityManagerFactory.cache
        cache.evictAll()  // Очистить весь Second Level Cache
    }
}

// Мониторинг кэша
@Service
class CacheStatsService(
    private val entityManager: EntityManager
) {
    
    fun getStats(): Map<String, Any> {
        val stats = (entityManager.entityManagerFactory as SessionFactory)
            .statistics
        
        return mapOf(
            "secondLevelCacheHitCount" to stats.secondLevelCacheHitCount,
            "secondLevelCacheMissCount" to stats.secondLevelCacheMissCount,
            "secondLevelCachePutCount" to stats.secondLevelCachePutCount,
            "queryCacheHitCount" to stats.queryCacheHitCount,
            "queryCacheMissCount" to stats.queryCacheMissCount
        )
    }
}
```

### КЕЙС #17 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как использовать @EntityGraph для оптимизации загрузки? В чём отличие от JOIN FETCH?

**ОТВЕТ:**
**@EntityGraph**: динамическое управление загрузкой ассоциаций.

**Преимущества:**
- Переопределяет fetch type без изменения Entity
- Можно несколько графов для разных случаев

**ПРИМЕР КОДА:**
```kotlin
@Entity
@NamedEntityGraph(
    name = "User.orders",
    attributeNodes = [NamedAttributeNode("orders")]
)
@NamedEntityGraph(
    name = "User.full",
    attributeNodes = [
        NamedAttributeNode("orders"),
        NamedAttributeNode("addresses"),
        NamedAttributeNode("roles")
    ]
)
data class User(
    @Id val id: Long,
    val name: String,
    
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val orders: List<Order> = emptyList(),
    
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val addresses: List<Address> = emptyList(),
    
    @ManyToMany(fetch = FetchType.LAZY)
    val roles: List<Role> = emptyList()
)

@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    // EntityGraph по имени
    @EntityGraph("User.orders")
    fun findWithOrdersById(id: Long): User?
    
    // EntityGraph по attributePaths
    @EntityGraph(attributePaths = ["orders", "addresses"])
    fun findWithOrdersAndAddressesById(id: Long): User?
    
    // Динамический EntityGraph
    override fun findById(id: Long): Optional<User>
}

// Использование в сервисе
@Service
class UserService(
    private val userRepository: UserRepository,
    private val entityManager: EntityManager
) {
    
    // Случай 1: нужны только orders
    fun getUserWithOrders(id: Long): User? {
        return userRepository.findWithOrdersById(id)
        // SELECT u.*, o.* FROM user u LEFT JOIN orders o ON ...
    }
    
    // Случай 2: нужны orders + addresses
    fun getUserWithOrdersAndAddresses(id: Long): User? {
        return userRepository.findWithOrdersAndAddressesById(id)
        // SELECT u.*, o.*, a.* FROM user u 
        // LEFT JOIN orders o ON ... 
        // LEFT JOIN addresses a ON ...
    }
    
    // Случай 3: динамический EntityGraph через EntityManager
    fun getUserWithCustomGraph(id: Long, vararg attributes: String): User? {
        val entityGraph = entityManager.createEntityGraph(User::class.java)
        attributes.forEach { entityGraph.addAttributeNodes(it) }
        
        return entityManager.find(
            User::class.java,
            id,
            mapOf("javax.persistence.fetchgraph" to entityGraph)
        )
    }
}

// fetchgraph vs loadgraph
// fetchgraph: ТОЛЬКО указанные атрибуты как EAGER, остальные LAZY
// loadgraph: указанные EAGER, остальные по умолчанию (из Entity)

@EntityGraph(attributePaths = ["orders"])
@EntityGraph(type = EntityGraph.EntityGraphType.FETCH)  // ТОЛЬКО orders
fun findWithFetchGraph(id: Long): User?

@EntityGraph(attributePaths = ["orders"])
@EntityGraph(type = EntityGraph.EntityGraphType.LOAD)  // orders + дефолтные EAGER
fun findWithLoadGraph(id: Long): User?
```

### КЕЙС #18 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как реализовать Optimistic Locking? Что делать при OptimisticLockException?

**ОТВЕТ:**
**Optimistic Locking**: проверка версии при UPDATE.

**Механизм:**
1. Загружаем Entity с version=5
2. Изменяем данные
3. UPDATE ... WHERE id=1 AND version=5
4. Если версия изменилась → OptimisticLockException

**ПРИМЕР КОДА:**
```kotlin
@Entity
data class Product(
    @Id val id: Long,
    var name: String,
    var price: BigDecimal,
    var stock: Int,
    
    @Version  // Hibernate управляет версией автоматически!
    var version: Long = 0
)

// Hibernate генерирует SQL:
// UPDATE product SET name=?, price=?, stock=?, version=version+1 
// WHERE id=? AND version=?

@Service
class ProductService(
    private val productRepository: ProductRepository
) {
    
    @Transactional
    fun updatePrice(productId: Long, newPrice: BigDecimal) {
        val product = productRepository.findById(productId).orElseThrow()
        // version = 5
        
        product.price = newPrice
        
        productRepository.save(product)
        // UPDATE ... WHERE id=1 AND version=5
        // Если другая транзакция изменила → version != 5 → exception
    }
    
    // Обработка OptimisticLockException
    @Transactional
    fun decreaseStockWithRetry(productId: Long, quantity: Int, maxRetries: Int = 3) {
        repeat(maxRetries) { attempt ->
            try {
                val product = productRepository.findById(productId).orElseThrow()
                
                require(product.stock >= quantity) {
                    "Insufficient stock: ${product.stock} < $quantity"
                }
                
                product.stock -= quantity
                productRepository.save(product)
                
                return  // Success
                
            } catch (e: OptimisticLockException) {
                if (attempt == maxRetries - 1) {
                    throw ConcurrentModificationException(
                        "Failed to update stock after $maxRetries attempts",
                        e
                    )
                }
                
                logger.warn("Optimistic lock conflict, retrying... (attempt ${attempt + 1})")
                Thread.sleep(100 * (attempt + 1))  // Exponential backoff
            }
        }
    }
}

// REST controller с retry
@RestController
class ProductController(
    private val productService: ProductService
) {
    
    @PostMapping("/api/products/{id}/stock/decrease")
    fun decreaseStock(
        @PathVariable id: Long,
        @RequestBody request: DecreaseStockRequest
    ): ResponseEntity<*> {
        return try {
            productService.decreaseStockWithRetry(id, request.quantity)
            ResponseEntity.ok().build<Any>()
        } catch (e: ConcurrentModificationException) {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "Product was modified by another transaction"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to e.message))
        }
    }
}

// LockModeType для явного управления
@Repository
interface ProductRepository : JpaRepository<Product, Long> {
    
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    override fun findById(id: Long): Optional<Product>
    
    // version инкрементируется ДАЖЕ если нет изменений
}

// Pessimistic Locking (альтернатива)
@Repository
interface ProductRepository : JpaRepository<Product, Long> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    fun findByIdWithLock(@Param("id") id: Long): Product?
    
    // SELECT ... FOR UPDATE (блокирует строку в БД)
}
```

### КЕЙС #19 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работают Cascade operations? Что такое orphanRemoval?

**ОТВЕТ:**
**Cascade**: автоматическое распространение операций на связанные entities.

**orphanRemoval**: удаление "осиротевших" дочерних entities.

**ПРИМЕР КОДА:**
```kotlin
// Cascade operations
@Entity
data class Order(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @OneToMany(
        mappedBy = "order",
        cascade = [CascadeType.ALL],  // Все операции каскадно
        orphanRemoval = true  // Удалять осиротевшие items
    )
    val items: MutableList<OrderItem> = mutableListOf()
)

@Entity
data class OrderItem(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @ManyToOne
    val order: Order,
    
    val productId: Long,
    val quantity: Int
)

// CascadeType.ALL включает:
// PERSIST, MERGE, REMOVE, REFRESH, DETACH

@Service
class OrderService {
    
    @Transactional
    fun createOrder(items: List<OrderItemDto>): Order {
        val order = Order()
        
        items.forEach { dto ->
            val item = OrderItem(
                order = order,
                productId = dto.productId,
                quantity = dto.quantity
            )
            order.items.add(item)
        }
        
        orderRepository.save(order)  // persist на Order
        // CASCADE → автоматически persist на все items!
        // Один save() → сохраняет Order + все OrderItems
        
        return order
    }
    
    @Transactional
    fun deleteOrder(orderId: Long) {
        val order = orderRepository.findById(orderId).orElseThrow()
        
        orderRepository.delete(order)  // remove на Order
        // CASCADE → автоматически remove на все items!
    }
}

// orphanRemoval vs CascadeType.REMOVE
@Entity
data class User(
    @Id val id: Long,
    
    // orphanRemoval=true: удаляет items при удалении из коллекции
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val orders: MutableList<Order> = mutableListOf()
)

@Transactional
fun removeOrderFromUser(userId: Long, orderId: Long) {
    val user = userRepository.findById(userId).orElseThrow()
    val order = user.orders.find { it.id == orderId }
    
    if (order != null) {
        user.orders.remove(order)  // Удаляем из коллекции
        
        // orphanRemoval=true → Hibernate удалит order из БД!
        // DELETE FROM orders WHERE id = ?
    }
}

// Без orphanRemoval:
@OneToMany(mappedBy = "user", cascade = [CascadeType.ALL])
val orders: MutableList<Order> = mutableListOf()

// При remove из коллекции:
user.orders.remove(order)
// Hibernate только обновит FK:
// UPDATE orders SET user_id = NULL WHERE id = ?
// Order останется в БД!

// Selective cascade
@Entity
data class Order(
    @Id val id: Long,
    
    @OneToMany(
        cascade = [CascadeType.PERSIST, CascadeType.MERGE]  // БЕЗ REMOVE!
    )
    val items: List<OrderItem>
)

// При delete(order) → items НЕ удалятся
```

### КЕЙС #20 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как правильно мапить Enum в Hibernate? @Enumerated(STRING) vs (ORDINAL)?

**ОТВЕТ:**
**ORDINAL**: хранит индекс (0, 1, 2) — ОПАСНО!
**STRING**: хранит имя — безопасно, но длиннее

**Проблема ORDINAL**: добавление значения в середину → ломает данные.

**ПРИМЕР КОДА:**
```kotlin
enum class OrderStatus {
    PENDING,    // 0
    CONFIRMED,  // 1
    SHIPPED,    // 2
    DELIVERED   // 3
}

// ПЛОХО: ORDINAL
@Entity
data class Order(
    @Id val id: Long,
    
    @Enumerated(EnumType.ORDINAL)  // Хранит 0, 1, 2, 3
    val status: OrderStatus
)

// Проблема: добавили новый статус в середину
enum class OrderStatus {
    PENDING,       // 0
    PAYMENT_PENDING,  // 1 ← НОВЫЙ!
    CONFIRMED,     // 2 (было 1!)
    SHIPPED,       // 3 (было 2!)
    DELIVERED      // 4 (было 3!)
}

// ВСЕ существующие заказы с status=1 теперь PAYMENT_PENDING вместо CONFIRMED!

// ХОРОШО: STRING
@Entity
data class Order(
    @Id val id: Long,
    
    @Enumerated(EnumType.STRING)  // Хранит "PENDING", "CONFIRMED", ...
    @Column(length = 20)
    val status: OrderStatus
)

// Можно безопасно добавлять новые значения в любое место

// Custom mapping (самый гибкий)
enum class OrderStatus(val code: String) {
    PENDING("P"),
    CONFIRMED("C"),
    SHIPPED("S"),
    DELIVERED("D");
    
    companion object {
        private val map = values().associateBy { it.code }
        fun fromCode(code: String) = map[code] 
            ?: throw IllegalArgumentException("Unknown status code: $code")
    }
}

@Converter(autoApply = true)
class OrderStatusConverter : AttributeConverter<OrderStatus, String> {
    
    override fun convertToDatabaseColumn(attribute: OrderStatus?): String? {
        return attribute?.code
    }
    
    override fun convertToEntityAttribute(dbData: String?): OrderStatus? {
        return dbData?.let { OrderStatus.fromCode(it) }
    }
}

@Entity
data class Order(
    @Id val id: Long,
    
    @Column(length = 1)
    val status: OrderStatus  // Хранится как "P", "C", "S", "D"
    // Converter применяется автоматически (autoApply = true)
)

// PostgreSQL: native ENUM type
// CREATE TYPE order_status AS ENUM ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED');

@Entity
data class Order(
    @Id val id: Long,
    
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "order_status")
    val status: OrderStatus
)
```

### КЕЙС #21 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Hibernate flush mode? Когда происходит flush?

**ОТВЕТ:**
**Flush**: синхронизация Persistence Context с БД (отправка INSERT/UPDATE/DELETE).

**FlushMode:**
- `AUTO` (default): перед query + при commit
- `COMMIT`: только при commit
- `MANUAL`: только явный flush()

**ПРИМЕР КОДА:**
```kotlin
@Service
class FlushDemonstrationService(
    private val entityManager: EntityManager
) {
    
    @Transactional
    fun demonstrateAutoFlush() {
        val user = User(name = "John")
        entityManager.persist(user)
        
        // AUTO flush: перед query
        val found = entityManager
            .createQuery("SELECT u FROM User u WHERE u.name = :name", User::class.java)
            .setParameter("name", "John")
            .singleResult
        
        // Hibernate автоматически flush() ПЕРЕД query!
        // Иначе только что созданный user не был бы найден
        
        assertNotNull(found)
    }
    
    @Transactional
    fun demonstrateCommitFlush() {
        entityManager.flushMode = FlushModeType.COMMIT
        
        val user = User(name = "Jane")
        entityManager.persist(user)
        
        // Query НЕ вызовет flush (flush mode = COMMIT)
        val count = entityManager
            .createQuery("SELECT COUNT(u) FROM User u WHERE u.name = 'Jane'", Long::class.java)
            .singleResult
        
        assertEquals(0, count)  // User ещё НЕ в БД!
        
        entityManager.flush()  // Явный flush
        
        val countAfterFlush = entityManager
            .createQuery("SELECT COUNT(u) FROM User u WHERE u.name = 'Jane'", Long::class.java)
            .singleResult
        
        assertEquals(1, countAfterFlush)
    }
}

// Write-behind: откладывание записи
@Service
class OrderService {
    
    @Transactional
    fun createManyOrders(dtos: List<OrderDto>) {
        dtos.forEachIndexed { index, dto ->
            val order = Order(userId = dto.userId, total = dto.total)
            entityManager.persist(order)
            
            // Без flush: все INSERT накапливаются в памяти
            // Отправятся в БД одним batch при commit
            
            if (index % 50 == 0 && index > 0) {
                entityManager.flush()  // Отправить batch
                entityManager.clear()  // Освободить память
            }
        }
    }
}

// Проблема: flush перед нативным query
@Transactional
fun updateWithNativeQuery() {
    val user = User(name = "John")
    entityManager.persist(user)
    
    // Native query НЕ вызывает автоматический flush!
    val count = entityManager
        .createNativeQuery("SELECT COUNT(*) FROM users WHERE name = 'John'")
        .singleResult
    
    assertEquals(0, count)  // User ещё НЕ в БД!
    
    entityManager.flush()  // Нужен явный flush для native queries
}
```

### КЕЙС #22 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как мапить JSON в PostgreSQL через Hibernate? @Type vs AttributeConverter?

**ОТВЕТ:**
**Варианты:**
1. `@Type` (Hibernate 5) — устарел
2. `AttributeConverter` — стандарт JPA
3. Custom UserType (Hibernate 6)

**ПРИМЕР КОДА:**
```kotlin
// JSON поле в PostgreSQL
// CREATE TABLE orders (
//   id BIGINT PRIMARY KEY,
//   metadata JSONB
// );

// Вариант 1: AttributeConverter (JPA стандарт)
data class OrderMetadata(
    val source: String,
    val referrer: String?,
    val tags: List<String> = emptyList(),
    val customFields: Map<String, String> = emptyMap()
)

@Converter
class OrderMetadataConverter : AttributeConverter<OrderMetadata, String> {
    
    private val objectMapper = ObjectMapper().registerKotlinModule()
    
    override fun convertToDatabaseColumn(attribute: OrderMetadata?): String? {
        return attribute?.let { objectMapper.writeValueAsString(it) }
    }
    
    override fun convertToEntityAttribute(dbData: String?): OrderMetadata? {
        return dbData?.let { objectMapper.readValue(it, OrderMetadata::class.java) }
    }
}

@Entity
data class Order(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @Convert(converter = OrderMetadataConverter::class)
    @Column(columnDefinition = "jsonb")
    val metadata: OrderMetadata
)

// Использование
val order = Order(
    metadata = OrderMetadata(
        source = "mobile_app",
        referrer = "google_ads",
        tags = listOf("new_customer", "promotion"),
        customFields = mapOf("campaign" to "summer_sale")
    )
)

orderRepository.save(order)
// INSERT INTO orders (metadata) VALUES ('{"source":"mobile_app",...}'::jsonb)

// Query по JSON полю (PostgreSQL)
@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query(
        value = """
            SELECT * FROM orders 
            WHERE metadata->>'source' = :source
        """,
        nativeQuery = true
    )
    fun findByMetadataSource(@Param("source") source: String): List<Order>
    
    @Query(
        value = """
            SELECT * FROM orders 
            WHERE metadata @> :metadata::jsonb
        """,
        nativeQuery = true
    )
    fun findByMetadataContains(@Param("metadata") metadata: String): List<Order>
}

// Использование
val orders = orderRepository.findByMetadataSource("mobile_app")
val ordersWithTag = orderRepository.findByMetadataContains("""{"tags": ["promotion"]}""")

// Hibernate 6: @JdbcTypeCode
@Entity
data class Order(
    @Id val id: Long,
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val metadata: OrderMetadata
)
```

### КЕЙС #23 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как правильно мапить @ManyToMany связь? Нужна ли промежуточная Entity?

**ОТВЕТ:**
**Простая ManyToMany**: через join table автоматически
**С доп. полями**: нужна промежуточная Entity

**ПРИМЕР КОДА:**
```kotlin
// Простая ManyToMany (без доп. полей)
@Entity
data class User(
    @Id val id: Long,
    val name: String,
    
    @ManyToMany
    @JoinTable(
        name = "user_roles",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")]
    )
    val roles: MutableSet<Role> = mutableSetOf()
)

@Entity
data class Role(
    @Id val id: Long,
    val name: String,
    
    @ManyToMany(mappedBy = "roles")
    val users: Set<User> = emptySet()
)

// Добавление роли
@Transactional
fun addRoleToUser(userId: Long, roleId: Long) {
    val user = userRepository.findById(userId).orElseThrow()
    val role = roleRepository.findById(roleId).orElseThrow()
    
    user.roles.add(role)
    // INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)
}

// ManyToMany с дополнительными полями (НУЖНА промежуточная Entity!)
// ПЛОХО: нельзя добавить поля в @ManyToMany
@Entity
data class Student(
    @Id val id: Long,
    @ManyToMany
    val courses: Set<Course>
    // Как добавить grade, enrolledAt?
)

// ХОРОШО: промежуточная Entity
@Entity
data class Student(
    @Id val id: Long,
    val name: String,
    
    @OneToMany(mappedBy = "student")
    val enrollments: Set<Enrollment> = emptySet()
)

@Entity
data class Course(
    @Id val id: Long,
    val name: String,
    
    @OneToMany(mappedBy = "course")
    val enrollments: Set<Enrollment> = emptySet()
)

@Entity
data class Enrollment(
    @Id @GeneratedValue
    val id: Long? = null,
    
    @ManyToOne
    @JoinColumn(name = "student_id")
    val student: Student,
    
    @ManyToOne
    @JoinColumn(name = "course_id")
    val course: Course,
    
    val enrolledAt: LocalDateTime = LocalDateTime.now(),
    var grade: Int? = null
)

// Composite key для Enrollment (альтернатива)
@Embeddable
data class EnrollmentId(
    val studentId: Long,
    val courseId: Long
) : Serializable

@Entity
data class Enrollment(
    @EmbeddedId
    val id: EnrollmentId,
    
    @ManyToOne
    @MapsId("studentId")
    @JoinColumn(name = "student_id")
    val student: Student,
    
    @ManyToOne
    @MapsId("courseId")
    @JoinColumn(name = "course_id")
    val course: Course,
    
    val grade: Int? = null
)
```

### КЕЙС #24 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое Hibernate Session flush order? Почему важен порядок операций?

**ОТВЕТ:**
**Flush order** (ActionQueue):
1. OrphanRemoval
2. INSERT
3. UPDATE
4. DELETE (collection elements)
5. INSERT (collection elements)
6. DELETE (entities)

**Причина**: FK constraints.

**ПРИМЕР КОДА:**
```kotlin
@Service
class ComplexOperationService {
    
    @Transactional
    fun demonstrateFlushOrder() {
        // 1. Создаём parent
        val user = User(name = "John")
        entityManager.persist(user)
        
        // 2. Создаём child
        val order = Order(user = user, total = BigDecimal("100"))
        entityManager.persist(order)
        
        // 3. Удаляем другой order
        val oldOrder = entityManager.find(Order::class.java, 999L)
        entityManager.remove(oldOrder)
        
        // 4. Обновляем третий order
        val anotherOrder = entityManager.find(Order::class.java, 888L)
        anotherOrder.total = BigDecimal("200")
        
        entityManager.flush()
        
        // Hibernate выполнит в таком порядке:
        // 1. INSERT INTO users ...  (user)
        // 2. INSERT INTO orders ... (order) — ПОСЛЕ users, чтобы user_id был известен
        // 3. UPDATE orders SET total=200 WHERE id=888
        // 4. DELETE FROM orders WHERE id=999
    }
    
    // Проблема: FK constraint violation
    @Transactional
    fun problematicOrder() {
        val user = User(name = "John")
        val order = Order(user = user, total = BigDecimal("100"))
        
        entityManager.persist(order)  // ОШИБКА!
        entityManager.persist(user)
        
        // Hibernate попытается INSERT order ПЕРЕД user
        // → FK constraint violation (user_id ещё не существует)
        
        // РЕШЕНИЕ: правильный порядок persist
        entityManager.persist(user)  // Сначала parent
        entityManager.persist(order)  // Потом child
        
        // Или cascade:
        user.orders.add(order)
        entityManager.persist(user)  // Автоматически persist order
    }
}

// Управление порядком через @OrderBy, @OrderColumn
@Entity
data class Category(
    @Id val id: Long,
    
    @OneToMany(mappedBy = "category")
    @OrderBy("position ASC, name ASC")  // SQL ORDER BY
    val products: List<Product>
)

@Entity
data class Playlist(
    @Id val id: Long,
    
    @OneToMany(mappedBy = "playlist")
    @OrderColumn(name = "position")  // Отдельная колонка для порядка
    val tracks: List<Track>
)

// Hibernate управляет position автоматически:
playlist.tracks.add(track)  // position = 0
playlist.tracks.add(track2) // position = 1
```

### КЕЙС #25 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как использовать @Formula для вычисляемых полей? В чём отличие от Transient?

**ОТВЕТ:**
**@Formula**: вычисляемое поле через SQL подзапрос (читается из БД)
**@Transient**: вычисляемое поле в Java/Kotlin (не хранится в БД)

**ПРИМЕР КОДА:**
```kotlin
@Entity
data class Order(
    @Id val id: Long,
    
    @OneToMany(mappedBy = "order")
    val items: List<OrderItem>,
    
    // @Formula: SQL подзапрос
    @Formula("(SELECT SUM(oi.price * oi.quantity) FROM order_items oi WHERE oi.order_id = id)")
    val total: BigDecimal? = null,
    
    @Formula("(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = id)")
    val itemCount: Int? = null
)

// При загрузке Order:
// SELECT o.*, 
//        (SELECT SUM(oi.price * oi.quantity) FROM order_items oi WHERE oi.order_id = o.id) as total,
//        (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id) as itemCount
// FROM orders o WHERE o.id = ?

val order = orderRepository.findById(1L)
println("Total: ${order.total}")  // Вычислено в БД!
println("Items: ${order.itemCount}")

// @Transient: вычисляется в коде
@Entity
data class Product(
    @Id val id: Long,
    val price: BigDecimal,
    val taxRate: BigDecimal,
    
    @Transient  // НЕ сохраняется в БД
    val priceWithTax: BigDecimal = price * (BigDecimal.ONE + taxRate)
)

// Проблема @Transient: вычисляется при создании объекта
val product = Product(
    id = 1L,
    price = BigDecimal("100"),
    taxRate = BigDecimal("0.2")
)
println(product.priceWithTax)  // 120

product.price = BigDecimal("200")
println(product.priceWithTax)  // Всё ещё 120! НЕ пересчиталось!

// РЕШЕНИЕ: вычисляемое свойство
@Entity
data class Product(
    @Id val id: Long,
    val price: BigDecimal,
    val taxRate: BigDecimal
) {
    @get:Transient
    val priceWithTax: BigDecimal
        get() = price * (BigDecimal.ONE + taxRate)  // Вычисляется каждый раз
}

product.price = BigDecimal("200")
println(product.priceWithTax)  // 240 — правильно!

// Сложная @Formula с JOIN
@Entity
data class User(
    @Id val id: Long,
    val name: String,
    
    @Formula("""
        (SELECT COUNT(DISTINCT o.id) 
         FROM orders o 
         WHERE o.user_id = id AND o.status = 'COMPLETED')
    """)
    val completedOrdersCount: Int? = null,
    
    @Formula("""
        (SELECT COALESCE(SUM(o.total), 0) 
         FROM orders o 
         WHERE o.user_id = id AND o.status = 'COMPLETED')
    """)
    val totalSpent: BigDecimal? = null
)

// Использование в query
val topCustomers = userRepository.findAll()
    .sortedByDescending { it.totalSpent }
    .take(10)
```

### КЕЙС #26 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает @Embeddable? В чём отличие от @Entity?

**ОТВЕТ:**
**@Embeddable**: встраиваемый объект (не отдельная таблица)
**@Entity**: отдельная таблица

**Использование**: для Value Objects (адрес, координаты, деньги).

**ПРИМЕР КОДА:**
```kotlin
// Value Object
@Embeddable
data class Address(
    val street: String,
    val city: String,
    val zipCode: String,
    val country: String
)

@Entity
data class User(
    @Id val id: Long,
    val name: String,
    
    @Embedded
    val address: Address,  // НЕ отдельная таблица!
    
    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "street", column = Column(name = "billing_street")),
        AttributeOverride(name = "city", column = Column(name = "billing_city")),
        AttributeOverride(name = "zipCode", column = Column(name = "billing_zip")),
        AttributeOverride(name = "country", column = Column(name = "billing_country"))
    )
    val billingAddress: Address?  // Другой префикс колонок
)

// SQL таблица:
// CREATE TABLE users (
//   id BIGINT PRIMARY KEY,
//   name VARCHAR(255),
//   street VARCHAR(255),
//   city VARCHAR(255),
//   zip_code VARCHAR(20),
//   country VARCHAR(100),
//   billing_street VARCHAR(255),
//   billing_city VARCHAR(255),
//   billing_zip VARCHAR(20),
//   billing_country VARCHAR(100)
// );

// Использование
val user = User(
    id = 1L,
    name = "John",
    address = Address(
        street = "123 Main St",
        city = "New York",
        zipCode = "10001",
        country = "USA"
    ),
    billingAddress = Address(
        street = "456 Billing Ave",
        city = "Boston",
        zipCode = "02101",
        country = "USA"
    )
)

// Query по embedded полям
@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    fun findByAddressCity(city: String): List<User>
    // SELECT * FROM users WHERE city = ?
    
    fun findByBillingAddressCountry(country: String): List<User>
    // SELECT * FROM users WHERE billing_country = ?
}

// Nullable Embeddable
@Entity
data class Order(
    @Id val id: Long,
    
    @Embedded
    val shippingAddress: Address?  // Может быть null
)

// Все поля address будут NULL если shippingAddress = null

// @Embeddable с бизнес-логикой
@Embeddable
data class Money(
    val amount: BigDecimal,
    val currency: String
) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return Money(amount + other.amount, currency)
    }
    
    operator fun times(multiplier: Int): Money {
        return Money(amount * multiplier.toBigDecimal(), currency)
    }
}

@Entity
data class Order(
    @Id val id: Long,
    
    @Embedded
    val total: Money
)

val order = Order(
    id = 1L,
    total = Money(BigDecimal("100"), "USD")
)

val doubledTotal = order.total * 2  // Money(200, "USD")
```

---

## Caching подробно

### КЕЙС #11 | Уровень: Senior
**ВОПРОС:** Как работает Second Level Cache в Hibernate? Когда использовать @Cacheable?

**ОТВЕТ:**
```kotlin
// Конфигурация
spring.jpa.properties.hibernate.cache.use_second_level_cache=true
spring.jpa.properties.hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory
spring.cache.jcache.provider=com.hazelcast.cache.HazelcastCachingProvider

// Кэшируемая сущность
@Entity
@Cacheable
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
data class Product(
    @Id val id: Long,
    val name: String,
    val price: BigDecimal
)

// 1-й вызов: запрос к БД
val product1 = productRepository.findById(1L)  // DB query

// 2-й вызов: из кэша
val product2 = productRepository.findById(1L)  // From cache!

// Query cache для запросов
@Query("SELECT p FROM Product p WHERE p.category = :category")
@QueryHints(QueryHint(name = "org.hibernate.cacheable", value = "true"))
fun findByCategory(@Param("category") category: String): List<Product>
```

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 26 | **Стоимость**: ~$3.20

*Версия: 1.0 | Январь 2026*

