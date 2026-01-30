# Spring / Spring Boot для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Содержание
- Dependency Injection (Кейсы 1-5)
- Bean Lifecycle (Кейсы 6-8)
- AOP (Кейсы 9-12)
- Testing (Кейсы 13-15)

## Dependency Injection

### КЕЙС #1 | Уровень: Middle
**ВОПРОС:** В чём разница между @Autowired по конструктору, сеттеру и полю? Что рекомендуется и почему?

**ОТВЕТ:**
```kotlin
// ПЛОХО: field injection
@Service
class UserServiceBad {
    @Autowired
    private lateinit var userRepository: UserRepository
    // Проблемы: нельзя сделать final, сложно тестировать, скрытые зависимости
}

// СРЕДНЕ: setter injection  
@Service
class UserServiceOk {
    private lateinit var userRepository: UserRepository
    
    @Autowired
    fun setUserRepository(repo: UserRepository) {
        this.userRepository = repo
    }
}

// ХОРОШО: constructor injection
@Service
class UserServiceGood(
    private val userRepository: UserRepository  // final, легко тестировать
) {
    // Все зависимости явные и обязательные
}

// ТЕСТ: легко мокировать через конструктор
@Test
fun testUserService() {
    val mockRepo = mockk<UserRepository>()
    val service = UserServiceGood(mockRepo)  // Просто!
    // vs field injection: нужна рефлексия
}
```

### КЕЙС #2 | Уровень: Senior
**ВОПРОС:** У вас два бина одного типа. Как Spring выберет нужный? Что такое @Primary и @Qualifier?

**ОТВЕТ:**
```kotlin
@Configuration
class DataSourceConfig {
    
    @Bean
    @Primary  // По умолчанию будет использоваться этот
    fun primaryDataSource(): DataSource {
        return HikariDataSource(/* primary DB */)
    }
    
    @Bean
    @Qualifier("readonly")  // Явное имя
    fun readonlyDataSource(): DataSource {
        return HikariDataSource(/* readonly replica */)
    }
}

@Service
class UserService(
    private val dataSource: DataSource,  // Получит @Primary
    @Qualifier("readonly") private val readonlyDs: DataSource  // Явно указан
) {
    fun createUser() = dataSource.connection.use { /* write */ }
    fun findUsers() = readonlyDs.connection.use { /* read */ }
}
```

## AOP

### КЕЙС #9 | Уровень: Middle
**ВОПРОС:** Как реализовать логирование времени выполнения всех методов сервиса через AOP?

**ОТВЕТ:**
```kotlin
@Aspect
@Component
class PerformanceLoggingAspect {
    
    @Around("@within(org.springframework.stereotype.Service)")
    fun logExecutionTime(joinPoint: ProceedingJoinPoint): Any? {
        val start = System.currentTimeMillis()
        val signature = joinPoint.signature
        
        return try {
            joinPoint.proceed()
        } finally {
            val time = System.currentTimeMillis() - start
            logger.info("${signature.name} executed in ${time}ms")
        }
    }
}

// Пользовательская аннотация
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Timed

@Aspect
@Component
class CustomTimedAspect {
    
    @Around("@annotation(Timed)")
    fun measureTime(joinPoint: ProceedingJoinPoint): Any? {
        val start = System.nanoTime()
        val result = joinPoint.proceed()
        val time = (System.nanoTime() - start) / 1_000_000
        
        metricsRegistry.timer("method.execution", "method", joinPoint.signature.name)
            .record(time, TimeUnit.MILLISECONDS)
        
        return result
    }
}

@Service
class OrderService {
    @Timed  // Автоматически замеряется время
    fun processOrder(orderId: Long) { /* ... */ }
}
```

## Bean Lifecycle

### КЕЙС #6 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Какой lifecycle у Spring bean? Как выполнить инициализацию после создания бина?

**ОТВЕТ:**
**Bean Lifecycle:**
1. Конструктор
2. Setter injection / Field injection
3. `@PostConstruct`
4. `afterPropertiesSet()` (если implements InitializingBean)
5. `init-method`
6. Бин готов
7. `@PreDestroy` при shutdown

**ПРИМЕР КОДА:**
```kotlin
@Service
class UserService(
    private val userRepository: UserRepository  // 1. Constructor
) : InitializingBean {
    
    @Autowired
    private lateinit var emailService: EmailService  // 2. Field injection
    
    private lateinit var cache: Map<Long, User>
    
    @PostConstruct  // 3. Выполнится после всех инъекций
    fun init() {
        logger.info("UserService initializing...")
        cache = userRepository.findAll().associateBy { it.id }
        logger.info("Loaded ${cache.size} users into cache")
    }
    
    override fun afterPropertiesSet() {  // 4. После @PostConstruct
        require(cache.isNotEmpty()) { "Cache must not be empty" }
    }
    
    @PreDestroy  // При остановке приложения
    fun cleanup() {
        logger.info("UserService shutting down...")
        cache = emptyMap()
    }
}

// Асинхронная инициализация
@Service
class AsyncInitService {
    
    @Async
    @PostConstruct
    fun asyncInit() {
        // ВНИМАНИЕ: @PostConstruct + @Async может не работать как ожидается!
        // Spring ждёт завершения @PostConstruct перед готовностью бина
    }
}

// ПРАВИЛЬНАЯ асинхронная инициализация
@Service
class ProperAsyncInitService : ApplicationListener<ApplicationReadyEvent> {
    
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            // Выполнится ПОСЛЕ полного старта приложения
            initializeCache()
        }
    }
    
    private suspend fun initializeCache() {
        logger.info("Starting async initialization")
        delay(5000)
        logger.info("Initialization complete")
    }
}
```

