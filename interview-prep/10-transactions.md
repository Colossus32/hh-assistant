# Транзакции для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Propagation

### КЕЙС #1 | Уровень: Middle
**ВОПРОС:** В чём разница между REQUIRED, REQUIRES_NEW и NESTED? Когда использовать каждый?

**ОТВЕТ:**
```kotlin
// REQUIRED (по умолчанию): использует существующую транзакцию или создаёт новую
@Transactional  // propagation = Propagation.REQUIRED
fun createOrder(orderDto: OrderDto): Order {
    val order = orderRepository.save(orderDto.toEntity())
    
    // Выполняется в ТОЙ ЖЕ транзакции
    auditService.logOrderCreated(order.id)
    
    return order
}

// Если logOrderCreated() бросит исключение → откатится ВСЯ транзакция

// REQUIRES_NEW: всегда создаёт новую транзакцию (приостанавливает текущую)
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun logOrderCreated(orderId: Long) {
    auditRepository.save(AuditLog(action = "ORDER_CREATED", orderId = orderId))
}

// Если logOrderCreated() упадёт → откатится ТОЛЬКО логирование, заказ сохранится

// NESTED: вложенная транзакция (savepoint)
@Transactional(propagation = Propagation.NESTED)
fun updateInventory(orderId: Long) {
    // Создаётся savepoint внутри родительской транзакции
    // Можно откатить ТОЛЬКО эту часть
}

// Использование
@Transactional
fun processOrder(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    try {
        inventoryService.updateInventory(order.id)  // NESTED
    } catch (e: InsufficientStockException) {
        // Откатываем updateInventory(), но order сохраняется
        order.status = OrderStatus.AWAITING_STOCK
        orderRepository.save(order)
    }
}
```

## Isolation

### КЕЙС #5 | Уровень: Senior
**ВОПРОС:** Что такое Phantom Read, Non-Repeatable Read, Dirty Read? Какие уровни изоляции защищают от них?

**ОТВЕТ:**
```kotlin
// DIRTY READ: чтение незакоммиченных данных
// Thread 1:
@Transactional
fun withdraw(amount: Int) {
    account.balance -= amount
    // НЕ закоммитили
}

// Thread 2:
@Transactional(isolation = Isolation.READ_UNCOMMITTED)  // ПЛОХО
fun getBalance(): Int {
    return account.balance  // Может прочитать незакоммиченное значение!
}

// NON-REPEATABLE READ: разные значения при повторном чтении
@Transactional(isolation = Isolation.READ_COMMITTED)  // По умолчанию в PostgreSQL
fun transfer() {
    val balance1 = accountRepository.findById(1L).balance  // 1000
    // Другая транзакция изменила balance
    val balance2 = accountRepository.findById(1L).balance  // 500 (другое значение!)
}

// PHANTOM READ: появление новых строк
@Transactional(isolation = Isolation.REPEATABLE_READ)
fun countOrders() {
    val count1 = orderRepository.count()  // 100
    // Другая транзакция добавила заказы
    val count2 = orderRepository.count()  // 105 (phantom rows!)
}

// SERIALIZABLE: самый строгий уровень
@Transactional(isolation = Isolation.SERIALIZABLE)
fun criticalOperation() {
    // Транзакции выполняются последовательно
    // Защита от всех аномалий, но медленно
}

// Таблица уровней изоляции:
// READ_UNCOMMITTED: Dirty Read ✓, Non-Repeatable ✓, Phantom ✓
// READ_COMMITTED:   Dirty Read ✗, Non-Repeatable ✓, Phantom ✓
// REPEATABLE_READ:  Dirty Read ✗, Non-Repeatable ✗, Phantom ✓
// SERIALIZABLE:     Dirty Read ✗, Non-Repeatable ✗, Phantom ✗
```

### КЕЙС #2 | Уровень: Senior
**ВОПРОС:** Как работает SUPPORTS и NEVER? В чём их отличие?

