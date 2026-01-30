# Кэширование и Redis для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Стратегии кэширования

### КЕЙС #1 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** В чём разница между Cache-Aside, Write-Through и Write-Back? Когда использовать каждую?

**ОТВЕТ:**
**3 основные стратегии:**
1. **Cache-Aside (Lazy Loading)**: приложение управляет кэшем
2. **Write-Through**: запись сначала в кэш, затем в БД
3. **Write-Back (Write-Behind)**: запись в кэш, потом асинхронно в БД

**ПОЧЕМУ ЭТО ВАЖНО:**
- Cache-Aside: простой, но cache miss penalty
- Write-Through: всегда свежие данные, но медленная запись
- Write-Back: быстрая запись, но риск потери данных

**ПРИМЕР КОДА:**
```kotlin
// 1. CACHE-ASIDE (Lazy Loading): самая распространённая
@Service
class UserService(
    private val userRepository: UserRepository,
    private val redisTemplate: RedisTemplate<String, User>
) {
    
    fun getUser(id: Long): User {
        val cacheKey = "user:$id"
        
        // 1. Проверяем кэш
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            logger.debug("Cache HIT for user $id")
            return cached
        }
        
        // 2. Cache MISS: загружаем из БД
        logger.debug("Cache MISS for user $id")
        val user = userRepository.findById(id).orElseThrow {
            NotFoundException("User $id not found")
        }
        
        // 3. Сохраняем в кэш
        redisTemplate.opsForValue().set(cacheKey, user, Duration.ofMinutes(30))
        
        return user
    }
    
    @CacheEvict(value = ["user"], key = "#user.id")
    fun updateUser(user: User): User {
        // Обновляем БД
        val saved = userRepository.save(user)
        
        // Spring Cache автоматически удалит из кэша (evict)
        return saved
    }
}

// ✅ Плюсы:
//   - Простота
//   - Кэшируются только запрашиваемые данные
//   - Нет overhead на запись
// ❌ Минусы:
//   - Cache miss penalty (первый запрос медленный)
//   - Риск stale data

// 2. WRITE-THROUGH: запись через кэш
@Service
class ProductServiceWriteThrough(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>
) {
    
    fun updateProduct(product: Product): Product {
        // 1. Сохраняем в БД
        val saved = productRepository.save(product)
        
        // 2. СРАЗУ обновляем кэш
        val cacheKey = "product:${saved.id}"
        redisTemplate.opsForValue().set(cacheKey, saved, Duration.ofHours(1))
        
        logger.info("Product ${saved.id} saved to DB and cache")
        
        return saved
    }
    
    fun getProduct(id: Long): Product {
        val cacheKey = "product:$id"
        
        // Всегда читаем из кэша
        val cached = redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) return cached
        
        // Если нет в кэше → загружаем и кэшируем
        val product = productRepository.findById(id).orElseThrow()
        redisTemplate.opsForValue().set(cacheKey, product, Duration.ofHours(1))
        
        return product
    }
}

// ✅ Плюсы:
//   - Данные всегда актуальны в кэше
//   - Нет stale data проблемы
// ❌ Минусы:
//   - Медленная запись (2 операции)
//   - Кэш может быть заполнен редко используемыми данными

// 3. WRITE-BACK (Write-Behind): отложенная запись
@Service
class OrderServiceWriteBack(
    private val redisTemplate: RedisTemplate<String, Order>,
    private val kafkaTemplate: KafkaTemplate<String, OrderUpdateCommand>
) {
    
    fun updateOrder(order: Order): Order {
        val cacheKey = "order:${order.id}"
        
        // 1. СРАЗУ в кэш
        redisTemplate.opsForValue().set(cacheKey, order, Duration.ofHours(2))
        
        // 2. Отправляем команду на асинхронную запись в БД
        kafkaTemplate.send(
            "order-updates",
            OrderUpdateCommand(order.id, order)
        )
        
        logger.info("Order ${order.id} cached, DB update queued")
        
        return order
    }
    
    // Consumer: асинхронная запись в БД
    @KafkaListener(topics = ["order-updates"])
    fun handleOrderUpdate(command: OrderUpdateCommand) {
        try {
            orderRepository.save(command.order)
            logger.info("Order ${command.orderId} persisted to DB")
        } catch (e: Exception) {
            logger.error("Failed to persist order ${command.orderId}", e)
            // Retry или DLQ
        }
    }
}

// ✅ Плюсы:
//   - Очень быстрая запись
//   - Batching в БД (можно группировать записи)
//   - Снижение нагрузки на БД
// ❌ Минусы:
//   - Риск потери данных (Redis упал до записи в БД)
//   - Сложность (нужна очередь, retry)
//   - Eventual consistency

// Spring Cache абстракция (Cache-Aside)
@Service
class ProductServiceSpringCache {
    
    @Cacheable(value = ["products"], key = "#id")
    fun getProduct(id: Long): Product {
        logger.info("Loading product $id from DB")
        return productRepository.findById(id).orElseThrow()
    }
    
    @CachePut(value = ["products"], key = "#product.id")
    fun updateProduct(product: Product): Product {
        return productRepository.save(product)
    }
    
    @CacheEvict(value = ["products"], key = "#id")
    fun deleteProduct(id: Long) {
        productRepository.deleteById(id)
    }
    
    @CacheEvict(value = ["products"], allEntries = true)
    fun clearCache() {
        // Очистить весь кэш products
    }
}

// Конфигурация Spring Cache с Redis
@Configuration
@EnableCaching
class CacheConfig {
    
    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        val config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer()
                )
            )
            .disableCachingNullValues()
        
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .withCacheConfiguration(
                "products",
                config.entryTtl(Duration.ofHours(1))  // Отдельный TTL для products
            )
            .build()
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #2 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как определить оптимальный TTL для кэша? Что такое cache warming?

**ОТВЕТ:**
**TTL (Time To Live)**: время жизни кэшированных данных.

**Trade-off:**
- Короткий TTL: актуальные данные, но больше cache miss
- Длинный TTL: меньше miss, но риск stale data

**Cache warming**: предварительная загрузка популярных данных в кэш.

**ПРИМЕР КОДА:**
```kotlin
// Выбор TTL на основе частоты изменений
@Service
class TtlStrategyService(
    private val redisTemplate: RedisTemplate<String, Any>
) {
    
    // Статические данные (категории, страны): длинный TTL
    fun getCategoryData(id: Long): Category {
        val cacheKey = "category:$id"
        val ttl = Duration.ofDays(7)  // 7 дней
        
        return getCachedOrLoad(cacheKey, ttl) {
            categoryRepository.findById(id)
        }
    }
    
    // Пользовательский профиль: средний TTL
    fun getUserProfile(id: Long): UserProfile {
        val cacheKey = "user:profile:$id"
        val ttl = Duration.ofHours(1)  // 1 час
        
        return getCachedOrLoad(cacheKey, ttl) {
            userRepository.findProfileById(id)
        }
    }
    
    // Цены товаров: короткий TTL
    fun getProductPrice(id: Long): BigDecimal {
        val cacheKey = "product:price:$id"
        val ttl = Duration.ofMinutes(5)  // 5 минут
        
        return getCachedOrLoad(cacheKey, ttl) {
            productRepository.getCurrentPrice(id)
        }
    }
    
    // Реал-тайм данные (курсы валют): очень короткий TTL
    fun getExchangeRate(from: String, to: String): BigDecimal {
        val cacheKey = "exchange:$from:$to"
        val ttl = Duration.ofSeconds(30)  // 30 секунд
        
        return getCachedOrLoad(cacheKey, ttl) {
            exchangeRateApi.getRate(from, to)
        }
    }
    
    private inline fun <reified T> getCachedOrLoad(
        key: String,
        ttl: Duration,
        loader: () -> T
    ): T {
        val cached = redisTemplate.opsForValue().get(key) as? T
        if (cached != null) return cached
        
        val loaded = loader()
        redisTemplate.opsForValue().set(key, loaded, ttl)
        
        return loaded
    }
}