### КЕЙС #7 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое Bean scopes? В чём разница между Singleton, Prototype, Request?

**ОТВЕТ:**
**Scopes:**
- `Singleton` (default): один инстанс на ApplicationContext
- `Prototype`: новый инстанс при каждом запросе
- `Request`: один инстанс на HTTP request (только в Web)
- `Session`: один инстанс на HTTP session

**ПРИМЕР КОДА:**
```kotlin
// Singleton (по умолчанию)
@Service
class OrderService {
    // Создаётся ОДИН раз при старте приложения
    // Все запросы используют ОДИН инстанс
}

// Prototype: новый инстанс каждый раз
@Component
@Scope("prototype")
class OrderProcessor {
    private val processedAt = LocalDateTime.now()
    
    fun process(order: Order) {
        logger.info("Processing order at $processedAt")
    }
}

@Service
class OrderService(
    private val applicationContext: ApplicationContext
) {
    fun processOrder(order: Order) {
        // Каждый раз НОВЫЙ инстанс
        val processor = applicationContext.getBean(OrderProcessor::class.java)
        processor.process(order)
    }
}

// Request scope: один инстанс на HTTP запрос
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
class RequestContext {
    val requestId: String = UUID.randomUUID().toString()
    var userId: Long? = null
    
    fun setUser(user: User) {
        userId = user.id
    }
}

@RestController
class OrderController(
    private val requestContext: RequestContext  // Proxy!
) {
    
    @GetMapping("/api/orders")
    fun getOrders(): List<Order> {
        // Каждый HTTP запрос получит СВОЙ RequestContext
        logger.info("Request ID: ${requestContext.requestId}")
        logger.info("User ID: ${requestContext.userId}")
        return orderService.findByUserId(requestContext.userId!!)
    }
}

// proxyMode = ScopedProxyMode.TARGET_CLASS — зачем?
// OrderController создаётся ОДИН раз (Singleton)
// RequestContext создаётся для каждого запроса (Request scope)
// → Spring создаёт PROXY, который делегирует к актуальному инстансу

// Session scope
@Component
@Scope(value = "session", proxyMode = ScopedProxyMode.TARGET_CLASS)
class ShoppingCart {
    private val items = mutableListOf<CartItem>()
    
    fun addItem(item: CartItem) {
        items.add(item)
    }
    
    fun getItems(): List<CartItem> = items.toList()
}

// Custom scope
class ThreadScope : Scope {
    private val threadLocalScope = ThreadLocal<MutableMap<String, Any>>()
    
    override fun get(name: String, objectFactory: ObjectFactory<*>): Any {
        val scope = threadLocalScope.get() ?: mutableMapOf<String, Any>().also {
            threadLocalScope.set(it)
        }
        
        return scope.getOrPut(name) { objectFactory.getObject() }
    }
    
    override fun remove(name: String): Any? {
        return threadLocalScope.get()?.remove(name)
    }
    
    override fun registerDestructionCallback(name: String, callback: Runnable) {}
    override fun resolveContextualObject(key: String): Any? = null
    override fun getConversationId(): String = Thread.currentThread().name
}

@Configuration
class ScopeConfig {
    @Bean
    fun threadScope(): ThreadScope = ThreadScope()
    
    @Bean
    @Scope("thread")
    fun threadScopedBean(): ThreadScopedBean = ThreadScopedBean()
}
```

## Auto-configuration и Configuration Properties

### КЕЙС #16 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает Spring Boot Auto-configuration? Как создать свою auto-configuration?

**ОТВЕТ:**
**Auto-configuration**: автоматическая настройка на основе classpath и properties.

**Механизм:**
1. `@EnableAutoConfiguration` сканирует `META-INF/spring.factories`
2. Загружает классы с `@Configuration`
3. `@Conditional` проверяет условия

