# Apache Kafka для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Producer

### КЕЙС #1 | Уровень: Middle
**ВОПРОС:** Что такое acks в Kafka Producer? Разница между acks=0, 1, all?

**ОТВЕТ:**
```kotlin
// acks=0: не ждём подтверждения (fastest, но может потерять данные)
val props = Properties().apply {
    put(ProducerConfig.ACKS_CONFIG, "0")
    put(ProducerConfig.RETRIES_CONFIG, 0)
}
// Используется для метрик, логов (допустима потеря)

// acks=1: ждём подтверждения от leader (баланс)
put(ProducerConfig.ACKS_CONFIG, "1")
// Leader записал, но реплики могут не успеть → риск потери

// acks=all (или -1): ждём от leader + все in-sync реплики (safest)
put(ProducerConfig.ACKS_CONFIG, "all")
put(ProducerConfig.MIN_IN_SYNC_REPLICAS_CONFIG, 2)  // Минимум 2 реплики
// Гарантия: данные на >=2 брокерах

// Идемпотентность (exactly-once)
put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
// Автоматически: acks=all, retries=MAX_INT, max.in.flight=5

// Использование
val producer = KafkaProducer<String, String>(props)

val record = ProducerRecord("orders", orderId.toString(), orderJson)
producer.send(record) { metadata, exception ->
    if (exception != null) {
        logger.error("Failed to send", exception)
    } else {
        logger.info("Sent to partition ${metadata.partition()}, offset ${metadata.offset()}")
    }
}
```

## Consumer

### КЕЙС #5 | Уровень: Senior
**ВОПРОС:** Как работает Consumer Group? Что происходит при rebalancing?

**ОТВЕТ:**
```kotlin
// Consumer Group: несколько consumers читают из одного топика
// Каждая партиция читается только 1 consumer'ом из группы

// Конфигурация
val props = Properties().apply {
    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    put(ConsumerConfig.GROUP_ID_CONFIG, "order-processing-group")
    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
    
    // Offset commit strategy
    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)  // Ручной commit
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
}

val consumer = KafkaConsumer<String, String>(props)
consumer.subscribe(listOf("orders"))

// Потребление
while (true) {
    val records = consumer.poll(Duration.ofSeconds(1))
    
    records.forEach { record ->
        try {
            processOrder(record.value())
            
            // Commit ПОСЛЕ успешной обработки
            consumer.commitSync(
                mapOf(
                    TopicPartition(record.topic(), record.partition()) 
                        to OffsetAndMetadata(record.offset() + 1)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to process record", e)
            // Не коммитим — повторим при следующем poll()
        }
    }
}

// REBALANCING: перераспределение партиций
// Происходит когда:
// 1. Добавляется/удаляется consumer
// 2. Consumer падает (heartbeat timeout)
// 3. Добавляются новые партиции

// Обработка rebalancing
consumer.subscribe(
    listOf("orders"),
    object : ConsumerRebalanceListener {
        override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
            // Вызывается ПЕРЕД rebalancing
            logger.warn("Partitions revoked: $partitions")
            consumer.commitSync()  // Коммитим обработанные offset'ы
        }
        
        override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
            // Вызывается ПОСЛЕ rebalancing
            logger.info("Partitions assigned: $partitions")
        }
    }
)
```

### КЕЙС #2 | Уровень: Middle
**ВОПРОС:** Как правильно выбрать ключ партиционирования для Kafka Producer?

**ОТВЕТ:**
```kotlin
// Партиционирование определяет, в какую партицию попадёт сообщение
// Сообщения с одним ключом → всегда в одну партицию → порядок гарантирован

// ❌ ПЛОХО: без ключа (round-robin)
producer.send(ProducerRecord("orders", null, orderJson))
// Сообщения одного заказа могут попасть в разные партиции → нарушится порядок

// ✅ ХОРОШО: ключ = orderId
producer.send(ProducerRecord("orders", orderId.toString(), orderJson))
// Все события одного заказа → в одну партицию → порядок сохраняется

// Пример: обработка заказа
@Service
class OrderEventProducer(private val kafkaTemplate: KafkaTemplate<String, OrderEvent>) {
    
    fun sendOrderEvent(orderId: String, event: OrderEvent) {
        // orderId как ключ — все события заказа в одной партиции
        kafkaTemplate.send("order-events", orderId, event)
            .whenComplete { result, ex ->
                if (ex == null) {
                    logger.info("Event sent to partition ${result.recordMetadata.partition()}")
                } else {
                    logger.error("Failed to send event", ex)
                }
            }
    }
}

// Кастомный партиционер
class CustomerPartitioner : Partitioner {
    override fun partition(
        topic: String,
        key: Any?,
        keyBytes: ByteArray?,
        value: Any?,
        valueBytes: ByteArray?,
        cluster: Cluster
    ): Int {
        val customerId = (key as String).substringBefore("-")
        val partitions = cluster.partitionsForTopic(topic).size
        
        // VIP клиенты → всегда в партицию 0 (быстрая обработка)
        return if (isVipCustomer(customerId)) 0 
               else abs(customerId.hashCode()) % (partitions - 1) + 1
    }
}
```