// Cache warming: предзагрузка популярных данных
@Component
class CacheWarmingScheduler(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>
) {
    
    @Scheduled(cron = "0 0 * * * *")  // Каждый час
    fun warmPopularProducts() {
        logger.info("Starting cache warming")
        
        // Топ-100 популярных товаров
        val popularProducts = productRepository.findTopByViewsOrderByViewsDesc(100)
        
        popularProducts.forEach { product ->
            val cacheKey = "product:${product.id}"
            redisTemplate.opsForValue().set(
                cacheKey,
                product,
                Duration.ofHours(2)
            )
        }
        
        logger.info("Warmed ${popularProducts.size} products")
    }
    
    @EventListener(ApplicationReadyEvent::class)
    fun warmOnStartup() {
        // Прогрев при старте приложения
        logger.info("Application started, warming critical cache")
        
        warmPopularProducts()
        warmCategoriesCache()
        warmConfigCache()
        
        logger.info("Cache warming completed")
    }
    
    private fun warmCategoriesCache() {
        val categories = categoryRepository.findAll()
        categories.forEach { category ->
            redisTemplate.opsForValue().set(
                "category:${category.id}",
                category,
                Duration.ofDays(1)
            )
        }
    }
}

// Adaptive TTL на основе hit rate
@Service
class AdaptiveTtlService(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val metricsRegistry: MeterRegistry
) {
    
    private val hitRate = ConcurrentHashMap<String, HitRateMetrics>()
    
    fun <T> getWithAdaptiveTtl(
        key: String,
        loader: () -> T
    ): T {
        val cached = redisTemplate.opsForValue().get(key) as? T
        
        if (cached != null) {
            recordHit(key)
            return cached
        }
        
        recordMiss(key)
        
        val loaded = loader() as Any
        val ttl = calculateTtl(key)
        
        redisTemplate.opsForValue().set(key, loaded, ttl)
        
        return loaded as T
    }
    
    private fun calculateTtl(key: String): Duration {
        val metrics = hitRate[key] ?: return Duration.ofMinutes(5)
        
        val hitRatePercent = metrics.hits.toDouble() / (metrics.hits + metrics.misses)
        
        return when {
            hitRatePercent > 0.9 -> Duration.ofHours(2)   // Высокий hit rate → длинный TTL
            hitRatePercent > 0.7 -> Duration.ofMinutes(30)
            hitRatePercent > 0.5 -> Duration.ofMinutes(10)
            else -> Duration.ofMinutes(5)                  // Низкий hit rate → короткий TTL
        }
    }
    
    private fun recordHit(key: String) {
        hitRate.compute(key) { _, metrics ->
            (metrics ?: HitRateMetrics()).apply { hits++ }
        }
    }
    
    private fun recordMiss(key: String) {
        hitRate.compute(key) { _, metrics ->
            (metrics ?: HitRateMetrics()).apply { misses++ }
        }
    }
}