**ПРИМЕР КОДА:**
```kotlin
// Своя auto-configuration
@Configuration
@ConditionalOnClass(RedisTemplate::class)  // Только если Redis в classpath
@ConditionalOnProperty(
    prefix = "app.cache",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
@EnableConfigurationProperties(CacheProperties::class)
class CacheAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean  // Только если пользователь не создал свой
    fun cacheManager(properties: CacheProperties): CacheManager {
        return RedisCacheManager.builder(redisConnectionFactory())
            .cacheDefaults(
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(properties.ttlMinutes))
            )
            .build()
    }
    
    @Bean
    @ConditionalOnBean(DataSource::class)  // Только если есть DataSource
    fun databaseCacheLoader(dataSource: DataSource): CacheLoader {
        return DatabaseCacheLoader(dataSource)
    }
}

// Configuration Properties
@ConfigurationProperties(prefix = "app.cache")
data class CacheProperties(
    val enabled: Boolean = false,
    val ttlMinutes: Long = 60,
    val maxSize: Int = 1000,
    val redis: RedisConfig = RedisConfig()
) {
    data class RedisConfig(
        val host: String = "localhost",
        val port: Int = 6379,
        val password: String? = null
    )
}

// application.yml
"""
app:
  cache:
    enabled: true
    ttl-minutes: 120
    max-size: 5000
    redis:
      host: redis.production.com
      port: 6380
      password: ${REDIS_PASSWORD}
"""

// META-INF/spring.factories
"""
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.example.autoconfigure.CacheAutoConfiguration
"""

// Условная конфигурация
@Configuration
class ConditionalConfig {
    
    @Bean
    @Profile("dev")  // Только для dev профиля
    fun devDataSource(): DataSource {
        return HikariDataSource().apply {
            jdbcUrl = "jdbc:h2:mem:testdb"
        }
    }
    
    @Bean
    @Profile("prod")  // Только для prod
    fun prodDataSource(): DataSource {
        return HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://prod-db:5432/mydb"
        }
    }
    
    @Bean
    @ConditionalOnExpression("\${app.features.new-checkout:false}")
    fun newCheckoutService(): CheckoutService {
        return NewCheckoutService()
    }
}

// Custom Conditional
class OnLinuxCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        return System.getProperty("os.name").contains("Linux", ignoreCase = true)
    }
}

@Configuration
@Conditional(OnLinuxCondition::class)
class LinuxSpecificConfig {
    @Bean
    fun linuxOptimizedService(): OptimizedService {
        return LinuxOptimizedService()
    }
}
```

### КЕЙС #17 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как валидировать @ConfigurationProperties? Что если конфигурация невалидна?

**ОТВЕТ:**
**Validation**: используйте `@Validated` + JSR-303 аннотации.

Приложение **не стартует**, если конфигурация невалидна → fail-fast.

**ПРИМЕР КОДА:**
```kotlin
@ConfigurationProperties(prefix = "app.payment")
@Validated  // Включает валидацию
data class PaymentConfig(
    
    @field:NotBlank(message = "API key is required")
    val apiKey: String,
    
    @field:Min(value = 1, message = "Timeout must be at least 1 second")
    @field:Max(value = 300, message = "Timeout cannot exceed 5 minutes")
    val timeoutSeconds: Int = 30,
    
    @field:Valid  // Валидация вложенного объекта
    val retry: RetryConfig = RetryConfig()
) {
    
    data class RetryConfig(
        @field:Min(1)
        @field:Max(10)
        val maxAttempts: Int = 3,
        
        @field:Min(100)
        val backoffMs: Long = 1000
    )
    
    // Custom validation
    @AssertTrue(message = "Backoff must be less than timeout")
    fun isBackoffValid(): Boolean {
        return retry.backoffMs < timeoutSeconds * 1000
    }
}

// application.yml (НЕВАЛИДНЫЙ)
"""
app:
  payment:
    api-key: ""  # ОШИБКА: NotBlank
    timeout-seconds: 500  # ОШИБКА: Max=300
    retry:
      max-attempts: 20  # ОШИБКА: Max=10
"""

// Приложение НЕ СТАРТУЕТ с ошибкой:
"""
***************************
APPLICATION FAILED TO START
***************************

Description:
Binding to target org.springframework.boot.context.properties.bind.BindException: 
Failed to bind properties under 'app.payment' to PaymentConfig failed:

    Property: app.payment.api-key
    Value: ""
    Reason: API key is required

    Property: app.payment.timeout-seconds
    Value: 500
    Reason: Timeout cannot exceed 5 minutes
"""

// ХОРОШО: валидная конфигурация
"""
app:
  payment:
    api-key: ${PAYMENT_API_KEY}
    timeout-seconds: 60
    retry:
      max-attempts: 5
      backoff-ms: 2000
"""

// Custom validator
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [UrlValidator::class])
annotation class ValidUrl(
    val message: String = "Invalid URL",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class UrlValidator : ConstraintValidator<ValidUrl, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        return try {
            URL(value)
            true
        } catch (e: MalformedURLException) {
            false
        }
    }
}

@ConfigurationProperties(prefix = "app.webhook")
@Validated
data class WebhookConfig(
    @field:ValidUrl
    val callbackUrl: String
)
```

### КЕЙС #18 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работают JDK Proxy и CGLIB Proxy в Spring? Когда какой используется?

**ОТВЕТ:**
**JDK Proxy**: через интерфейс (implements)
**CGLIB Proxy**: через наследование (extends) — для классов без интерфейса

**Ограничения:**
- JDK Proxy: класс ДОЛЖЕН иметь интерфейс
- CGLIB: метод НЕ должен быть `final`

