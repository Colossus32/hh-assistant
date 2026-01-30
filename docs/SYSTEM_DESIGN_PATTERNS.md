# 🎯 Архитектурные паттерны системного дизайна

## 📚 Содержание
1. [Микросервисные паттерны](#микросервисные-паттерны)
2. [Паттерны коммуникации](#паттерны-коммуникации)
3. [Паттерны данных](#паттерны-данных)
4. [Паттерны отказоустойчивости](#паттерны-отказоустойчивости)
5. [Паттерны безопасности](#паттерны-безопасности)

---

## Микросервисные паттерны

### 1. API Gateway Pattern

**Проблема:** Клиенты должны знать адреса всех микросервисов

**Решение:** Единая точка входа для всех клиентов

```
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Mobile  │  │  Web    │  │  Admin  │
│  App    │  │  App    │  │  Panel  │
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
     └────────────┴────────────┘
                  │
         ┌────────▼────────┐
         │   API Gateway   │
         │  (Nginx/Kong)   │
         └────────┬────────┘
                  │
     ┌────────────┼────────────┐
     │            │            │
┌────▼───┐  ┌────▼───┐  ┌────▼───┐
│ User   │  │ Order  │  │Payment │
│Service │  │Service │  │Service │
└────────┘  └────────┘  └────────┘
```

**Функции API Gateway:**
- **Routing** - маршрутизация запросов
- **Authentication** - проверка токенов
- **Rate Limiting** - ограничение запросов
- **Load Balancing** - балансировка нагрузки
- **Request/Response Transformation** - преобразование данных
- **Logging & Monitoring** - логирование и мониторинг

**Пример (Spring Cloud Gateway):**
```kotlin
@Configuration
class GatewayConfig {
    @Bean
    fun routeLocator(builder: RouteLocatorBuilder): RouteLocator {
        return builder.routes()
            .route("user-service") { r ->
                r.path("/api/users/**")
                    .uri("lb://user-service")
            }
            .route("order-service") { r ->
                r.path("/api/orders/**")
                    .uri("lb://order-service")
            }
            .build()
    }
}
```

---

### 2. Service Discovery Pattern

**Проблема:** Как найти адрес микросервиса? IP адреса меняются при деплое

**Решение:** Централизованный реестр сервисов

#### Client-Side Discovery

```
Client ──► Service Registry (Eureka/Consul)
              │
              ▼
         Get Service List
              │
              ▼
Client ──► Service Instance (direct connection)
```

#### Server-Side Discovery

```
Client ──► Load Balancer ──► Service Registry
                              │
                              ▼
                         Get Service List
                              │
                              ▼
Client ──► Load Balancer ──► Service Instance
```

**Реализации:**
- **Eureka** (Netflix) - для Spring Cloud
- **Consul** (HashiCorp) - более универсальный
- **Kubernetes** - встроенный service discovery

**Пример (Eureka):**
```kotlin
// Service Registration
@SpringBootApplication
@EnableEurekaClient
class UserServiceApplication

// Service Discovery
@Service
class OrderServiceClient {
    @Autowired
    lateinit var discoveryClient: DiscoveryClient
    
    fun getUser(userId: String): User {
        val instances = discoveryClient.getInstances("user-service")
        val serviceUrl = instances[0].uri.toString()
        return restTemplate.getForObject("$serviceUrl/users/$userId", User::class.java)
    }
}
```

---

### 3. Circuit Breaker Pattern

**Проблема:** Если один сервис упал, все запросы к нему блокируются, тратятся ресурсы

**Решение:** Автоматически "разрывать" соединение при ошибках

```
┌─────────┐
│ Service │
│    A    │
└────┬────┘
     │
     ▼
┌──────────────┐
│   Circuit    │
│   Breaker    │
└────┬─────────┘
     │
     ▼
┌─────────┐
│ Service │
│    B    │
└─────────┘

States:
- CLOSED: нормальная работа
- OPEN: сервис недоступен, сразу возвращаем ошибку
- HALF_OPEN: пробуем один запрос, если успех → CLOSED
```

**Реализация (Resilience4j):**
```kotlin
@Service
class PaymentServiceClient {
    private val circuitBreaker = CircuitBreaker.of("payment-service") {
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f) // 50% ошибок → OPEN
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .build()
    }
    
    fun processPayment(orderId: String): PaymentResult {
        return circuitBreaker.executeSupplier {
            paymentClient.pay(orderId)
        }
    }
}
```

**Преимущества:**
- Быстрый failover
- Защита от каскадных отказов
- Экономия ресурсов

---

### 4. Saga Pattern

**Проблема:** Distributed transactions (2PC) медленные и не масштабируются

**Решение:** Последовательность локальных транзакций с компенсацией

#### Choreography (Оркестрация через события)

```
Order Service ──► Create Order ──► Payment Service
                                      │
                                      ▼
                                   Charge Card
                                      │
                                      ▼
                                   Inventory Service
                                      │
                                      ▼
                                   Reserve Items
```

**Если ошибка:** Каждый сервис знает как откатить свою операцию

#### Orchestration (Центральный оркестратор)

```
         ┌──────────────┐
         │   Saga      │
         │ Orchestrator│
         └──────┬───────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
Order      Payment    Inventory
Service    Service    Service
```

**Пример (Orchestration):**
```kotlin
@Service
class OrderSagaOrchestrator {
    
    suspend fun createOrder(order: Order): OrderResult {
        return try {
            // Step 1: Create order
            val orderId = orderService.createOrder(order)
            
            // Step 2: Charge payment
            val payment = paymentService.charge(orderId, order.total)
            
            // Step 3: Reserve inventory
            inventoryService.reserve(orderId, order.items)
            
            OrderResult.Success(orderId)
        } catch (e: Exception) {
            // Compensate
            compensate(orderId)
            OrderResult.Failure(e.message)
        }
    }
    
    private suspend fun compensate(orderId: String) {
        // Откатываем в обратном порядке
        inventoryService.release(orderId)
        paymentService.refund(orderId)
        orderService.cancel(orderId)
    }
}
```

---

### 5. CQRS (Command Query Responsibility Segregation)

**Проблема:** Одна модель для чтения и записи не оптимальна

**Решение:** Разделить модели для чтения и записи

```
Write Side (Command)          Read Side (Query)
┌──────────────┐              ┌──────────────┐
│   Write      │              │    Read      │
│   Model      │              │    Model     │
│  (Normalized)│              │ (Denormalized)
└──────┬───────┘              └──────┬───────┘
       │                             │
       │ Event                       │
       ▼                             │
┌──────────────┐                    │
│   Event      │                    │
│   Store      │                    │
└──────┬───────┘                    │
       │                             │
       └─────────────┬───────────────┘
                     │
                     ▼
              ┌──────────────┐
              │   Projection │
              │   (Updates)  │
              └──────────────┘
```

**Пример:**
```kotlin
// Write Model (Command)
@Entity
class Order {
    @Id
    var id: String
    var userId: String
    var items: List<OrderItem>
    var status: OrderStatus
}

// Read Model (Query) - оптимизирован для чтения
data class OrderView(
    val id: String,
    val userName: String,  // Денормализовано
    val totalAmount: BigDecimal,
    val itemCount: Int,
    val status: String
)

// Event Handler обновляет Read Model
@EventHandler
class OrderViewProjection {
    fun handle(orderCreated: OrderCreatedEvent) {
        orderViewRepository.save(
            OrderView(
                id = orderCreated.orderId,
                userName = userService.getUser(orderCreated.userId).name,
                totalAmount = orderCreated.total,
                itemCount = orderCreated.items.size,
                status = "CREATED"
            )
        )
    }
}
```

**Когда использовать:**
- Разные требования к чтению и записи
- Нужна высокая производительность чтения
- Сложные запросы для чтения

---

## Паттерны коммуникации

### 1. Synchronous Communication (REST)

**Когда использовать:**
- Нужен немедленный ответ
- Простые запросы
- Низкая латентность критична

```
Client ──► Service A ──► Service B
         │              │
         │              │
         └──────────────┘
         Response
```

**Пример:**
```kotlin
@RestController
class OrderController {
    @Autowired
    lateinit var paymentClient: PaymentClient
    
    @PostMapping("/orders")
    fun createOrder(@RequestBody order: Order): OrderResponse {
        // Синхронный вызов
        val payment = paymentClient.charge(order.total)
        return OrderResponse(order.id, payment.status)
    }
}
```

**Проблемы:**
- Tight coupling (тесная связь)
- Каскадные отказы
- Блокирующие вызовы

---

### 2. Asynchronous Communication (Message Queue)

**Когда использовать:**
- Не нужен немедленный ответ
- Долгие операции
- Нужна отказоустойчивость

```
Service A ──► Message Queue ──► Service B
            (Kafka/RabbitMQ)      (async)
```

**Пример (Kafka):**
```kotlin
// Producer
@Service
class OrderService {
    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>
    
    fun createOrder(order: Order) {
        orderRepository.save(order)
        
        // Асинхронная отправка события
        kafkaTemplate.send("order-created", order.id, order.toJson())
    }
}

// Consumer
@KafkaListener(topics = ["order-created"])
fun handleOrderCreated(message: String) {
    val order = parseOrder(message)
    inventoryService.reserve(order.items)
    notificationService.sendEmail(order.userId)
}
```

**Преимущества:**
- Loose coupling
- Отказоустойчивость (сообщения сохраняются)
- Масштабируемость
- Буферизация нагрузки

---

### 3. Event-Driven Architecture

**Концепция:** Сервисы общаются через события

```
Order Service ──► OrderCreated Event ──► Payment Service
                                          Inventory Service
                                          Notification Service
                                          Analytics Service
```

**Пример:**
```kotlin
// Event
data class OrderCreatedEvent(
    val orderId: String,
    val userId: String,
    val items: List<OrderItem>,
    val total: BigDecimal,
    val timestamp: Instant
)

// Publisher
@Service
class OrderEventPublisher {
    @Autowired
    lateinit var eventBus: EventBus
    
    fun publishOrderCreated(order: Order) {
        val event = OrderCreatedEvent(
            orderId = order.id,
            userId = order.userId,
            items = order.items,
            total = order.total,
            timestamp = Instant.now()
        )
        eventBus.publish(event)
    }
}

// Subscribers
@EventListener
class PaymentEventHandler {
    fun handle(event: OrderCreatedEvent) {
        paymentService.charge(event.orderId, event.total)
    }
}

@EventListener
class InventoryEventHandler {
    fun handle(event: OrderCreatedEvent) {
        inventoryService.reserve(event.orderId, event.items)
    }
}
```

---

## Паттерны данных

### 1. Database per Service

**Проблема:** Общая БД создает tight coupling между сервисами

**Решение:** Каждый сервис имеет свою БД

```
User Service ──► User DB (PostgreSQL)
Order Service ──► Order DB (PostgreSQL)
Analytics Service ──► Analytics DB (MongoDB)
```

**Правило:** Сервис может обращаться ТОЛЬКО к своей БД

**Как обмениваться данными:**
- Через API (синхронно)
- Через события (асинхронно)

---

### 2. Event Sourcing

**Концепция:** Хранить не состояние, а события

```
Traditional:
Order(id=1, status=CREATED, total=100)
Order(id=1, status=PAID, total=100)
Order(id=1, status=SHIPPED, total=100)

Event Sourcing:
OrderCreated(id=1, total=100)
OrderPaid(id=1)
OrderShipped(id=1)

Current state = replay всех событий
```

**Пример:**
```kotlin
// Events
sealed class OrderEvent
data class OrderCreated(val orderId: String, val items: List<Item>) : OrderEvent()
data class OrderPaid(val orderId: String) : OrderEvent()
data class OrderShipped(val orderId: String) : OrderEvent()

// Event Store
interface EventStore {
    fun save(aggregateId: String, events: List<OrderEvent>)
    fun load(aggregateId: String): List<OrderEvent>
}

// Aggregate (восстанавливает состояние из событий)
class Order(aggregateId: String) {
    private var status: OrderStatus = OrderStatus.CREATED
    private var items: List<Item> = emptyList()
    
    fun apply(event: OrderEvent) {
        when (event) {
            is OrderCreated -> {
                items = event.items
                status = OrderStatus.CREATED
            }
            is OrderPaid -> status = OrderStatus.PAID
            is OrderShipped -> status = OrderStatus.SHIPPED
        }
    }
    
    companion object {
        fun fromEvents(events: List<OrderEvent>): Order {
            val order = Order(events.first().orderId)
            events.forEach { order.apply(it) }
            return order
        }
    }
}
```

**Преимущества:**
- Полная история изменений
- Audit trail
- Можно пересоздать состояние на любой момент
- Отлично работает с CQRS

**Недостатки:**
- Сложность
- Нужны snapshots для производительности

---

### 3. Materialized View

**Проблема:** Сложные запросы с JOIN между сервисами медленные

**Решение:** Предрассчитанные представления данных

```
Source Data              Materialized View
┌──────────┐            ┌──────────────┐
│ Orders   │            │ OrderSummary │
│ Users    │ ────────►  │ (pre-calculated)
│ Products │            │              │
└──────────┘            └──────────────┘
```

**Пример:**
```kotlin
// Materialized View (обновляется при изменении данных)
@Entity
@Table(name = "order_summary")
class OrderSummary {
    @Id
    var orderId: String
    var userName: String  // Денормализовано из User
    var productNames: String  // Денормализовано из Products
    var totalAmount: BigDecimal
    var itemCount: Int
    var createdAt: Instant
}

// Обновление при событиях
@EventListener
class OrderSummaryUpdater {
    fun onOrderCreated(event: OrderCreatedEvent) {
        val user = userService.getUser(event.userId)
        val products = productService.getProducts(event.productIds)
        
        orderSummaryRepository.save(
            OrderSummary(
                orderId = event.orderId,
                userName = user.name,
                productNames = products.joinToString { it.name },
                totalAmount = event.total,
                itemCount = event.items.size,
                createdAt = event.timestamp
            )
        )
    }
}
```

---

## Паттерны отказоустойчивости

### 1. Retry Pattern

**Проблема:** Временные сбои сети или сервиса

**Решение:** Повторять запрос с экспоненциальной задержкой

```kotlin
@Service
class PaymentClient {
    @Retryable(
        value = [Exception::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2)
    )
    fun charge(amount: BigDecimal): PaymentResult {
        return paymentService.charge(amount)
    }
}

// Exponential backoff:
// Attempt 1: immediate
// Attempt 2: wait 1s
// Attempt 3: wait 2s
// Attempt 4: wait 4s
```

---

### 2. Bulkhead Pattern

**Проблема:** Если один ресурс исчерпан, все запросы блокируются

**Решение:** Изолировать ресурсы (как переборки на корабле)

```
Thread Pool 1 ──► Service A
Thread Pool 2 ──► Service B
Thread Pool 3 ──► Service C

Если Service A упал, Service B и C продолжают работать
```

**Пример:**
```kotlin
@Configuration
class ThreadPoolConfig {
    @Bean("paymentExecutor")
    fun paymentExecutor(): ExecutorService {
        return Executors.newFixedThreadPool(10)
    }
    
    @Bean("notificationExecutor")
    fun notificationExecutor(): ExecutorService {
        return Executors.newFixedThreadPool(5)
    }
}

@Service
class OrderService {
    @Autowired
    @Qualifier("paymentExecutor")
    lateinit var paymentExecutor: ExecutorService
    
    fun processOrder(order: Order) {
        paymentExecutor.submit {
            paymentService.charge(order.total)
        }
    }
}
```

---

### 3. Timeout Pattern

**Проблема:** Запрос может висеть бесконечно

**Решение:** Устанавливать таймауты

```kotlin
@Service
class ExternalServiceClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    
    fun callExternalService(): Response {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://external-api.com/data"))
            .timeout(Duration.ofSeconds(10))
            .build()
        
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
```

---

## Паттерны безопасности

### 1. API Gateway Authentication

```
Client ──► API Gateway ──► Validate Token ──► Microservice
         │                  (JWT/OAuth2)
         │
         └──► Return 401 if invalid
```

### 2. Service-to-Service Authentication

**mTLS (Mutual TLS):**
- Каждый сервис имеет сертификат
- Взаимная проверка сертификатов

**API Keys:**
- Секретные ключи для сервисов
- Хранить в secrets manager (Vault)

---

## Резюме

| Паттерн | Проблема | Решение |
|---------|----------|---------|
| API Gateway | Множество endpoints | Единая точка входа |
| Service Discovery | Динамические адреса | Централизованный реестр |
| Circuit Breaker | Каскадные отказы | Автоматическое отключение |
| Saga | Distributed transactions | Последовательность с компенсацией |
| CQRS | Оптимизация чтения/записи | Разделение моделей |
| Event-Driven | Loose coupling | Коммуникация через события |
| Event Sourcing | История изменений | Хранение событий |
| Retry | Временные сбои | Повтор с backoff |
| Bulkhead | Изоляция ресурсов | Отдельные пулы ресурсов |

---

**Следующий шаг:** [Паттерны масштабирования](./SYSTEM_DESIGN_SCALING.md)