data class HitRateMetrics(
    var hits: Long = 0,
    var misses: Long = 0
)
```
───────────────────────────────────────────────────────────────────────────────

## Инвалидация и Cache Stampede

### КЕЙС #5 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Как реализовать инвалидацию кэша при обновлении данных? Cache Stampede — что это и как защититься?

**ОТВЕТ:**
**Cache Stampede (Thundering Herd)**: популярный ключ истекает → тысячи одновременных запросов к БД.

**Проблема:** БД перегружается, приложение тормозит.

**Решения:**
1. Распределённая блокировка (только 1 поток загружает)
2. Probabilistic Early Expiration (обновление до истечения)
3. Stale-While-Revalidate (отдать старые данные, обновить фоном)

**ПРИМЕР КОДА:**
```kotlin
// ПЛОХО: без защиты от Cache Stampede
@Service
class ProductServiceBad(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>
) {
    
    fun getPopularProduct(id: Long): Product {
        val cacheKey = "product:$id"
        val cached = redisTemplate.opsForValue().get(cacheKey)
        
        if (cached != null) return cached
        
        // Если кэш истёк, ВСЕ потоки одновременно идут в БД!
        logger.warn("Cache MISS for product $id")
        
        val product = productRepository.findById(id).orElseThrow()
        // 1000 потоков выполняют этот запрос ОДНОВРЕМЕННО!
        
        redisTemplate.opsForValue().set(cacheKey, product, Duration.ofMinutes(5))
        
        return product
    }
}

// Нагрузка на БД при истечении популярного ключа:
// Threads: 1000 одновременно → DB connection pool exhausted → Timeout