### КЕЙС #3 | Уровень: Senior
**ВОПРОС:** Что такое идемпотентность в Kafka Producer? Как она работает?

**ОТВЕТ:**
```kotlin
// Идемпотентность: повторная отправка не создаст дубликаты
// Producer назначает каждому сообщению sequence number

// Включение идемпотентности
val props = Properties().apply {
    put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
    // Автоматически устанавливается:
    // - acks=all
    // - retries=Integer.MAX_VALUE
    // - max.in.flight.requests.per.connection=5
}

// Как это работает:
// Producer генерирует PID (Producer ID) и sequence number для каждого сообщения
// Broker отклоняет дубликаты (с тем же PID и sequence number)

@Configuration
class KafkaProducerConfig {
    
    @Bean
    fun producerFactory(): ProducerFactory<String, OrderEvent> {
        val props = mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            
            // Идемпотентность + транзакции = exactly-once
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.TRANSACTIONAL_ID_CONFIG to "order-producer-1"
        )
        return DefaultKafkaProducerFactory(props)
    }
}

// Использование с транзакциями
@Service
class TransactionalOrderProducer(
    private val kafkaTemplate: KafkaTemplate<String, OrderEvent>
) {
    
    @Transactional("kafkaTransactionManager")
    fun processOrder(order: Order) {
        // Все send() в рамках одной транзакции
        kafkaTemplate.send("orders", order.id.toString(), OrderCreatedEvent(order))
        kafkaTemplate.send("inventory", order.productId.toString(), ReserveStockEvent(order))
        
        // Если упадёт — откатятся ОБА сообщения
        if (order.amount > 10000) {
            kafkaTemplate.send("alerts", "large-order", LargeOrderAlert(order))
        }
    }
}
```

### КЕЙС #4 | Уровень: Middle
**ВОПРОС:** Что такое max.in.flight.requests.per.connection? Как это влияет на порядок сообщений?

**ОТВЕТ:**
```kotlin
// max.in.flight.requests.per.connection — количество неподтверждённых запросов
// которые Producer может отправить одновременно

// ❌ ПРОБЛЕМА: нарушение порядка при max.in.flight > 1 + retries > 0
// Batch 1: [msg1, msg2] — отправлен
// Batch 2: [msg3, msg4] — отправлен
// Batch 1 упал → retry
// Batch 2 успешно записан
// Batch 1 retry успешен
// Порядок в топике: msg3, msg4, msg1, msg2 ❌

// ✅ РЕШЕНИЕ 1: max.in.flight = 1 (медленно)
val props = Properties().apply {
    put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
    put(ProducerConfig.RETRIES_CONFIG, Int.MAX_VALUE)
}
// Порядок гарантирован, но throughput страдает

// ✅ РЕШЕНИЕ 2: идемпотентность (рекомендуется)
put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)  // До 5 разрешено
// Broker гарантирует порядок за счёт sequence numbers

@Configuration
class OrderedKafkaProducerConfig {
    
    @Bean
    fun producerFactory(): ProducerFactory<String, String> {
        return DefaultKafkaProducerFactory(mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            
            // Гарантия порядка + производительность
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE
        ))
    }
}
```

## Consumer

### КЕЙС #5 | Уровень: Senior
**ВОПРОС:** Как работает Consumer Group? Что происходит при rebalancing?