**ОТВЕТ:**
```kotlin
// SUPPORTS: выполняется с транзакцией (если есть) или без неё
@Transactional(propagation = Propagation.SUPPORTS)
fun getOrderDetails(orderId: Long): OrderDetails {
    // Если вызвано из транзакции → выполнится в ней
    // Если вызвано вне транзакции → выполнится без транзакции
    return orderRepository.findById(orderId)
}

// Использование: read-only операции, которые могут работать в обоих режимах

// NEVER: НЕ ДОЛЖНА выполняться в транзакции
@Transactional(propagation = Propagation.NEVER)
fun sendEmailNotification(email: String) {
    // Если вызвано из транзакции → IllegalTransactionStateException
    emailService.send(email)
}

// Использование: операции, которые не должны откатываться (email, логи)

// MANDATORY: ТРЕБУЕТ существующей транзакции
@Transactional(propagation = Propagation.MANDATORY)
fun validateOrder(order: Order) {
    // Если вызвано вне транзакции → IllegalTransactionStateException
    // Используется для методов, которые ДОЛЖНЫ быть частью транзакции
}

// NOT_SUPPORTED: приостанавливает транзакцию
@Transactional(propagation = Propagation.NOT_SUPPORTED)
fun logAudit(action: String) {
    // Текущая транзакция приостанавливается
    // Выполняется БЕЗ транзакции
    auditRepository.save(AuditLog(action))
}

// Сравнение:
// SUPPORTS: может быть с транзакцией или без
// NEVER: обязательно без транзакции
// MANDATORY: обязательно с транзакцией
// NOT_SUPPORTED: принудительно без транзакции
```

### КЕЙС #3 | Уровень: Middle
**ВОПРОС:** Что произойдёт, если в методе с @Transactional вызвать другой метод того же класса?

**ОТВЕТ:**
```kotlin
@Service
class OrderService {
    
    // ❌ ПРОБЛЕМА: self-invocation не работает с @Transactional
    @Transactional
    fun createOrder(orderDto: OrderDto): Order {
        val order = orderRepository.save(orderDto.toEntity())
        
        // Вызов метода того же класса → @Transactional НЕ СРАБОТАЕТ
        this.sendNotification(order)
        
        return order
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendNotification(order: Order) {
        // Ожидание: новая транзакция
        // Реальность: выполнится в той же транзакции (или вообще без транзакции)
        notificationRepository.save(Notification(order.id))
    }
}

// Причина: Spring создаёт proxy для транзакций
// this.sendNotification() → вызывается напрямую, минуя proxy

// ✅ РЕШЕНИЕ 1: Вынести в отдельный сервис
@Service
class NotificationService {
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendNotification(order: Order) {
        notificationRepository.save(Notification(order.id))
    }
}

@Service
class OrderService(
    private val notificationService: NotificationService
) {
    @Transactional
    fun createOrder(orderDto: OrderDto): Order {
        val order = orderRepository.save(orderDto.toEntity())
        notificationService.sendNotification(order)  // ✅ Proxy сработает
        return order
    }
}

// ✅ РЕШЕНИЕ 2: Self-injection
@Service
class OrderService(
    @Lazy private val self: OrderService  // Ленивая инъекция самого себя
) {
    @Transactional
    fun createOrder(orderDto: OrderDto): Order {
        val order = orderRepository.save(orderDto.toEntity())
        self.sendNotification(order)  // ✅ Через proxy
        return order
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendNotification(order: Order) {
        notificationRepository.save(Notification(order.id))
    }
}

// ✅ РЕШЕНИЕ 3: ApplicationContext
@Service
class OrderService(
    private val applicationContext: ApplicationContext
) {
    @Transactional
    fun createOrder(orderDto: OrderDto): Order {
        val order = orderRepository.save(orderDto.toEntity())
        
        // Получаем proxy bean
        val proxy = applicationContext.getBean(OrderService::class.java)
        proxy.sendNotification(order)
        
        return order
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendNotification(order: Order) {
        notificationRepository.save(Notification(order.id))
    }
}
```

### КЕЙС #4 | Уровень: Senior
**ВОПРОС:** Как обработать исключение внутри транзакции, но не откатывать её?