// РЕШЕНИЕ 1: Распределённая блокировка (Redisson)
@Service
class ProductServiceWithLock(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
    private val redissonClient: RedissonClient
) {
    
    fun getPopularProduct(id: Long): Product {
        val cacheKey = "product:$id"
        val cached = redisTemplate.opsForValue().get(cacheKey)
        
        if (cached != null) return cached
        
        // Только 1 поток загружает из БД
        val lockKey = "lock:product:$id"
        val lock = redissonClient.getLock(lockKey)
        
        return if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            try {
                // Double-check: может другой поток уже загрузил
                val rechecked = redisTemplate.opsForValue().get(cacheKey)
                if (rechecked != null) {
                    logger.info("Product $id loaded by another thread")
                    return rechecked
                }
                
                logger.info("Thread ${Thread.currentThread().id} loading product $id")
                
                val product = productRepository.findById(id).orElseThrow()
                redisTemplate.opsForValue().set(
                    cacheKey,
                    product,
                    Duration.ofMinutes(5)
                )
                
                product
            } finally {
                if (lock.isHeldByCurrentThread) {
                    lock.unlock()
                }
            }
        } else {
            // Не получили блокировку — подождём и прочитаем из кэша
            logger.info("Thread ${Thread.currentThread().id} waiting for cache")
            
            Thread.sleep(100)  // Небольшая задержка
            
            redisTemplate.opsForValue().get(cacheKey)
                ?: productRepository.findById(id).orElseThrow()
                // Fallback: если всё ещё нет в кэше
        }
    }
}

// РЕШЕНИЕ 2: Probabilistic Early Expiration
@Service
class ProductServiceProbabilistic(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>
) {
    
    fun getProduct(id: Long): Product {
        val cacheKey = "product:$id"
        val ttlKey = "product:$id:ttl"
        
        val cached = redisTemplate.opsForValue().get(cacheKey)
        
        if (cached != null) {
            val ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS)
            
            if (ttl != null && ttl > 0) {
                // Вероятностная проверка: обновлять ли заранее?
                val delta = 5.0  // Среднее время загрузки из БД (секунды)
                val beta = 1.0
                
                val xfetch = delta * beta * Math.log(Random.nextDouble())
                
                if (ttl < xfetch) {
                    // Вероятностно обновляем ЗАРАНЕЕ (до истечения TTL)
                    logger.info("Proactively refreshing cache for product $id (TTL: $ttl)")
                    
                    CompletableFuture.runAsync {
                        refreshCache(id)
                    }
                }
            }
            
            return cached
        }
        
        return loadAndCache(id)
    }
    
    private fun refreshCache(id: Long) {
        try {
            val product = productRepository.findById(id).orElseThrow()
            redisTemplate.opsForValue().set(
                "product:$id",
                product,
                Duration.ofMinutes(5)
            )
        } catch (e: Exception) {
            logger.error("Failed to refresh cache for product $id", e)
        }
    }
    
    private fun loadAndCache(id: Long): Product {
        val product = productRepository.findById(id).orElseThrow()
        redisTemplate.opsForValue().set(
            "product:$id",
            product,
            Duration.ofMinutes(5)
        )
        return product
    }
}

// РЕШЕНИЕ 3: Stale-While-Revalidate (отдать старые, обновить фоном)
@Service
class ProductServiceStaleWhileRevalidate(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>
) {
    
    fun getProduct(id: Long): Product {
        val cacheKey = "product:$id"
        val staleKey = "product:$id:stale"
        
        val cached = redisTemplate.opsForValue().get(cacheKey)
        
        if (cached != null) {
            return cached
        }
        
        // Проверяем stale версию
        val stale = redisTemplate.opsForValue().get(staleKey)
        
        if (stale != null) {
            // Отдаём stale данные
            logger.info("Serving stale data for product $id")
            
            // Обновляем в фоне
            CompletableFuture.runAsync {
                val fresh = productRepository.findById(id).orElse(null)
                if (fresh != null) {
                    // Обновляем основной кэш
                    redisTemplate.opsForValue().set(cacheKey, fresh, Duration.ofMinutes(5))
                    // Обновляем stale (на случай следующего cache miss)
                    redisTemplate.opsForValue().set(staleKey, fresh, Duration.ofMinutes(10))
                }
            }
            
            return stale
        }
        
        // Нет ни fresh, ни stale → загружаем
        val product = productRepository.findById(id).orElseThrow()
        
        redisTemplate.opsForValue().set(cacheKey, product, Duration.ofMinutes(5))
        redisTemplate.opsForValue().set(staleKey, product, Duration.ofMinutes(10))
        
        return product
    }
}