**ОТВЕТ:**
```kotlin
// Consumer Group: несколько consumers читают из одного топика
// Каждая партиция читается только 1 consumer'ом из группы

// Конфигурация
val props = Properties().apply {
    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    put(ConsumerConfig.GROUP_ID_CONFIG, "order-processing-group")
    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
    
    // Offset commit strategy
    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)  // Ручной commit
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
}

val consumer = KafkaConsumer<String, String>(props)
consumer.subscribe(listOf("orders"))

// Потребление
while (true) {
    val records = consumer.poll(Duration.ofSeconds(1))
    
    records.forEach { record ->
        try {
            processOrder(record.value())
            
            // Commit ПОСЛЕ успешной обработки
            consumer.commitSync(
                mapOf(
                    TopicPartition(record.topic(), record.partition()) 
                        to OffsetAndMetadata(record.offset() + 1)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to process record", e)
            // Не коммитим — повторим при следующем poll()
        }
    }
}

// REBALANCING: перераспределение партиций
// Происходит когда:
// 1. Добавляется/удаляется consumer
// 2. Consumer падает (heartbeat timeout)
// 3. Добавляются новые партиции

// Обработка rebalancing
consumer.subscribe(
    listOf("orders"),
    object : ConsumerRebalanceListener {
        override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
            // Вызывается ПЕРЕД rebalancing
            logger.warn("Partitions revoked: $partitions")
            consumer.commitSync()  // Коммитим обработанные offset'ы
        }
        
        override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
            // Вызывается ПОСЛЕ rebalancing
            logger.info("Partitions assigned: $partitions")
        }
    }
)
```

### КЕЙС #6 | Уровень: Middle
**ВОПРОС:** В чём разница между commitSync() и commitAsync()? Когда использовать каждый?

**ОТВЕТ:**
```kotlin
// commitSync(): блокирует поток, ждёт подтверждения от брокера
@KafkaListener(topics = ["orders"], groupId = "order-processing")
fun consumeOrderSync(message: OrderEvent) {
    processOrder(message)
    
    // ❌ ПРОБЛЕМА: блокирует Consumer loop
    // Throughput страдает, но гарантия что offset записан
}

// Spring Kafka по умолчанию использует batch commit после каждого poll()

// commitAsync(): не блокирует, callback при завершении
val consumer = KafkaConsumer<String, String>(props)
while (true) {
    val records = consumer.poll(Duration.ofMillis(100))
    
    records.forEach { record ->
        processOrder(record.value())
    }
    
    // ✅ Асинхронный commit (не блокирует)
    consumer.commitAsync { offsets, exception ->
        if (exception != null) {
            logger.error("Commit failed for offsets: $offsets", exception)
            // Можно retry или fallback на commitSync()
        } else {
            logger.debug("Committed offsets: $offsets")
        }
    }
}

// ✅ BEST PRACTICE: commitAsync() в loop + commitSync() при shutdown
class OrderConsumer : AutoCloseable {
    private val consumer = KafkaConsumer<String, String>(props)
    @Volatile private var running = true
    
    fun start() {
        consumer.subscribe(listOf("orders"))
        
        try {
            while (running) {
                val records = consumer.poll(Duration.ofMillis(100))
                processRecords(records)
                
                // Async commit для производительности
                consumer.commitAsync()
            }
        } finally {
            // Sync commit при shutdown — гарантия сохранения offset
            consumer.commitSync()
            consumer.close()
        }
    }
    
    override fun close() {
        running = false
    }
}

// Spring Kafka конфигурация
@Configuration
class KafkaConsumerConfig {
    
    @Bean
    fun consumerFactory(): ConsumerFactory<String, OrderEvent> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "order-processing",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,  // Ручной commit
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
        ))
    }
    
    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, OrderEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, OrderEvent>()
        factory.consumerFactory = consumerFactory()
        
        // AckMode.MANUAL: ручной контроль commit
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
        
        return factory
    }
}

@Service
class ManualCommitConsumer {
    
    @KafkaListener(topics = ["orders"])
    fun consume(message: OrderEvent, acknowledgment: Acknowledgment) {
        try {
            processOrder(message)
            acknowledgment.acknowledge()  // Async commit
        } catch (e: Exception) {
            // Не ack — повторим при следующем poll()
            logger.error("Failed to process", e)
        }
    }
}
```

### КЕЙС #7 | Уровень: Senior
**ВОПРОС:** Как обрабатывать backpressure в Kafka Consumer?