**ОТВЕТ:**
```kotlin
// По умолчанию RuntimeException → откат транзакции
// Checked Exception → НЕ откатывает

@Transactional
fun processOrder(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    try {
        // Может упасть, но мы не хотим откатывать order
        inventoryService.reserveStock(order)
    } catch (e: InsufficientStockException) {
        // Откатить ТОЛЬКО reserveStock (если используется REQUIRES_NEW)
        logger.warn("Stock not available, order saved as pending")
        order.status = OrderStatus.AWAITING_STOCK
    }
    // Транзакция НЕ откатится, order сохранится
}

// Проблема: если reserveStock() в той же транзакции → всё откатится

// ✅ РЕШЕНИЕ 1: REQUIRES_NEW для reserveStock
@Service
class InventoryService {
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserveStock(order: Order) {
        // Отдельная транзакция — можно откатить независимо
    }
}

// ✅ РЕШЕНИЕ 2: noRollbackFor
@Transactional(noRollbackFor = [InsufficientStockException::class])
fun processOrder(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    // InsufficientStockException не откатит транзакцию
    inventoryService.reserveStock(order)
}

// ✅ РЕШЕНИЕ 3: TransactionAspectSupport (ручной контроль)
@Transactional
fun processOrderWithManualRollback(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    try {
        inventoryService.reserveStock(order)
    } catch (e: InsufficientStockException) {
        // Помечаем транзакцию для отката, но продолжаем выполнение
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
        
        // Можно логировать, отправить алерт и т.д.
        logger.error("Stock reservation failed", e)
    }
    // Транзакция откатится при завершении метода
}

// ✅ РЕШЕНИЕ 4: Nested transactions (savepoint)
@Transactional
fun processOrderWithSavepoint(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    try {
        // NESTED создаёт savepoint
        inventoryService.reserveStockNested(order)
    } catch (e: InsufficientStockException) {
        // Откат до savepoint, order остаётся
        order.status = OrderStatus.AWAITING_STOCK
    }
}

@Service
class InventoryService {
    
    @Transactional(propagation = Propagation.NESTED)
    fun reserveStockNested(order: Order) {
        // Выполняется с savepoint
    }
}
```

## Isolation

### КЕЙС #5 | Уровень: Senior
**ВОПРОС:** Что такое Phantom Read, Non-Repeatable Read, Dirty Read? Какие уровни изоляции защищают от них?

**ОТВЕТ:**
```kotlin
// DIRTY READ: чтение незакоммиченных данных
// Thread 1:
@Transactional
fun withdraw(amount: Int) {
    account.balance -= amount
    // НЕ закоммитили
}

// Thread 2:
@Transactional(isolation = Isolation.READ_UNCOMMITTED)  // ПЛОХО
fun getBalance(): Int {
    return account.balance  // Может прочитать незакоммиченное значение!
}

// NON-REPEATABLE READ: разные значения при повторном чтении
@Transactional(isolation = Isolation.READ_COMMITTED)  // По умолчанию в PostgreSQL
fun transfer() {
    val balance1 = accountRepository.findById(1L).balance  // 1000
    // Другая транзакция изменила balance
    val balance2 = accountRepository.findById(1L).balance  // 500 (другое значение!)
}

// PHANTOM READ: появление новых строк
@Transactional(isolation = Isolation.REPEATABLE_READ)
fun countOrders() {
    val count1 = orderRepository.count()  // 100
    // Другая транзакция добавила заказы
    val count2 = orderRepository.count()  // 105 (phantom rows!)
}

// SERIALIZABLE: самый строгий уровень
@Transactional(isolation = Isolation.SERIALIZABLE)
fun criticalOperation() {
    // Транзакции выполняются последовательно
    // Защита от всех аномалий, но медленно
}

// Таблица уровней изоляции:
// READ_UNCOMMITTED: Dirty Read ✓, Non-Repeatable ✓, Phantom ✓
// READ_COMMITTED:   Dirty Read ✗, Non-Repeatable ✓, Phantom ✓
// REPEATABLE_READ:  Dirty Read ✗, Non-Repeatable ✗, Phantom ✓
// SERIALIZABLE:     Dirty Read ✗, Non-Repeatable ✗, Phantom ✗
```

### КЕЙС #6 | Уровень: Middle
**ВОПРОС:** Почему важно правильно выбирать уровень изоляции? Какой использовать в production?

**ОТВЕТ:**
```kotlin
// Уровни изоляции: компромисс между consistency и performance

// ❌ READ_UNCOMMITTED: почти никогда не используется
@Transactional(isolation = Isolation.READ_UNCOMMITTED)
fun getApproximateStats() {
    // Можем прочитать данные, которые откатятся
    // Использование: очень редко (логи, метрики)
}

// ✅ READ_COMMITTED (по умолчанию в PostgreSQL, Oracle)
@Transactional(isolation = Isolation.READ_COMMITTED)
fun processPayment(payment: Payment) {
    // Видим только закоммиченные данные
    // Но при повторном чтении могут быть разные значения
    // Использование: большинство CRUD операций
}

// ✅ REPEATABLE_READ (по умолчанию в MySQL)
@Transactional(isolation = Isolation.REPEATABLE_READ)
fun generateReport() {
    // Гарантия: повторное чтение вернёт те же значения
    // Но могут появиться новые строки (phantom read)
    // Использование: отчёты, аналитика
}

// ⚠️ SERIALIZABLE: самый строгий (редко используется)
@Transactional(isolation = Isolation.SERIALIZABLE)
fun criticalFinancialOperation() {
    // Полная изоляция, как будто транзакции выполняются последовательно
    // Производительность страдает, возможны частые rollback из-за serialization failures
    // Использование: критичные финансовые операции
}

// Рекомендации для production:
@Configuration
class TransactionConfig {
    
    // Обычные CRUD операции
    @Bean
    @Primary
    fun defaultTransactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager {
        return JpaTransactionManager(entityManagerFactory).apply {
            defaultTimeout = 30  // 30 секунд
        }
    }
    
    // Критичные операции
    @Bean("criticalTransactionManager")
    fun criticalTransactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager {
        return JpaTransactionManager(entityManagerFactory).apply {
            defaultTimeout = 10
            isNestedTransactionAllowed = true
        }
    }
}

@Service
class PaymentService {
    
    // Обычная операция
    @Transactional  // READ_COMMITTED по умолчанию
    fun createPayment(payment: Payment) {
        paymentRepository.save(payment)
    }
    
    // Критичная операция
    @Transactional(
        isolation = Isolation.SERIALIZABLE,
        transactionManager = "criticalTransactionManager"
    )
    fun processRefund(paymentId: Long) {
        // Строгая изоляция для финансовых операций
    }
}
```