// РЕШЕНИЕ 4: Circuit Breaker для защиты БД
@Service
class ProductServiceWithCircuitBreaker(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, Product>,
    private val circuitBreakerFactory: CircuitBreakerFactory<String, Resilience4JConfigBuilder.Resilience4JCircuitBreakerConfiguration>
) {
    
    fun getProduct(id: Long): Product {
        val cacheKey = "product:$id"
        val cached = redisTemplate.opsForValue().get(cacheKey)
        
        if (cached != null) return cached
        
        // Circuit Breaker защищает БД от перегрузки
        val circuitBreaker = circuitBreakerFactory.create("product-db")
        
        return try {
            circuitBreaker.run({
                val product = productRepository.findById(id).orElseThrow()
                redisTemplate.opsForValue().set(cacheKey, product, Duration.ofMinutes(5))
                product
            }, { throwable ->
                // Fallback: вернуть дефолтные данные
                logger.error("Circuit breaker open, returning default product", throwable)
                Product.default(id)
            })
        } catch (e: Exception) {
            throw CacheException("Failed to load product $id", e)
        }
    }
}

// Мониторинг Cache Stampede
@Component
class CacheStampedeDetector(
    private val meterRegistry: MeterRegistry
) {
    
    private val concurrentLoads = ConcurrentHashMap<String, AtomicInteger>()
    
    fun trackCacheLoad(key: String, block: () -> Unit) {
        val counter = concurrentLoads.computeIfAbsent(key) { AtomicInteger(0) }
        val concurrent = counter.incrementAndGet()
        
        try {
            if (concurrent > 10) {
                // Stampede detected!
                logger.warn("Cache stampede detected for key: $key (concurrent loads: $concurrent)")
                
                meterRegistry.counter(
                    "cache.stampede",
                    "key", key
                ).increment()
            }
            
            block()
        } finally {
            counter.decrementAndGet()
        }
    }
}
```
───────────────────────────────────────────────────────────────────────────────

## Распределённый кэш и координация

### КЕЙС #10 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Как использовать Redis Pub/Sub для инвалидации кэша в нескольких инстансах приложения?

**ОТВЕТ:**
**Проблема**: несколько инстансов приложения → каждый имеет локальный кэш (Caffeine) → несогласованность.

**Решение**: Redis Pub/Sub для broadcast инвалидации.

**ПРИМЕР КОДА:**
```kotlin
// Конфигурация Pub/Sub
@Configuration
class RedisPubSubConfig {
    
    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        cacheInvalidationListener: CacheInvalidationListener
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        
        // Подписка на канал инвалидации
        container.addMessageListener(
            cacheInvalidationListener,
            ChannelTopic("cache:invalidation")
        )
        
        return container
    }
}

// Listener для инвалидации
@Component
class CacheInvalidationListener(
    private val cacheManager: CacheManager
) : MessageListener {
    
    override fun onMessage(message: Message, pattern: ByteArray?) {
        val payload = String(message.body)
        val (cacheName, key) = payload.split(":", limit = 2)
        
        logger.info("Invalidating cache: $cacheName, key: $key")
        
        // Удаляем из локального кэша (Caffeine)
        cacheManager.getCache(cacheName)?.evict(key)
    }
}

// Сервис с двухуровневым кэшем
@Service
class UserService(
    private val userRepository: UserRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val cacheManager: CacheManager
) {
    
    // Level 1: локальный кэш (Caffeine) — быстро
    // Level 2: Redis — распределённый
    
    @Cacheable(value = ["users"], key = "#id")  // Локальный кэш
    fun getUserById(id: Long): User {
        // Проверяем Redis
        val redisKey = "user:$id"
        val cached = redisTemplate.opsForValue().get(redisKey)
        
        if (cached != null) {
            return objectMapper.readValue(cached, User::class.java)
        }
        
        // Загружаем из БД
        val user = userRepository.findById(id).orElseThrow()
        
        // Сохраняем в Redis
        redisTemplate.opsForValue().set(
            redisKey,
            objectMapper.writeValueAsString(user),
            Duration.ofMinutes(30)
        )
        
        return user
    }
    
    @Transactional
    fun updateUser(user: User): User {
        val updated = userRepository.save(user)
        
        // 1. Удаляем из Redis
        redisTemplate.delete("user:${user.id}")
        
        // 2. Публикуем событие инвалидации для ВСЕХ инстансов
        redisTemplate.convertAndSend(
            "cache:invalidation",
            "users:${user.id}"  // cacheName:key
        )
        
        logger.info("Published cache invalidation for user ${user.id}")
        
        return updated
    }
}