**ПРИМЕР КОДА:**
```kotlin
// JDK Proxy: через интерфейс
interface UserService {
    fun createUser(user: User): User
}

@Service
class UserServiceImpl(
    private val userRepository: UserRepository
) : UserService {
    
    @Transactional
    override fun createUser(user: User): User {
        return userRepository.save(user)
    }
}

// Spring создаёт JDK Proxy:
// class UserServiceProxy implements UserService {
//     private UserServiceImpl target;
//     
//     public User createUser(User user) {
//         // Before advice (start transaction)
//         User result = target.createUser(user);
//         // After advice (commit transaction)
//         return result;
//     }
// }

// CGLIB Proxy: класс без интерфейса
@Service
class OrderService(
    private val orderRepository: OrderRepository
) {
    // НЕ final! Иначе CGLIB не сможет переопределить
    @Transactional
    open fun createOrder(order: Order): Order {
        return orderRepository.save(order)
    }
}

// Spring создаёт CGLIB Proxy через наследование:
// class OrderService$$EnhancerBySpringCGLIB extends OrderService {
//     public Order createOrder(Order order) {
//         // Before advice
//         Order result = super.createOrder(order);
//         // After advice
//         return result;
//     }
// }

// ПРОБЛЕМА: self-invocation не работает
@Service
class PaymentService {
    
    fun processPayment(amount: BigDecimal) {
        // Вызов через this → НЕ через proxy!
        this.chargeCard(amount)  // @Transactional НЕ СРАБОТАЕТ!
    }
    
    @Transactional
    open fun chargeCard(amount: BigDecimal) {
        // Транзакция НЕ начнётся!
    }
}

// РЕШЕНИЕ 1: self-injection
@Service
class PaymentService(
    @Lazy private val self: PaymentService  // Inject proxy
) {
    
    fun processPayment(amount: BigDecimal) {
        self.chargeCard(amount)  // Через proxy → @Transactional работает!
    }
    
    @Transactional
    open fun chargeCard(amount: BigDecimal) {
        // Транзакция начнётся!
    }
}

// РЕШЕНИЕ 2: разделение на два класса
@Service
class PaymentService(
    private val paymentTransactionalService: PaymentTransactionalService
) {
    fun processPayment(amount: BigDecimal) {
        paymentTransactionalService.chargeCard(amount)
    }
}

@Service
class PaymentTransactionalService {
    
    @Transactional
    fun chargeCard(amount: BigDecimal) {
        // Транзакция работает!
    }
}

// Проверка типа proxy
@Service
class DebugService(private val userService: UserService) {
    
    @PostConstruct
    fun checkProxyType() {
        if (AopUtils.isJdkDynamicProxy(userService)) {
            logger.info("UserService uses JDK Proxy")
        } else if (AopUtils.isCglibProxy(userService)) {
            logger.info("UserService uses CGLIB Proxy")
        }
    }
}
```

## Spring Security

### КЕЙС #19 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как настроить JWT аутентификацию в Spring Security? Где хранить токен?

**ОТВЕТ:**
**JWT Authentication Flow:**
1. Login → генерация JWT токена
2. Клиент сохраняет токен (HttpOnly cookie или localStorage)
3. Каждый запрос → валидация токена

**ПРИМЕР КОДА:**
```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig {
    
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }  // Отключаем CSRF для API
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
            }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                // НЕ используем HTTP сессии
            }
            .addFilterBefore(
                jwtAuthenticationFilter(),
                UsernamePasswordAuthenticationFilter::class.java
            )
        
        return http.build()
    }
}

// JWT Filter
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: UserDetailsService
) : OncePerRequestFilter() {
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = extractToken(request)
        
        if (token != null && jwtService.validateToken(token)) {
            val username = jwtService.extractUsername(token)
            val userDetails = userDetailsService.loadUserByUsername(username)
            
            val authentication = UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.authorities
            )
            
            SecurityContextHolder.getContext().authentication = authentication
        }
        
        filterChain.doFilter(request, response)
    }
    
    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization")
        return if (header?.startsWith("Bearer ") == true) {
            header.substring(7)
        } else {
            null
        }
    }
}

// JWT Service
@Service
class JwtService {
    
    @Value("\${jwt.secret}")
    private lateinit var secret: String
    
    @Value("\${jwt.expiration-ms}")
    private var expirationMs: Long = 86400000  // 24 hours
    
    fun generateToken(username: String, roles: List<String>): String {
        val now = Date()
        val expiration = Date(now.time + expirationMs)
        
        return Jwts.builder()
            .setSubject(username)
            .claim("roles", roles)
            .setIssuedAt(now)
            .setExpiration(expiration)
            .signWith(getSigningKey(), SignatureAlgorithm.HS256)
            .compact()
    }
    
    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun extractUsername(token: String): String {
        val claims = Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(token)
            .body
        
        return claims.subject
    }
    
    private fun getSigningKey(): Key {
        val keyBytes = Decoders.BASE64.decode(secret)
        return Keys.hmacShaKeyFor(keyBytes)
    }
}

// Login endpoint
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService
) {
    
    @PostMapping("/login")
    fun login(@RequestBody @Valid request: LoginRequest): LoginResponse {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(
                request.username,
                request.password
            )
        )
        
        val user = authentication.principal as UserDetails
        val roles = user.authorities.map { it.authority }
        
        val token = jwtService.generateToken(user.username, roles)
        
        return LoginResponse(
            token = token,
            expiresIn = 86400
        )
    }
}

// Использование в контроллере
@RestController
@RequestMapping("/api/orders")
class OrderController(private val orderService: OrderService) {
    
    @GetMapping("/my")
    fun getMyOrders(@AuthenticationPrincipal user: UserDetails): List<Order> {
        // user автоматически инжектится из SecurityContext
        return orderService.findByUsername(user.username)
    }
    
    @PreAuthorize("hasRole('ADMIN')")  // Method-level security
    @DeleteMapping("/{id}")
    fun deleteOrder(@PathVariable id: Long) {
        orderService.deleteOrder(id)
    }
}
```