### КЕЙС #7 | Уровень: Senior
**ВОПРОС:** Как работает MVCC в PostgreSQL? Почему это важно для транзакций?

**ОТВЕТ:**
```kotlin
// MVCC (Multi-Version Concurrency Control): каждая транзакция видит snapshot данных

// Пример:
// T1: BEGIN; SELECT balance FROM accounts WHERE id=1; -- видит 1000
// T2: BEGIN; UPDATE accounts SET balance=500 WHERE id=1; COMMIT;
// T1: SELECT balance FROM accounts WHERE id=1; -- всё ещё видит 1000 (в REPEATABLE_READ)

// PostgreSQL хранит несколько версий каждой строки
// Каждая версия помечена xmin (транзакция создания) и xmax (транзакция удаления)

@Entity
@Table(name = "accounts")
data class Account(
    @Id val id: Long,
    var balance: BigDecimal,
    
    // PostgreSQL внутренние поля (невидимы для приложения):
    // xmin: ID транзакции, которая создала эту версию
    // xmax: ID транзакции, которая удалила/обновила эту версию
    // ctid: физическое расположение строки
)

// Преимущества MVCC:
// 1. Readers не блокируют writers
// 2. Writers не блокируют readers
// 3. Высокий concurrency

// Недостатки:
// 1. "Мёртвые" версии строк (требуется VACUUM)
// 2. Bloat в таблицах

// Настройка VACUUM
"""
-- Автоматический VACUUM
ALTER TABLE accounts SET (autovacuum_vacuum_scale_factor = 0.1);

-- Мониторинг bloat
SELECT
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
  n_dead_tup AS dead_tuples
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
"""

// Использование в Kotlin
@Service
class AccountService {
    
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun transferMoney(fromId: Long, toId: Long, amount: BigDecimal) {
        // MVCC: видим snapshot данных на момент начала транзакции
        val from = accountRepository.findById(fromId)
        val to = accountRepository.findById(toId)
        
        // UPDATE создаст новую версию строки
        from.balance -= amount
        to.balance += amount
        
        // Если другая транзакция обновила эти строки → conflict
        accountRepository.saveAll(listOf(from, to))
    }
    
    // FOR UPDATE блокирует строку (MVCC не помогает)
    @Transactional
    fun transferWithLock(fromId: Long, toId: Long, amount: BigDecimal) {
        // SELECT ... FOR UPDATE — блокирует последнюю версию строки
        val from = accountRepository.findByIdForUpdate(fromId)
        val to = accountRepository.findByIdForUpdate(toId)
        
        from.balance -= amount
        to.balance += amount
        
        accountRepository.saveAll(listOf(from, to))
    }
}
```

### КЕЙС #8 | Уровень: Middle
**ВОПРОС:** Что такое timeout в транзакциях? Как его настроить?