**ОТВЕТ:**
```kotlin
// Backpressure: Consumer не успевает обрабатывать сообщения
// Решения:

// 1. Ограничение max.poll.records
@Configuration
class BackpressureConfig {
    
    @Bean
    fun consumerFactory(): ConsumerFactory<String, OrderEvent> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 10,  // Не более 10 за раз
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 300_000  // 5 минут на обработку
        ))
    }
}

// 2. Pause/Resume партиций
@Service
class AdaptiveConsumer {
    
    @KafkaListener(topics = ["orders"])
    fun consume(
        message: OrderEvent,
        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) partition: Int,
        consumer: Consumer<*, *>
    ) {
        // Проверяем нагрузку
        val queueSize = processingQueue.size()
        
        if (queueSize > 1000) {
            // Приостанавливаем чтение из партиции
            val topicPartition = TopicPartition("orders", partition)
            consumer.pause(listOf(topicPartition))
            logger.warn("Paused partition $partition due to backpressure")
            
            // Через некоторое время возобновляем
            scheduleResume(consumer, topicPartition)
        }
        
        processingQueue.add(message)
    }
}

// 3. Thread pool для параллельной обработки
@Configuration
class ParallelConsumerConfig {
    
    @Bean
    fun kafkaListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, OrderEvent> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, OrderEvent>()
        factory.consumerFactory = consumerFactory()
        
        // 10 параллельных Consumer'ов
        factory.setConcurrency(10)
        
        return factory
    }
}

// 4. Reactive подход (Spring Kafka + Project Reactor)
@Service
class ReactiveKafkaConsumer(
    private val receiverOptions: ReceiverOptions<String, OrderEvent>
) {
    
    fun startConsuming() {
        KafkaReceiver.create(receiverOptions)
            .receive()
            .flatMap({ record ->
                // Асинхронная обработка с backpressure
                processOrderAsync(record.value())
                    .doOnSuccess { record.receiverOffset().acknowledge() }
                    .onErrorResume { error ->
                        logger.error("Failed to process", error)
                        Mono.empty()
                    }
            }, 10)  // Concurrency = 10
            .subscribe()
    }
    
    private fun processOrderAsync(order: OrderEvent): Mono<Void> {
        return Mono.fromCallable {
            // Тяжёлая обработка
            processOrder(order)
        }.subscribeOn(Schedulers.boundedElastic())
            .then()
    }
}
```

### КЕЙС #8 | Уровень: Middle
**ВОПРОС:** Что такое auto.offset.reset? В чём разница между earliest и latest?

**ОТВЕТ:**
```kotlin
// auto.offset.reset: что делать, если offset отсутствует в группе
// (новая группа или offset удалён из-за retention)

// earliest: начать с самого начала топика
val propsEarliest = Properties().apply {
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
}
// Использование: гарантия что не пропустим сообщения (например, audit log)

// latest: начать с новых сообщений (default)
val propsLatest = Properties().apply {
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
}
// Использование: нам нужны только свежие данные (например, real-time alerts)

// none: бросить исключение
val propsNone = Properties().apply {
    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
}
// Использование: критичные системы, где пропуск сообщений недопустим

// Пример: аудит + алерты
@Configuration
class MultiConsumerConfig {
    
    // Аудит: читаем ВСЁ
    @Bean("auditConsumerFactory")
    fun auditConsumerFactory(): ConsumerFactory<String, AuditEvent> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "audit-consumer",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"  // С начала
        ))
    }
    
    // Алерты: только новые
    @Bean("alertConsumerFactory")
    fun alertConsumerFactory(): ConsumerFactory<String, AlertEvent> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "alert-consumer",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest"  // Только новые
        ))
    }
}

@Service
class AuditConsumer {
    
    @KafkaListener(
        topics = ["audit-events"],
        containerFactory = "auditConsumerFactory"
    )
    fun consumeAudit(event: AuditEvent) {
        // Обрабатываем все события с самого начала
        auditRepository.save(event)
    }
}

@Service
class AlertConsumer {
    
    @KafkaListener(
        topics = ["alerts"],
        containerFactory = "alertConsumerFactory"
    )
    fun consumeAlert(event: AlertEvent) {
        // Обрабатываем только новые алерты
        notificationService.send(event)
    }
}
```

### КЕЙС #9 | Уровень: Senior
**ВОПРОС:** Как реализовать Exactly-Once Semantics в Kafka Consumer?