## Actuator и мониторинг

### КЕЙС #20 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как настроить health checks и custom metrics через Spring Boot Actuator?

**ОТВЕТ:**
**Actuator**: production-ready endpoints для мониторинга.

**Основные endpoints:**
- `/actuator/health` — статус приложения
- `/actuator/metrics` — метрики
- `/actuator/info` — информация о приложении

**ПРИМЕР КОДА:**
```kotlin
// Dependencies
"""
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
"""

// application.yml
"""
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${spring.profiles.active}
"""

// Custom Health Indicator
@Component
class DatabaseHealthIndicator(
    private val dataSource: DataSource
) : HealthIndicator {
    
    override fun health(): Health {
        return try {
            dataSource.connection.use { conn ->
                val stmt = conn.createStatement()
                stmt.execute("SELECT 1")
                
                Health.up()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("status", "Connected")
                    .build()
            }
        } catch (e: Exception) {
            Health.down()
                .withDetail("error", e.message)
                .withException(e)
                .build()
        }
    }
}

// Composite health indicator
@Component
class OrderProcessingHealthIndicator(
    private val orderQueue: OrderQueue,
    private val paymentGateway: PaymentGateway
) : HealthIndicator {
    
    override fun health(): Health {
        val queueSize = orderQueue.size()
        val paymentAvailable = paymentGateway.isAvailable()
        
        return when {
            queueSize > 1000 -> Health.down()
                .withDetail("queue-size", queueSize)
                .withDetail("reason", "Queue overloaded")
                .build()
            
            !paymentAvailable -> Health.down()
                .withDetail("payment-gateway", "unavailable")
                .build()
            
            queueSize > 500 -> Health.up()
                .withDetail("queue-size", queueSize)
                .withDetail("status", "warning")
                .build()
            
            else -> Health.up()
                .withDetail("queue-size", queueSize)
                .withDetail("payment-gateway", "available")
                .build()
        }
    }
}

// Custom metrics
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val meterRegistry: MeterRegistry
) {
    
    private val orderCounter = meterRegistry.counter(
        "orders.created",
        "status", "success"
    )
    
    private val orderTimer = meterRegistry.timer("orders.processing.time")
    
    fun createOrder(orderDto: OrderDto): Order {
        return orderTimer.recordCallable {
            val order = orderRepository.save(orderDto.toEntity())
            orderCounter.increment()
            
            // Gauge для текущего количества активных заказов
            meterRegistry.gauge(
                "orders.active",
                orderRepository.countByStatus(OrderStatus.PENDING)
            )
            
            order
        }!!
    }
    
    @Timed(value = "orders.validation.time", percentiles = [0.5, 0.95, 0.99])
    fun validateOrder(order: Order): Boolean {
        // Автоматическое измерение через @Timed
        return order.items.isNotEmpty() && order.total > BigDecimal.ZERO
    }
}

// Prometheus endpoint
// GET /actuator/prometheus
"""
# HELP orders_created_total  
# TYPE orders_created_total counter
orders_created_total{status="success"} 1234.0

# HELP orders_processing_time_seconds  
# TYPE orders_processing_time_seconds summary
orders_processing_time_seconds_count 1234.0
orders_processing_time_seconds_sum 45.6
orders_processing_time_seconds_max 2.3

# HELP orders_active  
# TYPE orders_active gauge
orders_active 56.0
"""

// Custom endpoint
@Component
@Endpoint(id = "orders")
class OrdersEndpoint(private val orderService: OrderService) {
    
    @ReadOperation
    fun getOrderStats(): Map<String, Any> {
        return mapOf(
            "total" to orderService.count(),
            "pending" to orderService.countByStatus(OrderStatus.PENDING),
            "completed" to orderService.countByStatus(OrderStatus.COMPLETED)
        )
    }
    
    @WriteOperation
    fun resetStats() {
        orderService.resetStatistics()
    }
}

// GET /actuator/orders
// {
//   "total": 1000,
//   "pending": 50,
//   "completed": 950
// }
```

## Testing