**ОТВЕТ:**
```kotlin
// Timeout: максимальное время выполнения транзакции
// Если превышен → TransactionTimedOutException

// 1. Глобальный timeout
@Configuration
class TransactionConfig {
    
    @Bean
    fun transactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager {
        return JpaTransactionManager(entityManagerFactory).apply {
            defaultTimeout = 30  // 30 секунд для всех транзакций
        }
    }
}

// 2. Timeout для конкретного метода
@Transactional(timeout = 10)  // 10 секунд
fun processOrder(orderDto: OrderDto) {
    // Если выполнение займёт > 10 секунд → откат
}

// 3. PostgreSQL statement_timeout
@Configuration
class DataSourceConfig {
    
    @Bean
    fun dataSource(): DataSource {
        return HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
            username = "user"
            password = "password"
            
            // Timeout на уровне JDBC
            connectionTimeout = 10_000  // 10 сек на установку соединения
            validationTimeout = 5_000   // 5 сек на валидацию соединения
            
            // PostgreSQL specific
            addDataSourceProperty("statement_timeout", "30000")  // 30 сек на запрос
        }
    }
}

// 4. JPA query hints
@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query("SELECT o FROM Order o WHERE o.status = :status")
    @QueryHints(QueryHint(name = "javax.persistence.query.timeout", value = "10000"))
    fun findByStatus(@Param("status") status: OrderStatus): List<Order>
}

// 5. Программный timeout
@Service
class OrderService {
    
    @Transactional
    fun processOrderWithTimeout(orderDto: OrderDto) {
        val startTime = System.currentTimeMillis()
        
        val order = orderRepository.save(orderDto.toEntity())
        
        // Проверяем время выполнения
        if (System.currentTimeMillis() - startTime > 10_000) {
            throw TransactionTimedOutException("Processing took too long")
        }
        
        inventoryService.reserveStock(order)
    }
}

// Best practices:
// - Короткие транзакции (< 5 сек)
// - Timeout для защиты от зависаний
// - Мониторинг длительных транзакций

@Component
class TransactionMonitor {
    
    @Scheduled(fixedDelay = 60000)
    fun checkLongRunningTransactions() {
        val longTransactions = entityManager.createNativeQuery("""
            SELECT pid, now() - query_start AS duration, query
            FROM pg_stat_activity
            WHERE state = 'active'
              AND now() - query_start > interval '30 seconds'
        """).resultList
        
        if (longTransactions.isNotEmpty()) {
            logger.warn("Long running transactions detected: $longTransactions")
        }
    }
}
```

### КЕЙС #9 | Уровень: Senior
**ВОПРОС:** Как работают транзакции с connection pool? Какие проблемы могут возникнуть?

**ОТВЕТ:**
```kotlin
// Connection Pool: пул соединений к БД (HikariCP в Spring Boot)
// Каждая транзакция берёт connection из pool

// Проблема 1: Pool exhaustion (нехватка connections)
@Configuration
class HikariConfig {
    
    @Bean
    fun dataSource(): DataSource {
        return HikariDataSource().apply {
            maximumPoolSize = 10  // Максимум 10 соединений
            minimumIdle = 5       // Минимум 5 idle соединений
            connectionTimeout = 30_000  // 30 сек ожидания connection
        }
    }
}

// ❌ ПРОБЛЕМА: вложенные транзакции с REQUIRES_NEW
@Transactional
fun processOrders() {
    orderRepository.findAll().forEach { order ->
        // Каждая итерация берёт новый connection (REQUIRES_NEW)
        auditService.logOrderProcessed(order.id)  // REQUIRES_NEW
    }
    // Если заказов > 10 → pool exhaustion → connection timeout
}

@Service
class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun logOrderProcessed(orderId: Long) {
        auditRepository.save(AuditLog(orderId))
    }
}

// ✅ РЕШЕНИЕ: batch processing
@Transactional
fun processOrdersBatch() {
    val orders = orderRepository.findAll()
    
    // Обрабатываем батчами
    orders.chunked(100).forEach { batch ->
        batch.forEach { order ->
            process(order)
        }
        entityManager.flush()
        entityManager.clear()
    }
    
    // Логируем ПОСЛЕ основной транзакции
    auditService.logBatch(orders.map { it.id })
}

// Проблема 2: Connection leak
@Service
class LeakyService(
    private val dataSource: DataSource
) {
    
    // ❌ ПЛОХО: connection не возвращается в pool
    fun getBalance(accountId: Long): BigDecimal {
        val connection = dataSource.connection
        val statement = connection.prepareStatement("SELECT balance FROM accounts WHERE id = ?")
        statement.setLong(1, accountId)
        val rs = statement.executeQuery()
        // НЕ закрыли connection → утечка
        return if (rs.next()) rs.getBigDecimal("balance") else BigDecimal.ZERO
    }
    
    // ✅ ХОРОШО: use() автоматически закрывает
    fun getBalanceSafe(accountId: Long): BigDecimal {
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT balance FROM accounts WHERE id = ?").use { stmt ->
                stmt.setLong(1, accountId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getBigDecimal("balance") else BigDecimal.ZERO
                }
            }
        }
    }
}

// Проблема 3: Долгие транзакции держат connection
@Transactional
fun processOrderSlow(orderDto: OrderDto) {
    val order = orderRepository.save(orderDto.toEntity())
    
    // ❌ ПЛОХО: HTTP вызов внутри транзакции
    Thread.sleep(5000)  // Connection занят 5 секунд
    val result = externalApiClient.call()
    
    order.externalId = result.id
    orderRepository.save(order)
}

// ✅ ХОРОШО: разделяем на части
fun processOrderFast(orderDto: OrderDto) {
    // Быстрая транзакция
    val orderId = createOrder(orderDto)
    
    // HTTP вызов БЕЗ транзакции
    val result = externalApiClient.call()
    
    // Быстрая транзакция для обновления
    updateOrderExternalId(orderId, result.id)
}

@Transactional
fun createOrder(orderDto: OrderDto): Long {
    return orderRepository.save(orderDto.toEntity()).id
}

@Transactional
fun updateOrderExternalId(orderId: Long, externalId: String) {
    val order = orderRepository.findById(orderId)
    order.externalId = externalId
}

// Мониторинг pool
@Component
class PoolMonitor(
    private val dataSource: HikariDataSource
) {
    
    @Scheduled(fixedDelay = 60000)
    fun logPoolStats() {
        val poolStats = dataSource.hikariPoolMXBean
        logger.info("""
            Pool stats:
            - Active connections: ${poolStats.activeConnections}
            - Idle connections: ${poolStats.idleConnections}
            - Total connections: ${poolStats.totalConnections}
            - Threads waiting: ${poolStats.threadsAwaitingConnection}
        """)
        
        if (poolStats.threadsAwaitingConnection > 0) {
            logger.warn("Threads are waiting for connections! Consider increasing pool size.")
        }
    }
}
```