**ОТВЕТ:**
```kotlin
// Exactly-Once: сообщение обработано ровно один раз
// Комбинация: идемпотентный Producer + транзакции + правильный Consumer

// 1. Producer с транзакциями
@Configuration
class ExactlyOnceProducerConfig {
    
    @Bean
    fun producerFactory(): ProducerFactory<String, OrderEvent> {
        return DefaultKafkaProducerFactory(mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
            ProducerConfig.TRANSACTIONAL_ID_CONFIG to "order-producer-${UUID.randomUUID()}"
        ))
    }
    
    @Bean
    fun kafkaTransactionManager(): KafkaTransactionManager<String, OrderEvent> {
        return KafkaTransactionManager(producerFactory())
    }
}

// 2. Consumer с isolation.level=read_committed
@Configuration
class ExactlyOnceConsumerConfig {
    
    @Bean
    fun consumerFactory(): ConsumerFactory<String, OrderEvent> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "order-processor",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed"  // Только committed
        ))
    }
}

// 3. Транзакционная обработка: Kafka → DB → Kafka
@Service
class ExactlyOnceOrderProcessor(
    private val kafkaTemplate: KafkaTemplate<String, OrderEvent>,
    private val orderRepository: OrderRepository
) {
    
    @Transactional("kafkaTransactionManager")
    @KafkaListener(topics = ["orders"])
    fun processOrder(orderEvent: OrderEvent, acknowledgment: Acknowledgment) {
        // Всё в рамках одной транзакции:
        // 1. Сохраняем в DB
        val order = orderRepository.save(orderEvent.toEntity())
        
        // 2. Отправляем в другой топик
        kafkaTemplate.send("order-processed", order.id.toString(), OrderProcessedEvent(order))
        
        // 3. Commit offset
        acknowledgment.acknowledge()
        
        // Если что-то упадёт — откатятся ВСЕ изменения (DB + Kafka)
    }
}

// 4. Идемпотентная обработка (на случай дубликатов)
@Service
class IdempotentOrderProcessor(
    private val orderRepository: OrderRepository,
    private val processedEventsRepository: ProcessedEventRepository
) {
    
    @KafkaListener(topics = ["orders"])
    @Transactional
    fun processOrder(
        orderEvent: OrderEvent,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) partition: Int
    ) {
        val eventId = "orders-$partition-$offset"
        
        // Проверяем, не обрабатывали ли уже
        if (processedEventsRepository.existsById(eventId)) {
            logger.info("Event $eventId already processed, skipping")
            return
        }
        
        // Обрабатываем
        val order = orderRepository.save(orderEvent.toEntity())
        
        // Помечаем как обработанное
        processedEventsRepository.save(ProcessedEvent(eventId, Instant.now()))
        
        logger.info("Processed order ${order.id}")
    }
}

// 5. Transactional Outbox для гарантированной доставки
@Entity
data class OutboxEvent(
    @Id val id: UUID = UUID.randomUUID(),
    val topic: String,
    val key: String,
    val payload: String,
    var status: OutboxStatus = OutboxStatus.PENDING,
    val createdAt: Instant = Instant.now()
)

@Service
class TransactionalOutboxService(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    
    @Transactional
    fun saveOrderAndScheduleEvent(order: Order) {
        // 1. Сохраняем заказ
        orderRepository.save(order)
        
        // 2. Сохраняем событие в Outbox (в той же транзакции)
        outboxRepository.save(
            OutboxEvent(
                topic = "orders",
                key = order.id.toString(),
                payload = objectMapper.writeValueAsString(order)
            )
        )
        // Если упадёт — откатится и заказ, и событие
    }
    
    @Scheduled(fixedDelay = 1000)
    fun publishPendingEvents() {
        val pending = outboxRepository.findTop100ByStatusOrderByCreatedAt(OutboxStatus.PENDING)
        
        pending.forEach { event ->
            try {
                kafkaTemplate.send(event.topic, event.key, event.payload).get()
                
                event.status = OutboxStatus.PUBLISHED
                outboxRepository.save(event)
            } catch (e: Exception) {
                logger.error("Failed to publish event ${event.id}", e)
            }
        }
    }
}
```

## DLQ (Dead Letter Queue)