// Двухуровневый кэш (L1: Caffeine, L2: Redis)
@Configuration
@EnableCaching
class CacheConfig {
    
    @Bean
    fun cacheManager(
        connectionFactory: RedisConnectionFactory
    ): CacheManager {
        // L1: Caffeine (локальный, быстрый)
        val caffeineCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build<Any, Any>()
        
        val caffeineCacheManager = CaffeineCacheManager()
        caffeineCacheManager.setCaffeine(caffeineCache)
        
        // L2: Redis (распределённый)
        val redisConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
        
        val redisCacheManager = RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(redisConfig)
            .build()
        
        // Композитный cache manager
        return CompositeCacheManager(caffeineCacheManager, redisCacheManager)
    }
}

// Batch invalidation для связанных ключей
@Service
class ProductService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    fun updateProductCategory(categoryId: Long, newName: String) {
        categoryRepository.updateName(categoryId, newName)
        
        // Инвалидируем все товары этой категории
        val pattern = "product:category:$categoryId:*"
        
        val keys = redisTemplate.keys(pattern)
        
        if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
            
            // Публикуем batch invalidation
            redisTemplate.convertAndSend(
                "cache:invalidation:batch",
                "products:category:$categoryId"
            )
        }
    }
}

// Pattern subscription (wildcard)
@Configuration
class PatternSubscriptionConfig {
    