## Locking

### КЕЙС #10 | Уровень: Senior
**ВОПРОС:** В чём разница между оптимистичной и пессимистичной блокировкой? Когда использовать каждую?

**ОТВЕТ:**
```kotlin
// ОПТИМИСТИЧНАЯ: проверка версии при commit
@Entity
data class Account(
    @Id val id: Long,
    var balance: BigDecimal,
    
    @Version  // Hibernate автоматически управляет версией
    var version: Long = 0
)

@Transactional
fun withdraw(accountId: Long, amount: BigDecimal) {
    val account = accountRepository.findById(accountId)
    account.balance -= amount
    accountRepository.save(account)  
    // Hibernate: UPDATE accounts SET balance=?, version=version+1 WHERE id=? AND version=?
    // Если version изменилась → OptimisticLockException
}

// Подходит для: редкие конфликты, read-heavy нагрузка

// ПЕССИМИСТИЧНАЯ: блокировка строки при чтении
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
fun findByIdForUpdate(@Param("id") id: Long): Account?

@Transactional
fun withdrawPessimistic(accountId: Long, amount: BigDecimal) {
    // SELECT ... FOR UPDATE — блокирует строку
    val account = accountRepository.findByIdForUpdate(accountId)
    account.balance -= amount
    accountRepository.save(account)
}
// Другая транзакция будет ЖДАТЬ разблокировки

// Подходит для: частые конфликты, критичные операции (платежи)

// DEADLOCK: взаимная блокировка
// Thread 1: блокирует A, ждёт B
// Thread 2: блокирует B, ждёт A
// Решение: всегда блокировать в одном порядке (по id)
```

### КЕЙС #11 | Уровень: Senior
**ВОПРОС:** Как обрабатывать deadlock в приложении? Какие стратегии retry?