### КЕЙС #10 | Уровень: Middle
**ВОПРОС:** Как реализовать Dead Letter Queue для сообщений, которые не удалось обработать после N попыток?

**ОТВЕТ:**
```kotlin
@Service
class OrderConsumerWithDLQ(
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    
    @KafkaListener(topics = ["orders"], groupId = "order-processing")
    fun consumeOrder(
        message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION_ID) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long
    ) {
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            try {
                processOrder(message)
                return  // Успешно обработано
            } catch (e: Exception) {
                attempts++
                logger.warn("Failed attempt $attempts/$maxAttempts", e)
                
                if (attempts < maxAttempts) {
                    Thread.sleep(1000 * attempts)  // Exponential backoff
                }
            }
        }
        
        // После 3 попыток → DLQ
        logger.error("Sending to DLQ after $maxAttempts attempts")
        kafkaTemplate.send(
            "orders-dlq",
            DLQMessage(
                originalMessage = message,
                partition = partition,
                offset = offset,
                attempts = attempts,
                lastError = "Processing failed",
                timestamp = System.currentTimeMillis()
            )
        )
    }
    
    private fun processOrder(message: String) {
        // Бизнес-логика
    }
}

// Consumer для DLQ (ручная обработка)
@KafkaListener(topics = ["orders-dlq"], groupId = "dlq-handler")
fun handleDLQ(dlqMessage: DLQMessage) {
    logger.error("DLQ message: ${dlqMessage.originalMessage}")
    
    // Опции:
    // 1. Алерт в мониторинг
    alertService.sendAlert("DLQ message received", dlqMessage)
    
    // 2. Сохранение в БД для ручной обработки
    dlqRepository.save(dlqMessage)
    
    // 3. Попытка автоматической корректировки данных
    // val corrected = tryCorrect(dlqMessage.originalMessage)
    // if (corrected != null) kafkaTemplate.send("orders", corrected)
}
```

## Schema Registry

### КЕЙС #11 | Уровень: Senior
**ВОПРОС:** Зачем нужен Schema Registry? Как работать с Avro в Kafka?

**ОТВЕТ:**
```kotlin
// Schema Registry: централизованное хранение схем данных
// Преимущества:
// - Контроль совместимости (backward, forward, full)
// - Компактная сериализация (binary)
// - Автоматическая валидация

// 1. Avro схема (order.avsc)
"""
{
  "type": "record",
  "name": "Order",
  "namespace": "com.example.events",
  "fields": [
    {"name": "id", "type": "string"},
    {"name": "customerId", "type": "string"},
    {"name": "amount", "type": "double"},
    {"name": "status", "type": {
      "type": "enum",
      "name": "OrderStatus",
      "symbols": ["CREATED", "PAID", "SHIPPED", "DELIVERED"]
    }}
  ]
}
"""

// 2. Producer конфигурация
@Configuration
class AvroProducerConfig {
    
    @Bean
    fun producerFactory(): ProducerFactory<String, Order> {
        return DefaultKafkaProducerFactory(mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            
            // Avro serializer
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to KafkaAvroSerializer::class.java,
            "schema.registry.url" to "http://localhost:8081"
        ))
    }
}

@Service
class OrderProducer(private val kafkaTemplate: KafkaTemplate<String, Order>) {
    
    fun sendOrder(order: Order) {
        // Serializer автоматически:
        // 1. Регистрирует схему в Registry (если новая)
        // 2. Сериализует в binary Avro
        // 3. Добавляет schema ID в начало сообщения
        kafkaTemplate.send("orders", order.id, order)
    }
}

// 3. Consumer конфигурация
@Configuration
class AvroConsumerConfig {
    
    @Bean
    fun consumerFactory(): ConsumerFactory<String, Order> {
        return DefaultKafkaConsumerFactory(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG to "order-processor",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            
            // Avro deserializer
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to KafkaAvroDeserializer::class.java,
            "schema.registry.url" to "http://localhost:8081",
            "specific.avro.reader" to true  // Использовать сгенерированные классы
        ))
    }
}

@Service
class OrderConsumer {
    
    @KafkaListener(topics = ["orders"])
    fun consume(order: Order) {
        // Deserializer автоматически:
        // 1. Читает schema ID из сообщения
        // 2. Получает схему из Registry
        // 3. Десериализует в объект Order
        
        logger.info("Received order: ${order.id}, status: ${order.status}")
    }
}

// 4. Эволюция схемы (добавление поля)
// order-v2.avsc
"""
{
  "type": "record",
  "name": "Order",
  "namespace": "com.example.events",
  "fields": [
    {"name": "id", "type": "string"},
    {"name": "customerId", "type": "string"},
    {"name": "amount", "type": "double"},
    {"name": "status", "type": "OrderStatus"},
    {"name": "discount", "type": ["null", "double"], "default": null}  // Новое поле
  ]
}
"""

// Типы совместимости:
// BACKWARD (default): новый Consumer может читать старые сообщения
// FORWARD: старый Consumer может читать новые сообщения
// FULL: и то, и другое
// NONE: без проверки

// Настройка совместимости
@Bean
fun schemaRegistryClient(): SchemaRegistryClient {
    return CachedSchemaRegistryClient("http://localhost:8081", 100).apply {
        updateCompatibility("orders-value", "BACKWARD")
    }
}
```