### КЕЙС #13 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Как правильно тестировать REST контроллер? MockMvc vs @SpringBootTest vs WebTestClient?

**ОТВЕТ:**
```kotlin
// Unit тест с MockMvc (быстрый, без Spring Context)
@WebMvcTest(UserController::class)
class UserControllerTest {
    
    @Autowired
    private lateinit var mockMvc: MockMvc
    
    @MockBean
    private lateinit var userService: UserService
    
    @Test
    fun `should return user by id`() {
        val user = User(id = 1L, name = "John", email = "john@example.com")
        every { userService.findById(1L) } returns user
        
        mockMvc.perform(get("/api/users/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("John"))
            .andExpect(jsonPath("$.email").value("john@example.com"))
    }
    
    @Test
    fun `should return 404 when user not found`() {
        every { userService.findById(999L) } returns null
        
        mockMvc.perform(get("/api/users/999"))
            .andExpect(status().isNotFound)
    }
}

// Integration тест (полный Spring Context + БД)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase
class UserIntegrationTest {
    
    @Autowired
    private lateinit var restTemplate: TestRestTemplate
    
    @Autowired
    private lateinit var userRepository: UserRepository
    
    @BeforeEach
    fun setup() {
        userRepository.deleteAll()
    }
    
    @Test
    fun `should create and find user`() {
        val userDto = UserDto(name = "John", email = "john@example.com")
        
        // POST /api/users
        val response = restTemplate.postForEntity(
            "/api/users",
            userDto,
            User::class.java
        )
        
        assertEquals(HttpStatus.CREATED, response.statusCode)
        val userId = response.body?.id!!
        
        // GET /api/users/{id}
        val getResponse = restTemplate.getForEntity(
            "/api/users/$userId",
            User::class.java
        )
        
        assertEquals(HttpStatus.OK, getResponse.statusCode)
        assertEquals("John", getResponse.body?.name)
    }
}
```

### КЕЙС #21 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как тестировать @Transactional сервисы? Откатываются ли транзакции в тестах?

**ОТВЕТ:**
**По умолчанию**: `@Transactional` в тестах **откатывается** после каждого теста.

**Проблема**: если нужно проверить commit → используйте `@Commit`.

**ПРИМЕР КОДА:**
```kotlin
@SpringBootTest
@Transactional  // Откатывается после каждого теста!
class OrderServiceTest {
    
    @Autowired
    private lateinit var orderService: OrderService
    
    @Autowired
    private lateinit var orderRepository: OrderRepository
    
    @Test
    fun `should create order`() {
        val order = orderService.createOrder(orderDto)
        
        assertNotNull(order.id)
        
        // Данные есть в БД в рамках транзакции теста
        val found = orderRepository.findById(order.id!!).orElse(null)
        assertNotNull(found)
    }
    
    @AfterEach
    fun verify() {
        // После теста транзакция откатится
        // orderRepository будет пуст!
    }
}

// @Commit: сохраняет изменения
@SpringBootTest
class OrderServiceCommitTest {
    
    @Test
    @Transactional
    @Commit  // НЕ откатывать транзакцию!
    fun `should persist order after test`() {
        val order = orderService.createOrder(orderDto)
        // Останется в БД после теста
    }
    
    @AfterEach
    fun cleanup() {
        orderRepository.deleteAll()  // Ручная очистка
    }
}

// Тестирование rollback
@SpringBootTest
class TransactionRollbackTest {
    
    @Test
    fun `should rollback on exception`() {
        val initialCount = orderRepository.count()
        
        assertThrows<PaymentException> {
            orderService.createOrderWithPayment(orderDto)
            // Внутри: создаём order, потом payment падает
        }
        
        // Order НЕ должен быть сохранён (rollback)
        assertEquals(initialCount, orderRepository.count())
    }
}

// Тестирование propagation
@SpringBootTest
class PropagationTest {
    
    @Autowired
    private lateinit var outerService: OuterService
    
    @Autowired
    private lateinit var innerService: InnerService
    
    @Test
    fun `REQUIRES_NEW creates separate transaction`() {
        assertThrows<Exception> {
            outerService.outerMethod()
            // Внутри: innerService.innerMethodRequiresNew() → commit
            // Потом outerMethod бросает exception → rollback
        }
        
        // Inner method должен быть закоммичен (REQUIRES_NEW)
        assertEquals(1, innerRepository.count())
        // Outer method откатился
        assertEquals(0, outerRepository.count())
    }
}

// Testcontainers для реальной БД
@SpringBootTest
@Testcontainers
class OrderServiceTestcontainersTest {
    
    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
        }
        
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
    
    @Test
    fun `should work with real PostgreSQL`() {
        // Тест с реальной БД в Docker контейнере
        val order = orderService.createOrder(orderDto)
        assertNotNull(order.id)
    }
}
```

### КЕЙС #22 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как тестировать Kafka consumers/producers в Spring? MockBean или Testcontainers?

**ОТВЕТ:**
**MockBean**: быстро, но не проверяет serialization/deserialization
**Testcontainers**: медленнее, но реальный Kafka