**ОТВЕТ:**
```kotlin
// Deadlock: PostgreSQL автоматически обнаруживает и откатывает одну из транзакций
// PSQLException: deadlock detected

// Стратегия 1: Retry с exponential backoff
@Service
class DeadlockRetryService {
    
    fun transferWithRetry(fromId: Long, toId: Long, amount: BigDecimal) {
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            try {
                transfer(fromId, toId, amount)
                return  // Успешно
            } catch (e: Exception) {
                if (isDeadlock(e) && attempts < maxAttempts - 1) {
                    attempts++
                    val delay = (100 * 2.0.pow(attempts)).toLong()
                    Thread.sleep(delay + Random.nextLong(0, 100))  // Jitter
                    logger.warn("Deadlock detected, retry #$attempts")
                } else {
                    throw e
                }
            }
        }
    }
    
    @Transactional
    fun transfer(fromId: Long, toId: Long, amount: BigDecimal) {
        val from = accountRepository.findByIdForUpdate(fromId)
        val to = accountRepository.findByIdForUpdate(toId)
        from.balance -= amount
        to.balance += amount
    }
    
    private fun isDeadlock(e: Exception): Boolean {
        return e is PessimisticLockException || 
               e.cause?.message?.contains("deadlock") == true
    }
}

// Стратегия 2: Spring Retry
@Configuration
@EnableRetry
class RetryConfig

@Service
class TransferService {
    
    @Retryable(
        value = [PessimisticLockException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 100, multiplier = 2.0, random = true)
    )
    @Transactional
    fun transferWithSpringRetry(fromId: Long, toId: Long, amount: BigDecimal) {
        val from = accountRepository.findByIdForUpdate(fromId)
        val to = accountRepository.findByIdForUpdate(toId)
        from.balance -= amount
        to.balance += amount
    }
    
    @Recover
    fun recover(e: PessimisticLockException, fromId: Long, toId: Long, amount: BigDecimal) {
        logger.error("Failed to transfer after retries", e)
        throw TransferFailedException("Unable to complete transfer")
    }
}

// Стратегия 3: Предотвращение deadlock
@Transactional
fun transferNoDeadlock(fromId: Long, toId: Long, amount: BigDecimal) {
    // ВАЖНО: блокируем в одном порядке (по возрастанию id)
    val (firstId, secondId) = if (fromId < toId) fromId to toId else toId to fromId
    
    val first = accountRepository.findByIdForUpdate(firstId)
    val second = accountRepository.findByIdForUpdate(secondId)
    
    if (fromId < toId) {
        first.balance -= amount
        second.balance += amount
    } else {
        second.balance -= amount
        first.balance += amount
    }
}

// Стратегия 4: Мониторинг deadlock
@Component
class DeadlockMonitor {
    
    private val deadlockCounter = AtomicLong(0)
    
    @EventListener
    fun handleDeadlock(event: DeadlockEvent) {
        deadlockCounter.incrementAndGet()
        logger.error("Deadlock detected: ${event.message}")
    }
    
    @Scheduled(fixedDelay = 60000)
    fun reportDeadlocks() {
        val count = deadlockCounter.getAndSet(0)
        if (count > 0) {
            logger.warn("Deadlocks in last minute: $count")
        }
    }
}
```

### КЕЙС #12 | Уровень: Middle
**ВОПРОС:** В чём разница между PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT?

**ОТВЕТ:**
```kotlin
// PESSIMISTIC_READ: shared lock (FOR SHARE в PostgreSQL)
@Lock(LockModeType.PESSIMISTIC_READ)
@Query("SELECT a FROM Account a WHERE a.id = :id")
fun findByIdForRead(@Param("id") id: Long): Account?

@Transactional
fun readAccount(accountId: Long) {
    // SELECT ... FOR SHARE
    // Другие транзакции могут ЧИТАТЬ, но не ИЗМЕНЯТЬ
    val account = accountRepository.findByIdForRead(accountId)
}

// PESSIMISTIC_WRITE: exclusive lock (FOR UPDATE в PostgreSQL)
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
fun findByIdForUpdate(@Param("id") id: Long): Account?

@Transactional
fun updateAccount(accountId: Long) {
    // SELECT ... FOR UPDATE
    // Другие транзакции НЕ МОГУТ ни читать, ни изменять
    val account = accountRepository.findByIdForUpdate(accountId)
    account.balance += BigDecimal.TEN
}

// PESSIMISTIC_FORCE_INCREMENT: lock + increment version
@Lock(LockModeType.PESSIMISTIC_FORCE_INCREMENT)
@Query("SELECT a FROM Account a WHERE a.id = :id")
fun findByIdForUpdateAndIncrement(@Param("id") id: Long): Account?

@Entity
data class Account(
    @Id val id: Long,
    var balance: BigDecimal,
    @Version var version: Long = 0  // Обязательно с @Version
)

@Transactional
fun updateWithVersionIncrement(accountId: Long) {
    // SELECT ... FOR UPDATE
    // + автоматически инкрементирует version при commit
    val account = accountRepository.findByIdForUpdateAndIncrement(accountId)
    account.balance += BigDecimal.TEN
    // При commit: version++
}

// Сравнение:
// PESSIMISTIC_READ: другие могут читать (shared lock)
// PESSIMISTIC_WRITE: никто не может ни читать, ни писать (exclusive lock)
// PESSIMISTIC_FORCE_INCREMENT: как WRITE + инкремент версии

// Использование с timeout
@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "javax.persistence.lock.timeout", value = "5000"))
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    fun findByIdForUpdateWithTimeout(@Param("id") id: Long): Account?
}

// Если lock не получен за 5 сек → LockTimeoutException
```