    @Bean
    fun patternListenerContainer(
        connectionFactory: RedisConnectionFactory,
        patternListener: PatternCacheListener
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        
        // Подписка на все каналы cache:*
        container.addMessageListener(
            patternListener,
            PatternTopic("cache:*")
        )
        
        return container
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #11 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как измерить эффективность кэша? Какие метрики важны?

**ОТВЕТ:**
**Ключевые метрики:**
1. **Hit Rate**: % запросов, найденных в кэше
2. **Miss Rate**: % запросов НЕ в кэше
3. **Eviction Rate**: как часто удаляются данные
4. **Latency**: время ответа (cache vs DB)
5. **Memory Usage**: занятая память

**Цель**: Hit Rate > 80%

**ПРИМЕР КОДА:**
```kotlin
// Мониторинг метрик через Micrometer
@Service
class CacheMetricsService(
    private val meterRegistry: MeterRegistry,
    private val redisTemplate: RedisTemplate<String, Any>
) {
    
    fun <T> getWithMetrics(
        key: String,
        cacheName: String,
        loader: () -> T
    ): T {
        val cacheKey = "$cacheName:$key"
        val start = System.nanoTime()
        
        val cached = redisTemplate.opsForValue().get(cacheKey) as? T
        
        if (cached != null) {
            // Cache HIT
            val latency = (System.nanoTime() - start) / 1_000_000.0
            
            meterRegistry.counter(
                "cache.requests",
                "cache", cacheName,
                "result", "hit"
            ).increment()
            
            meterRegistry.timer(
                "cache.latency",
                "cache", cacheName,
                "result", "hit"
            ).record(latency, TimeUnit.MILLISECONDS)
            
            return cached
        }
        
        // Cache MISS
        meterRegistry.counter(
            "cache.requests",
            "cache", cacheName,
            "result", "miss"
        ).increment()
        
        val loadStart = System.nanoTime()
        val loaded = loader()
        val loadLatency = (System.nanoTime() - loadStart) / 1_000_000.0
        
        meterRegistry.timer(
            "cache.load.latency",
            "cache", cacheName
        ).record(loadLatency, TimeUnit.MILLISECONDS)
        
        // Сохраняем в кэш
        redisTemplate.opsForValue().set(cacheKey, loaded as Any, Duration.ofMinutes(10))
        
        val totalLatency = (System.nanoTime() - start) / 1_000_000.0
        
        meterRegistry.timer(
            "cache.latency",
            "cache", cacheName,
            "result", "miss"
        ).record(totalLatency, TimeUnit.MILLISECONDS)
        
        return loaded
    }
}

// Расчёт Hit Rate
@Service
class CacheAnalyticsService(
    private val meterRegistry: MeterRegistry
) {
    
    fun getCacheStats(cacheName: String): CacheStats {
        val hits = meterRegistry.counter(
            "cache.requests",
            "cache", cacheName,
            "result", "hit"
        ).count()
        
        val misses = meterRegistry.counter(
            "cache.requests",
            "cache", cacheName,
            "result", "miss"
        ).count()
        
        val total = hits + misses
        val hitRate = if (total > 0) (hits / total) * 100 else 0.0
        
        val avgHitLatency = meterRegistry.timer(
            "cache.latency",
            "cache", cacheName,
            "result", "hit"
        ).mean(TimeUnit.MILLISECONDS)
        
        val avgMissLatency = meterRegistry.timer(
            "cache.latency",
            "cache", cacheName,
            "result", "miss"
        ).mean(TimeUnit.MILLISECONDS)
        
        return CacheStats(
            cacheName = cacheName,
            hits = hits.toLong(),
            misses = misses.toLong(),
            hitRate = hitRate,
            avgHitLatency = avgHitLatency,
            avgMissLatency = avgMissLatency
        )
    }
}

data class CacheStats(
    val cacheName: String,
    val hits: Long,
    val misses: Long,
    val hitRate: Double,
    val avgHitLatency: Double,
    val avgMissLatency: Double
)

// Actuator endpoint для cache stats
@RestController
@RequestMapping("/actuator/cache")
class CacheStatsController(
    private val cacheAnalyticsService: CacheAnalyticsService,
    private val redisTemplate: RedisTemplate<String, Any>
) {
    
    @GetMapping("/stats")
    fun getCacheStats(): Map<String, Any> {
        val caches = listOf("users", "products", "orders")
        
        return mapOf(
            "caches" to caches.associateWith { cacheName ->
                cacheAnalyticsService.getCacheStats(cacheName)
            },
            "redis" to getRedisInfo()
        )
    }
    
    private fun getRedisInfo(): Map<String, Any> {
        val info = redisTemplate.execute { connection ->
            connection.serverCommands().info("memory")
        }
        
        return mapOf(
            "usedMemory" to (info?.get("used_memory_human") ?: "unknown"),
            "maxMemory" to (info?.get("maxmemory_human") ?: "unknown"),
            "evictedKeys" to (info?.get("evicted_keys") ?: "0")
        )
    }
}

// Алерты при низком Hit Rate
@Component
class CacheHealthMonitor(
    private val cacheAnalyticsService: CacheAnalyticsService,
    private val alertService: AlertService
) {
    
    @Scheduled(fixedRate = 60000)  // Каждую минуту
    fun checkCacheHealth() {
        val caches = listOf("users", "products", "orders")
        
        caches.forEach { cacheName ->
            val stats = cacheAnalyticsService.getCacheStats(cacheName)
            
            if (stats.hitRate < 70.0 && stats.hits + stats.misses > 1000) {
                alertService.sendAlert(
                    "Low cache hit rate for $cacheName: ${stats.hitRate}%"
                )
            }
            
            if (stats.avgMissLatency > 1000) {  // > 1 секунды
                alertService.sendAlert(
                    "High cache miss latency for $cacheName: ${stats.avgMissLatency}ms"
                )
            }
        }
    }
}

// Cache size tracking
@Component
class CacheSizeMonitor(
    private val redisTemplate: RedisTemplate<String, Any>
) {
    
    @Scheduled(cron = "0 */5 * * * *")  // Каждые 5 минут
    fun trackCacheSize() {
        val caches = listOf("users", "products", "orders")
        
        caches.forEach { cacheName ->
            val pattern = "$cacheName:*"
            val keys = redisTemplate.keys(pattern)
            
            logger.info("Cache $cacheName size: ${keys?.size ?: 0} keys")
            
            meterRegistry.gauge(
                "cache.size",
                "cache", cacheName,
                keys?.size ?: 0
            )
        }
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #12 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое cache eviction policies? В чём разница между LRU, LFU, FIFO?

**ОТВЕТ:**
**Eviction Policy**: стратегия удаления данных при переполнении кэша.

**Политики:**
- **LRU (Least Recently Used)**: удаляет давно не использованные
- **LFU (Least Frequently Used)**: удаляет редко используемые
- **FIFO (First In First Out)**: удаляет самые старые
- **TTL**: удаляет по истечении времени

**ПРИМЕР КОДА:**
```kotlin
// Redis: настройка eviction policy
// redis.conf:
maxmemory 1gb
maxmemory-policy allkeys-lru

// Политики Redis:
// noeviction: ошибка при переполнении
// allkeys-lru: удаляет LRU ключи из ВСЕХ
// allkeys-lfu: удаляет LFU ключи из ВСЕХ
// allkeys-random: случайное удаление
// volatile-lru: LRU только среди ключей с TTL
// volatile-lfu: LFU только среди ключей с TTL
// volatile-ttl: удаляет ключи с наименьшим TTL

// Caffeine: LRU cache в Java/Kotlin
@Configuration
class LocalCacheConfig {
    
    @Bean
    fun userCache(): Cache<Long, User> {
        return Caffeine.newBuilder()
            .maximumSize(10_000)  // Максимум 10К записей
            .expireAfterWrite(10, TimeUnit.MINUTES)  // TTL
            .expireAfterAccess(5, TimeUnit.MINUTES)  // LRU: удаление после 5 мин без доступа
            .recordStats()  // Метрики
            .build()
    }
    
    @Bean
    fun productCache(): Cache<Long, Product> {
        return Caffeine.newBuilder()
            .maximumWeight(100_000_000)  // 100MB
            .weigher<Long, Product> { key, value ->
                // Вычисляем вес объекта
                estimateSize(value)
            }
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .removalListener<Long, Product> { key, value, cause ->
                logger.debug("Evicted product $key, cause: $cause")
            }
            .build()
    }
    
    private fun estimateSize(product: Product): Int {
        // Примерная оценка размера в байтах
        return product.name.length * 2 + 
               product.description.length * 2 + 
               100  // Прочие поля
    }
}

// Использование Caffeine cache
@Service
class ProductService(
    private val productCache: Cache<Long, Product>,
    private val productRepository: ProductRepository
) {
    
    fun getProduct(id: Long): Product {
        return productCache.get(id) { key ->
            // Cache miss: загружаем из БД
            productRepository.findById(key).orElseThrow()
        }
    }
    
    fun updateProduct(product: Product): Product {
        val saved = productRepository.save(product)
        
        // Инвалидируем кэш
        productCache.invalidate(product.id)
        
        return saved
    }
    
    // Статистика кэша
    fun getCacheStats(): CacheStatsData {
        val stats = productCache.stats()
        
        return CacheStatsData(
            hitCount = stats.hitCount(),
            missCount = stats.missCount(),
            hitRate = stats.hitRate(),
            evictionCount = stats.evictionCount(),
            estimatedSize = productCache.estimatedSize()
        )
    }
}

// Сравнение политик
// LRU: хорош для временной локальности (недавно использованные снова нужны)
//   Пример: пользовательские сессии, недавние заказы
//
// LFU: хорош для частотной локальности (популярные данные нужны чаще)
//   Пример: топ товаров, популярные статьи
//
// TTL: хорош для устаревающих данных
//   Пример: курсы валют, цены
//
// FIFO: простой, но неэффективный (не учитывает использование)

// Custom eviction policy
@Service
class PriorityCache<K, V>(
    private val maxSize: Int
) {
    
    private val cache = mutableMapOf<K, CacheEntry<V>>()
    private val priorityQueue = PriorityQueue<CacheEntry<V>>(
        compareBy { it.priority }
    )
    
    data class CacheEntry<V>(
        val key: Any,
        val value: V,
        var priority: Int,
        var lastAccess: Long = System.currentTimeMillis()
    )
    
    @Synchronized
    fun put(key: K, value: V, priority: Int) {
        if (cache.size >= maxSize) {
            // Evict lowest priority
            val evicted = priorityQueue.poll()
            cache.remove(evicted.key)
        }
        
        val entry = CacheEntry(key!!, value, priority)
        cache[key] = entry
        priorityQueue.add(entry)
    }
    
    @Synchronized
    fun get(key: K): V? {
        val entry = cache[key] ?: return null
        
        // Update priority on access (LRU + Priority)
        entry.lastAccess = System.currentTimeMillis()
        entry.priority++
        
        return entry.value
    }
}

// Использование приоритетного кэша
val priorityCache = PriorityCache<Long, Product>(maxSize = 1000)

// Топ товары — высокий приоритет
priorityCache.put(productId, product, priority = 100)

// Обычные товары — низкий приоритет
priorityCache.put(productId, product, priority = 10)
```
───────────────────────────────────────────────────────────────────────────────

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$2.90

*Версия: 1.0 | Январь 2026*