**ПРИМЕР КОДА:**
```kotlin
// Unit тест с MockBean
@SpringBootTest
class OrderEventProducerTest {
    
    @Autowired
    private lateinit var orderEventProducer: OrderEventProducer
    
    @MockBean
    private lateinit var kafkaTemplate: KafkaTemplate<String, OrderEvent>
    
    @Test
    fun `should send order created event`() {
        val order = Order(id = 1L, userId = 123L, total = BigDecimal("100"))
        
        orderEventProducer.sendOrderCreated(order)
        
        verify {
            kafkaTemplate.send(
                "order-events",
                order.id.toString(),
                match<OrderEvent> { it is OrderEvent.OrderCreated }
            )
        }
    }
}

// Integration тест с Testcontainers
@SpringBootTest
@Testcontainers
class OrderEventIntegrationTest {
    
    companion object {
        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
        
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }
    
    @Autowired
    private lateinit var orderEventProducer: OrderEventProducer
    
    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, OrderEvent>
    
    @Test
    fun `should produce and consume order event`() {
        val events = mutableListOf<OrderEvent>()
        
        // Consumer
        val containerProperties = ContainerProperties("order-events")
        containerProperties.messageListener = MessageListener<String, OrderEvent> { record ->
            events.add(record.value())
        }
        
        val container = KafkaMessageListenerContainer(
            DefaultKafkaConsumerFactory<String, OrderEvent>(
                mapOf(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG to "test-group",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
                )
            ),
            containerProperties
        )
        
        container.start()
        
        // Produce event
        val order = Order(id = 1L, userId = 123L, total = BigDecimal("100"))
        orderEventProducer.sendOrderCreated(order)
        
        // Wait for consumption
        await().atMost(Duration.ofSeconds(10)).until {
            events.isNotEmpty()
        }
        
        assertEquals(1, events.size)
        assertTrue(events.first() is OrderEvent.OrderCreated)
        
        container.stop()
    }
}

// EmbeddedKafka (альтернатива)
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = ["order-events"],
    brokerProperties = [
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    ]
)
class OrderEventEmbeddedTest {
    
    @Autowired
    private lateinit var orderEventProducer: OrderEventProducer
    
    @Autowired
    @Qualifier("kafkaListenerEndpointRegistry")
    private lateinit var registry: KafkaListenerEndpointRegistry
    
    @Test
    fun `should process order event`() {
        // Тест с embedded Kafka
    }
}
```

### КЕЙС #23 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как использовать @TestConfiguration для переопределения бинов в тестах?

**ОТВЕТ:**
**@TestConfiguration**: создание/переопределение бинов только для тестов.

**ПРИМЕР КОДА:**
```kotlin
// Production конфигурация
@Configuration
class ProductionConfig {
    
    @Bean
    fun paymentGateway(): PaymentGateway {
        return RealPaymentGateway(apiKey = System.getenv("PAYMENT_API_KEY"))
    }
}

// Test конфигурация
@TestConfiguration
class TestConfig {
    
    @Bean
    @Primary  // Переопределяет production bean
    fun paymentGateway(): PaymentGateway {
        return MockPaymentGateway()  // Fake для тестов
    }
    
    @Bean
    fun testDataGenerator(): TestDataGenerator {
        return TestDataGenerator()  // Только для тестов
    }
}

// Использование в тесте
@SpringBootTest
@Import(TestConfig::class)  // Импортируем тестовую конфигурацию
class OrderServiceTest {
    
    @Autowired
    private lateinit var orderService: OrderService
    
    @Autowired
    private lateinit var paymentGateway: PaymentGateway  // Будет MockPaymentGateway!
    
    @Autowired
    private lateinit var testDataGenerator: TestDataGenerator
    
    @Test
    fun `should process order with mock payment`() {
        val order = testDataGenerator.createOrder()
        
        orderService.processOrder(order)
        
        // Используется MockPaymentGateway, а не реальный
        assertTrue(paymentGateway is MockPaymentGateway)
    }
}

// Mock Implementation для тестов
class MockPaymentGateway : PaymentGateway {
    val chargedAmounts = mutableListOf<BigDecimal>()
    var shouldFail = false
    
    override fun charge(amount: BigDecimal): PaymentResult {
        if (shouldFail) {
            throw PaymentException("Mock payment failed")
        }
        
        chargedAmounts.add(amount)
        return PaymentResult.Success("mock-transaction-id")
    }
}

@Test
fun `should handle payment failure`() {
    val mockGateway = paymentGateway as MockPaymentGateway
    mockGateway.shouldFail = true
    
    assertThrows<PaymentException> {
        orderService.processOrder(order)
    }
}
```

### КЕЙС #24 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как писать slice tests (@DataJpaTest, @WebMvcTest)? В чём выгода перед @SpringBootTest?

**ОТВЕТ:**
**Slice tests**: загружают только часть Spring Context → быстрее.

**Типы:**
- `@DataJpaTest` — только JPA
- `@WebMvcTest` — только MVC
- `@JsonTest` — только JSON serialization