## Distributed Transactions

### КЕЙС #13 | Уровень: Senior
**ВОПРОС:** Как работают транзакции в микросервисной архитектуре? Что такое Saga pattern?

**ОТВЕТ:**
```kotlin
// В микросервисах нет распределённых ACID транзакций
// Используются паттерны: Saga, Transactional Outbox, Eventual Consistency

// SAGA PATTERN: последовательность локальных транзакций
// Каждая транзакция публикует событие для следующего шага
// При ошибке — компенсирующие транзакции (откат)

// Пример: создание заказа
// 1. OrderService: создаёт заказ → событие OrderCreated
// 2. InventoryService: резервирует товар → событие StockReserved
// 3. PaymentService: списывает деньги → событие PaymentProcessed
// 4. DeliveryService: создаёт доставку → событие DeliveryScheduled

// Если на шаге 3 ошибка:
// 3. PaymentService: компенсация → RefundProcessed
// 2. InventoryService: компенсация → StockReleased
// 1. OrderService: компенсация → OrderCancelled

// Реализация с Kafka
@Service
class OrderSagaOrchestrator(
    private val orderService: OrderService,
    private val kafkaTemplate: KafkaTemplate<String, SagaEvent>
) {
    
    fun startOrderSaga(orderDto: OrderDto) {
        // Шаг 1: создаём заказ
        val order = orderService.createOrder(orderDto)
        
        // Публикуем событие
        kafkaTemplate.send("saga-events", OrderCreatedEvent(order.id, order.items))
    }
    
    @KafkaListener(topics = ["saga-events"])
    fun handleSagaEvents(event: SagaEvent) {
        when (event) {
            is OrderCreatedEvent -> {
                // Шаг 2: резервируем товар
                try {
                    inventoryService.reserveStock(event.orderId, event.items)
                    kafkaTemplate.send("saga-events", StockReservedEvent(event.orderId))
                } catch (e: Exception) {
                    // Компенсация
                    kafkaTemplate.send("saga-events", OrderCancelledEvent(event.orderId))
                }
            }
            
            is StockReservedEvent -> {
                // Шаг 3: списываем деньги
                try {
                    paymentService.processPayment(event.orderId)
                    kafkaTemplate.send("saga-events", PaymentProcessedEvent(event.orderId))
                } catch (e: Exception) {
                    // Компенсация: возвращаем товар
                    kafkaTemplate.send("saga-events", StockReleasedEvent(event.orderId))
                    kafkaTemplate.send("saga-events", OrderCancelledEvent(event.orderId))
                }
            }
            
            is PaymentProcessedEvent -> {
                // Шаг 4: создаём доставку
                deliveryService.scheduleDelivery(event.orderId)
                kafkaTemplate.send("saga-events", DeliveryScheduledEvent(event.orderId))
            }
            
            // Обработка компенсаций
            is StockReleasedEvent -> inventoryService.releaseStock(event.orderId)
            is OrderCancelledEvent -> orderService.cancelOrder(event.orderId)
        }
    }
}

// State Machine для Saga
@Configuration
class SagaStateMachineConfig : StateMachineConfigurerAdapter<SagaState, SagaEvent>() {
    
    override fun configure(states: StateMachineStateConfigurer<SagaState, SagaEvent>) {
        states
            .withStates()
            .initial(SagaState.ORDER_CREATED)
            .state(SagaState.STOCK_RESERVED)
            .state(SagaState.PAYMENT_PROCESSED)
            .end(SagaState.COMPLETED)
            .end(SagaState.FAILED)
    }
    
    override fun configure(transitions: StateMachineTransitionConfigurer<SagaState, SagaEvent>) {
        transitions
            .withExternal()
                .source(SagaState.ORDER_CREATED).target(SagaState.STOCK_RESERVED)
                .event(SagaEvent.STOCK_RESERVED)
            .and()
            .withExternal()
                .source(SagaState.STOCK_RESERVED).target(SagaState.PAYMENT_PROCESSED)
                .event(SagaEvent.PAYMENT_PROCESSED)
            .and()
            .withExternal()
                .source(SagaState.PAYMENT_PROCESSED).target(SagaState.COMPLETED)
                .event(SagaEvent.DELIVERY_SCHEDULED)
            .and()
            .withExternal()
                .source(SagaState.ORDER_CREATED).target(SagaState.FAILED)
                .event(SagaEvent.STOCK_RESERVATION_FAILED)
    }
}
```

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$1.10

*Версия: 2.0 | Январь 2026*