## Kafka Streams

### КЕЙС #12 | Уровень: Senior
**ВОПРОС:** Как использовать Kafka Streams для обработки событий в real-time?

**ОТВЕТ:**
```kotlin
// Kafka Streams: библиотека для stream processing
// Преимущества: stateful processing, exactly-once, fault-tolerant

@Configuration
@EnableKafkaStreams
class KafkaStreamsConfig {
    
    @Bean
    fun kStreamsConfig(): KafkaStreamsConfiguration {
        return KafkaStreamsConfiguration(mapOf(
            StreamsConfig.APPLICATION_ID_CONFIG to "order-analytics",
            StreamsConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
            StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG to Serdes.String()::class.java,
            StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG to JsonSerde::class.java,
            
            // Exactly-once
            StreamsConfig.PROCESSING_GUARANTEE_CONFIG to StreamsConfig.EXACTLY_ONCE_V2
        ))
    }
}

// Пример 1: Агрегация заказов по статусу
@Component
class OrderStatusAggregator {
    
    @Bean
    fun orderStatusStream(streamsBuilder: StreamsBuilder): KStream<String, Order> {
        val orders: KStream<String, Order> = streamsBuilder.stream("orders")
        
        // Группировка по статусу + подсчёт
        orders
            .groupBy({ key, order -> order.status.name }, Grouped.with(Serdes.String(), orderSerde))
            .count(Materialized.as("order-count-by-status"))
            .toStream()
            .to("order-statistics", Produced.with(Serdes.String(), Serdes.Long()))
        
        return orders
    }
}

// Пример 2: Joining заказов и платежей
@Component
class OrderPaymentJoiner {
    
    @Bean
    fun orderPaymentStream(streamsBuilder: StreamsBuilder): KStream<String, OrderWithPayment> {
        val orders: KStream<String, Order> = streamsBuilder.stream("orders")
        val payments: KTable<String, Payment> = streamsBuilder.table("payments")
        
        // Join по orderId
        return orders.join(
            payments,
            { order, payment -> OrderWithPayment(order, payment) },
            Joined.with(Serdes.String(), orderSerde, paymentSerde)
        ).to("orders-with-payments")
    }
}

// Пример 3: Windowed aggregation (заказы за последний час)
@Component
class HourlyOrderAnalytics {
    
    @Bean
    fun hourlyRevenue(streamsBuilder: StreamsBuilder): KStream<String, Order> {
        val orders: KStream<String, Order> = streamsBuilder.stream("orders")
        
        orders
            .groupByKey()
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofHours(1)))
            .aggregate(
                { 0.0 },
                { key, order, total -> total + order.amount },
                Materialized.with(Serdes.String(), Serdes.Double())
            )
            .toStream()
            .map { windowedKey, total ->
                KeyValue(
                    windowedKey.key(),
                    RevenueReport(
                        period = windowedKey.window().startTime(),
                        total = total
                    )
                )
            }
            .to("hourly-revenue")
        
        return orders
    }
}

// Пример 4: Фильтрация + трансформация
@Component
class LargeOrderProcessor {
    
    @Bean
    fun largeOrdersStream(streamsBuilder: StreamsBuilder): KStream<String, Order> {
        return streamsBuilder
            .stream<String, Order>("orders")
            .filter { key, order -> order.amount > 10000 }
            .mapValues { order ->
                LargeOrderAlert(
                    orderId = order.id,
                    amount = order.amount,
                    customerId = order.customerId,
                    timestamp = Instant.now()
                )
            }
            .to("large-order-alerts")
    }
}

// Interactive Queries: чтение state store
@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(
    private val kafkaStreams: KafkaStreams
) {
    
    @GetMapping("/order-count/{status}")
    fun getOrderCountByStatus(@PathVariable status: String): Long {
        val store: ReadOnlyKeyValueStore<String, Long> = 
            kafkaStreams.store(
                StoreQueryParameters.fromNameAndType(
                    "order-count-by-status",
                    QueryableStoreTypes.keyValueStore()
                )
            )
        
        return store.get(status) ?: 0L
    }
}
```