**ПРИМЕР КОДА:**
```kotlin
// @DataJpaTest: только JPA слой
@DataJpaTest
class UserRepositoryTest {
    
    @Autowired
    private lateinit var userRepository: UserRepository
    
    @Autowired
    private lateinit var entityManager: TestEntityManager
    
    @Test
    fun `should find user by email`() {
        // TestEntityManager для setup данных
        val user = entityManager.persistAndFlush(
            User(name = "John", email = "john@example.com")
        )
        
        val found = userRepository.findByEmail("john@example.com")
        
        assertNotNull(found)
        assertEquals(user.id, found?.id)
    }
    
    @Test
    fun `should use custom query correctly`() {
        entityManager.persist(User(name = "John", email = "john@example.com", age = 25))
        entityManager.persist(User(name = "Jane", email = "jane@example.com", age = 30))
        entityManager.flush()
        
        val users = userRepository.findByAgeGreaterThan(28)
        
        assertEquals(1, users.size)
        assertEquals("Jane", users.first().name)
    }
}

// @WebMvcTest: только Web слой
@WebMvcTest(OrderController::class)
class OrderControllerSliceTest {
    
    @Autowired
    private lateinit var mockMvc: MockMvc
    
    @MockBean
    private lateinit var orderService: OrderService
    
    @Test
    fun `should validate request body`() {
        val invalidDto = """{"userId": null, "items": []}"""
        
        mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidDto)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors").isArray)
    }
}

// @JsonTest: только JSON serialization/deserialization
@JsonTest
class OrderDtoJsonTest {
    
    @Autowired
    private lateinit var json: JacksonTester<OrderDto>
    
    @Test
    fun `should serialize order dto`() {
        val orderDto = OrderDto(
            userId = 123L,
            items = listOf(
                OrderItemDto(productId = 1L, quantity = 2)
            )
        )
        
        val result = json.write(orderDto)
        
        assertThat(result).hasJsonPathNumberValue("$.userId", 123)
        assertThat(result).hasJsonPathArrayValue("$.items")
    }
    
    @Test
    fun `should deserialize order dto`() {
        val jsonContent = """
            {
                "userId": 123,
                "items": [
                    {"productId": 1, "quantity": 2}
                ]
            }
        """
        
        val dto = json.parse(jsonContent).getObject()
        
        assertEquals(123L, dto.userId)
        assertEquals(1, dto.items.size)
    }
}

// Сравнение производительности
// @SpringBootTest: ~5-10 секунд (полный context)
// @WebMvcTest: ~2-3 секунды (только web layer)
// @DataJpaTest: ~2-3 секунды (только JPA)
```

### КЕЙС #25 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как тестировать асинхронные методы (@Async)? Как дождаться завершения?

**ОТВЕТ:**
**Проблема**: `@Async` возвращает сразу → тест завершается до выполнения.

**Решения:**
1. Вернуть `CompletableFuture` и вызвать `.get()`
2. Использовать `await()` из Awaitility
3. `@SpyBean` для проверки вызовов

**ПРИМЕР КОДА:**
```kotlin
@Service
class EmailService {
    
    @Async
    fun sendEmailAsync(to: String, subject: String): CompletableFuture<Boolean> {
        Thread.sleep(1000)  // Симуляция отправки
        logger.info("Email sent to $to")
        return CompletableFuture.completedFuture(true)
    }
}

// ТЕСТ 1: через CompletableFuture
@SpringBootTest
@EnableAsync
class EmailServiceTest {
    
    @Autowired
    private lateinit var emailService: EmailService
    
    @Test
    fun `should send email asynchronously`() {
        val future = emailService.sendEmailAsync("test@example.com", "Test")
        
        val result = future.get(5, TimeUnit.SECONDS)  // Ждём завершения
        
        assertTrue(result)
    }
}

// ТЕСТ 2: через Awaitility
@Test
fun `should send email with awaitility`() {
    val emailSent = AtomicBoolean(false)
    
    emailService.sendEmailAsync("test@example.com", "Test")
        .thenAccept { emailSent.set(it) }
    
    await()
        .atMost(Duration.ofSeconds(5))
        .until { emailSent.get() }
}

// ТЕСТ 3: @SpyBean для проверки вызовов
@SpringBootTest
class OrderServiceAsyncTest {
    
    @Autowired
    private lateinit var orderService: OrderService
    
    @SpyBean  // Real bean + возможность verify
    private lateinit var emailService: EmailService
    
    @Test
    fun `should send email after order creation`() {
        orderService.createOrder(orderDto)
        
        // Ждём асинхронного вызова
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted {
                verify { emailService.sendEmailAsync(any(), any()) }
            }
    }
}

// Тестирование exception в async
@Test
fun `should handle async exception`() {
    val future = emailService.sendEmailAsync("invalid-email", "Test")
    
    val exception = assertThrows<ExecutionException> {
        future.get(5, TimeUnit.SECONDS)
    }
    
    assertTrue(exception.cause is EmailSendingException)
}
```

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$2.80

*Версия: 1.0 | Январь 2026*