## Performance & Monitoring

### КЕЙС #13 | Уровень: Middle
**ВОПРОС:** Как мониторить Kafka Producer/Consumer? Какие метрики важны?

**ОТВЕТ:**
```kotlin
// Мониторинг через Micrometer (Spring Boot Actuator)

@Configuration
class KafkaMonitoringConfig {
    
    @Bean
    fun kafkaMetricsProducer(meterRegistry: MeterRegistry): ProducerFactory<String, OrderEvent> {
        val factory = DefaultKafkaProducerFactory<String, OrderEvent>(producerProps())
        
        // Добавляем метрики Producer
        factory.addListener(MicrometerProducerListener(meterRegistry))
        
        return factory
    }
    
    @Bean
    fun kafkaMetricsConsumer(meterRegistry: MeterRegistry): ConsumerFactory<String, OrderEvent> {
        val factory = DefaultKafkaConsumerFactory<String, OrderEvent>(consumerProps())
        
        // Добавляем метрики Consumer
        factory.addListener(MicrometerConsumerListener(meterRegistry))
        
        return factory
    }
}

// Ключевые метрики Producer:
// - record-send-rate: сообщений/сек
// - record-error-rate: ошибок/сек
// - request-latency-avg: средняя задержка
// - buffer-available-bytes: доступная память в буфере

// Ключевые метрики Consumer:
// - records-consumed-rate: сообщений/сек
// - records-lag-max: максимальное отставание
// - fetch-latency-avg: средняя задержка fetch
// - commit-latency-avg: средняя задержка commit

@Service
class KafkaHealthIndicator(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val listenerContainerRegistry: KafkaListenerEndpointRegistry
) : HealthIndicator {
    
    override fun health(): Health {
        // Проверяем доступность Producer
        val producerHealthy = try {
            kafkaTemplate.send("health-check", "ping").get(5, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        }
        
        // Проверяем Consumer
        val consumerHealthy = listenerContainerRegistry.listenerContainers
            .all { it.isRunning }
        
        return if (producerHealthy && consumerHealthy) {
            Health.up()
                .withDetail("producer", "UP")
                .withDetail("consumer", "UP")
                .build()
        } else {
            Health.down()
                .withDetail("producer", if (producerHealthy) "UP" else "DOWN")
                .withDetail("consumer", if (consumerHealthy) "UP" else "DOWN")
                .build()
        }
    }
}

// Кастомные метрики
@Service
class OrderConsumerWithMetrics(
    private val meterRegistry: MeterRegistry
) {
    
    private val processedCounter = meterRegistry.counter("orders.processed")
    private val failedCounter = meterRegistry.counter("orders.failed")
    private val processingTimer = meterRegistry.timer("orders.processing.time")
    
    @KafkaListener(topics = ["orders"])
    fun consume(order: OrderEvent) {
        processingTimer.recordCallable {
            try {
                processOrder(order)
                processedCounter.increment()
            } catch (e: Exception) {
                failedCounter.increment()
                throw e
            }
        }
    }
}

// Алерты на основе метрик
@Component
class ConsumerLagAlert(
    private val meterRegistry: MeterRegistry,
    private val alertService: AlertService
) {
    
    @Scheduled(fixedDelay = 60000)  // Каждую минуту
    fun checkConsumerLag() {
        val lag = meterRegistry.get("kafka.consumer.records.lag.max")
            .tag("topic", "orders")
            .gauge()
            .value()
        
        if (lag > 10000) {
            alertService.send("High consumer lag detected: $lag messages")
        }
    }
}
```

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$1.20

*Версия: 2.0 | Январь 2026*

