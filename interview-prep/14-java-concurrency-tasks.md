# Java многопоточность — задачи для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Базовые задачи

### ЗАДАЧА #1 | Уровень: Middle
**УСЛОВИЕ:** Реализовать thread-safe счётчик с методами `increment()` и `get()`.

**РЕШЕНИЕ:**
```java
// Вариант 1: synchronized
class Counter {
    private int count = 0;
    
    public synchronized void increment() {
        count++;
    }
    
    public synchronized int get() {
        return count;
    }
}

// Вариант 2: AtomicInteger (эффективнее)
class AtomicCounter {
    private final AtomicInteger count = new AtomicInteger(0);
    
    public void increment() {
        count.incrementAndGet();
    }
    
    public int get() {
        return count.get();
    }
}

// Вариант 3: ReentrantLock (больший контроль)
class LockCounter {
    private int count = 0;
    private final ReentrantLock lock = new ReentrantLock();
    
    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();
        }
    }
    
    public int get() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}

// Тест
Counter counter = new AtomicCounter();
ExecutorService executor = Executors.newFixedThreadPool(10);

for (int i = 0; i < 1000; i++) {
    executor.submit(counter::increment);
}

executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);

System.out.println("Count: " + counter.get());  // 1000
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `synchronized` — простой, но может быть медленным
- `AtomicInteger` — lock-free, эффективнее для простых операций
- `ReentrantLock` — больше возможностей (tryLock, interruptible locks)

### ЗАДАЧА #2 | Уровень: Middle
**УСЛОВИЕ:** Реализовать Producer-Consumer pattern с очередью на 10 элементов.

**РЕШЕНИЕ:**
```java
// Вариант 1: wait/notify (классический подход)
class ProducerConsumer {
    private final Queue<Integer> queue = new LinkedList<>();
    private final int MAX_SIZE = 10;
    
    public synchronized void produce(int value) throws InterruptedException {
        while (queue.size() == MAX_SIZE) {
            wait();  // Ждём, пока освободится место
        }
        
        queue.add(value);
        System.out.println("Produced: " + value + ", size: " + queue.size());
        notifyAll();  // Уведомляем consumers
    }
    
    public synchronized int consume() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();  // Ждём, пока появятся элементы
        }
        
        int value = queue.poll();
        System.out.println("Consumed: " + value + ", size: " + queue.size());
        notifyAll();  // Уведомляем producers
        return value;
    }
}

// Вариант 2: BlockingQueue (рекомендуется)
class BlockingQueueExample {
    private final BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
    
    public void produce(int value) throws InterruptedException {
        queue.put(value);  // Блокируется, если очередь полная
        System.out.println("Produced: " + value);
    }
    
    public int consume() throws InterruptedException {
        int value = queue.take();  // Блокируется, если очередь пустая
        System.out.println("Consumed: " + value);
        return value;
    }
}

// Использование
BlockingQueueExample pc = new BlockingQueueExample();
ExecutorService executor = Executors.newFixedThreadPool(2);

// Producer
executor.submit(() -> {
    for (int i = 0; i < 20; i++) {
        try {
            pc.produce(i);
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
});

// Consumer
executor.submit(() -> {
    for (int i = 0; i < 20; i++) {
        try {
            pc.consume();
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
});

executor.shutdown();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `wait()/notify()` — низкоуровневый механизм, легко ошибиться
- `BlockingQueue` — высокоуровневая абстракция, thread-safe
- Понимание blocking operations

### ЗАДАЧА #3 | Уровень: Senior
**УСЛОВИЕ:** Реализовать Read-Write Lock для кэша: множественное чтение, эксклюзивная запись.

**РЕШЕНИЕ:**
```java
class Cache<K, V> {
    private final Map<K, V> map = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();
    
    public V get(K key) {
        readLock.lock();
        try {
            return map.get(key);
        } finally {
            readLock.unlock();
        }
    }
    
    public void put(K key, V value) {
        writeLock.lock();
        try {
            map.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }
    
    public V computeIfAbsent(K key, Function<K, V> mappingFunction) {
        // Сначала пробуем read lock (оптимистичный сценарий)
        readLock.lock();
        try {
            V value = map.get(key);
            if (value != null) {
                return value;
            }
        } finally {
            readLock.unlock();
        }
        
        // Если не нашли — берём write lock
        writeLock.lock();
        try {
            // Double-check: другой поток мог добавить значение
            V value = map.get(key);
            if (value == null) {
                value = mappingFunction.apply(key);
                map.put(key, value);
            }
            return value;
        } finally {
            writeLock.unlock();
        }
    }
    
    public int size() {
        readLock.lock();
        try {
            return map.size();
        } finally {
            readLock.unlock();
        }
    }
}

// Тест
Cache<String, String> cache = new Cache<>();
ExecutorService executor = Executors.newFixedThreadPool(10);

// 8 readers
for (int i = 0; i < 8; i++) {
    final int id = i;
    executor.submit(() -> {
        for (int j = 0; j < 100; j++) {
            String value = cache.get("key" + (j % 10));
            System.out.println("Reader " + id + " read: " + value);
        }
    });
}

// 2 writers
for (int i = 0; i < 2; i++) {
    final int id = i;
    executor.submit(() -> {
        for (int j = 0; j < 50; j++) {
            cache.put("key" + j, "value" + j);
            System.out.println("Writer " + id + " wrote: key" + j);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    });
}

executor.shutdown();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `ReadWriteLock` позволяет concurrent чтение
- Upgrade lock pattern: read → unlock → write lock
- Double-checked locking для computeIfAbsent
- Read-heavy workloads benefit from ReadWriteLock

## CompletableFuture

### ЗАДАЧА #4 | Уровень: Middle
**УСЛОВИЕ:** Загрузить данные пользователя и его заказы параллельно, затем объединить.

**РЕШЕНИЕ:**
```java
record User(String id, String name) {}
record Order(String id, String userId, double amount) {}
record UserWithOrders(User user, List<Order> orders) {}

class UserService {
    public CompletableFuture<User> getUserAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> {
            // Имитация HTTP запроса
            sleep(500);
            return new User(userId, "User " + userId);
        });
    }
    
    public CompletableFuture<List<Order>> getOrdersAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> {
            // Имитация HTTP запроса
            sleep(700);
            return List.of(
                new Order("O1", userId, 100),
                new Order("O2", userId, 200)
            );
        });
    }
    
    public CompletableFuture<UserWithOrders> getUserWithOrders(String userId) {
        CompletableFuture<User> userFuture = getUserAsync(userId);
        CompletableFuture<List<Order>> ordersFuture = getOrdersAsync(userId);
        
        // Ожидаем оба future
        return userFuture.thenCombine(ordersFuture,
            (user, orders) -> new UserWithOrders(user, orders)
        );
    }
    
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// Использование
UserService service = new UserService();

long start = System.currentTimeMillis();

CompletableFuture<UserWithOrders> future = service.getUserWithOrders("U123");

future.thenAccept(result -> {
    long duration = System.currentTimeMillis() - start;
    System.out.println("User: " + result.user().name());
    System.out.println("Orders: " + result.orders().size());
    System.out.println("Duration: " + duration + "ms");  // ~700ms (параллельно)
}).join();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `thenCombine()` для параллельного выполнения и объединения результатов
- Время выполнения = max(getUserAsync, getOrdersAsync), а не сумма
- `supplyAsync()` для асинхронных операций

### ЗАДАЧА #5 | Уровень: Senior
**УСЛОВИЕ:** Загрузить список пользователей, затем для каждого загрузить заказы (параллельно). Обработать ошибки.

**РЕШЕНИЕ:**
```java
class OrderService {
    public CompletableFuture<List<UserWithOrders>> getUsersWithOrders(List<String> userIds) {
        // Шаг 1: загружаем всех пользователей параллельно
        List<CompletableFuture<User>> userFutures = userIds.stream()
            .map(this::getUserAsync)
            .toList();
        
        // Ждём всех
        CompletableFuture<Void> allUsers = CompletableFuture.allOf(
            userFutures.toArray(new CompletableFuture[0])
        );
        
        // Шаг 2: когда все пользователи загружены, загружаем заказы
        return allUsers.thenCompose(v -> {
            List<CompletableFuture<UserWithOrders>> combinedFutures = userFutures.stream()
                .map(userFuture -> userFuture.thenCompose(user ->
                    getOrdersAsync(user.id())
                        .thenApply(orders -> new UserWithOrders(user, orders))
                        .exceptionally(ex -> {
                            // Обрабатываем ошибку загрузки заказов
                            System.err.println("Failed to load orders for " + user.id() + ": " + ex.getMessage());
                            return new UserWithOrders(user, List.of());
                        })
                ))
                .toList();
            
            // Ждём всех
            return CompletableFuture.allOf(
                combinedFutures.toArray(new CompletableFuture[0])
            ).thenApply(vv ->
                combinedFutures.stream()
                    .map(CompletableFuture::join)
                    .toList()
            );
        });
    }
    
    // С timeout
    public CompletableFuture<User> getUserAsyncWithTimeout(String userId) {
        return getUserAsync(userId)
            .orTimeout(2, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                System.err.println("Timeout loading user " + userId);
                return new User(userId, "Unknown");
            });
    }
    
    // Fallback
    public CompletableFuture<User> getUserWithFallback(String userId) {
        return getUserAsync(userId)
            .exceptionallyCompose(ex -> {
                System.err.println("Primary failed, trying backup: " + ex.getMessage());
                return getBackupUserAsync(userId);
            });
    }
    
    private CompletableFuture<User> getUserAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> new User(userId, "User " + userId));
    }
    
    private CompletableFuture<List<Order>> getOrdersAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> List.of(new Order("O1", userId, 100)));
    }
    
    private CompletableFuture<User> getBackupUserAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> new User(userId, "Backup User"));
    }
}

// Использование
OrderService service = new OrderService();
List<String> userIds = List.of("U1", "U2", "U3", "U4", "U5");

service.getUsersWithOrders(userIds)
    .thenAccept(results -> {
        results.forEach(result ->
            System.out.printf("User %s has %d orders%n",
                result.user().name(), result.orders().size())
        );
    })
    .exceptionally(ex -> {
        System.err.println("Failed to load data: " + ex.getMessage());
        return null;
    })
    .join();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `allOf()` для ожидания множества futures
- `thenCompose()` для цепочки асинхронных операций
- `exceptionally()` для обработки ошибок
- `orTimeout()` для таймаутов
- `exceptionallyCompose()` для fallback логики

### ЗАДАЧА #6 | Уровень: Senior
**УСЛОВИЕ:** Реализовать retry логику для HTTP запросов с экспоненциальным backoff.

**РЕШЕНИЕ:**
```java
class RetryableHttpClient {
    private final int maxRetries;
    private final long initialDelayMs;
    
    public RetryableHttpClient(int maxRetries, long initialDelayMs) {
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
    }
    
    public CompletableFuture<String> fetchWithRetry(String url) {
        return fetchWithRetry(url, 0);
    }
    
    private CompletableFuture<String> fetchWithRetry(String url, int attempt) {
        return fetch(url)
            .exceptionallyCompose(ex -> {
                if (attempt >= maxRetries) {
                    System.err.println("Max retries exceeded for " + url);
                    return CompletableFuture.failedFuture(ex);
                }
                
                long delay = initialDelayMs * (long) Math.pow(2, attempt);
                System.out.printf("Retry #%d for %s after %dms%n", attempt + 1, url, delay);
                
                return CompletableFuture
                    .delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> {})
                    .thenCompose(v -> fetchWithRetry(url, attempt + 1));
            });
    }
    
    private CompletableFuture<String> fetch(String url) {
        return CompletableFuture.supplyAsync(() -> {
            // Имитация HTTP запроса
            if (Math.random() < 0.7) {  // 70% шанс ошибки
                throw new RuntimeException("HTTP 500");
            }
            return "Response from " + url;
        });
    }
}

// Использование
RetryableHttpClient client = new RetryableHttpClient(3, 100);

client.fetchWithRetry("https://api.example.com/users")
    .thenAccept(response -> System.out.println("Success: " + response))
    .exceptionally(ex -> {
        System.err.println("Failed after retries: " + ex.getMessage());
        return null;
    })
    .join();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Retry pattern критичен для внешних API
- Экспоненциальный backoff избегает перегрузки
- `exceptionallyCompose()` для рекурсивного retry
- `delayedExecutor()` для задержки

## Deadlock и проблемы синхронизации

### ЗАДАЧА #7 | Уровень: Senior
**УСЛОВИЕ:** В коде возможен deadlock. Найти проблему и исправить.

**РЕШЕНИЕ:**
```java
// ❌ ПРОБЛЕМА: deadlock
class BankAccount {
    private double balance;
    
    public BankAccount(double balance) {
        this.balance = balance;
    }
    
    public synchronized void transfer(BankAccount to, double amount) {
        this.balance -= amount;
        // Между этими строками может произойти context switch
        to.deposit(amount);  // Пытаемся взять lock на `to`
    }
    
    public synchronized void deposit(double amount) {
        this.balance += amount;
    }
    
    public synchronized double getBalance() {
        return balance;
    }
}

// Thread 1: account1.transfer(account2, 100)  // Блокирует account1, ждёт account2
// Thread 2: account2.transfer(account1, 50)   // Блокирует account2, ждёт account1
// → DEADLOCK

// ✅ РЕШЕНИЕ 1: блокировать в одном порядке
class SafeBankAccount {
    private final long id;
    private double balance;
    
    public SafeBankAccount(long id, double balance) {
        this.id = id;
        this.balance = balance;
    }
    
    public void transfer(SafeBankAccount to, double amount) {
        // Всегда блокируем в порядке возрастания id
        SafeBankAccount first = this.id < to.id ? this : to;
        SafeBankAccount second = this.id < to.id ? to : this;
        
        synchronized (first) {
            synchronized (second) {
                if (this.balance < amount) {
                    throw new IllegalStateException("Insufficient funds");
                }
                this.balance -= amount;
                to.balance += amount;
            }
        }
    }
    
    public synchronized double getBalance() {
        return balance;
    }
}

// ✅ РЕШЕНИЕ 2: глобальный lock
class GlobalLockBankAccount {
    private static final Object GLOBAL_LOCK = new Object();
    private double balance;
    
    public void transfer(GlobalLockBankAccount to, double amount) {
        synchronized (GLOBAL_LOCK) {
            if (this.balance < amount) {
                throw new IllegalStateException("Insufficient funds");
            }
            this.balance -= amount;
            to.balance += amount;
        }
    }
}

// ✅ РЕШЕНИЕ 3: tryLock с timeout
class TryLockBankAccount {
    private final ReentrantLock lock = new ReentrantLock();
    private double balance;
    
    public boolean transfer(TryLockBankAccount to, double amount) throws InterruptedException {
        while (true) {
            if (this.lock.tryLock(50, TimeUnit.MILLISECONDS)) {
                try {
                    if (to.lock.tryLock(50, TimeUnit.MILLISECONDS)) {
                        try {
                            if (this.balance < amount) {
                                return false;
                            }
                            this.balance -= amount;
                            to.balance += amount;
                            return true;
                        } finally {
                            to.lock.unlock();
                        }
                    }
                } finally {
                    this.lock.unlock();
                }
            }
            // Если не получили оба lock'а — retry
            Thread.sleep(10);
        }
    }
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Deadlock — классическая проблема многопоточности
- Решение: блокировка в одном порядке (по id, hash code и т.д.)
- `tryLock()` с timeout — альтернатива
- Глобальный lock — простой, но не масштабируется

### ЗАДАЧА #8 | Уровень: Middle
**УСЛОВИЕ:** Реализовать thread-safe Singleton (double-checked locking).

**РЕШЕНИЕ:**
```java
// ❌ ПЛОХО: не thread-safe
class NaiveSingleton {
    private static NaiveSingleton instance;
    
    public static NaiveSingleton getInstance() {
        if (instance == null) {
            instance = new NaiveSingleton();  // Race condition!
        }
        return instance;
    }
}

// ❌ ПЛОХО: медленно (synchronized на каждый вызов)
class SynchronizedSingleton {
    private static SynchronizedSingleton instance;
    
    public static synchronized SynchronizedSingleton getInstance() {
        if (instance == null) {
            instance = new SynchronizedSingleton();
        }
        return instance;
    }
}

// ✅ ХОРОШО: double-checked locking
class DoubleCheckedSingleton {
    private static volatile DoubleCheckedSingleton instance;  // volatile обязателен!
    
    public static DoubleCheckedSingleton getInstance() {
        if (instance == null) {  // Первая проверка без lock (быстро)
            synchronized (DoubleCheckedSingleton.class) {
                if (instance == null) {  // Вторая проверка с lock
                    instance = new DoubleCheckedSingleton();
                }
            }
        }
        return instance;
    }
}

// ✅ ЕЩЁ ЛУЧШЕ: Initialization-on-demand holder
class HolderSingleton {
    private HolderSingleton() {}
    
    private static class Holder {
        private static final HolderSingleton INSTANCE = new HolderSingleton();
    }
    
    public static HolderSingleton getInstance() {
        return Holder.INSTANCE;  // Thread-safe благодаря classloader
    }
}

// ✅ ОПТИМАЛЬНО: enum (защита от рефлексии и сериализации)
enum EnumSingleton {
    INSTANCE;
    
    public void doSomething() {
        System.out.println("Singleton method");
    }
}

// Тест
ExecutorService executor = Executors.newFixedThreadPool(100);
Set<DoubleCheckedSingleton> instances = ConcurrentHashMap.newKeySet();

for (int i = 0; i < 1000; i++) {
    executor.submit(() ->
        instances.add(DoubleCheckedSingleton.getInstance())
    );
}

executor.shutdown();
executor.awaitTermination(1, TimeUnit.MINUTES);

System.out.println("Unique instances: " + instances.size());  // Должно быть 1
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `volatile` критично для double-checked locking (memory visibility)
- Initialization-on-demand holder — рекомендуемый подход
- Enum singleton — защита от рефлексии
- Понимание happens-before relationship

## Executor Service

### ЗАДАЧА #9 | Уровень: Middle
**УСЛОВИЕ:** Обработать список задач с ограничением: не более 5 задач параллельно, timeout 10 секунд на задачу.

**РЕШЕНИЕ:**
```java
class TaskProcessor {
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    
    public List<String> processTasks(List<String> tasks) throws InterruptedException {
        List<Future<String>> futures = tasks.stream()
            .map(task -> executor.submit(() -> processTask(task)))
            .toList();
        
        List<String> results = new ArrayList<>();
        
        for (Future<String> future : futures) {
            try {
                String result = future.get(10, TimeUnit.SECONDS);
                results.add(result);
            } catch (TimeoutException e) {
                System.err.println("Task timed out");
                future.cancel(true);  // Прерываем задачу
                results.add("TIMEOUT");
            } catch (ExecutionException e) {
                System.err.println("Task failed: " + e.getCause().getMessage());
                results.add("ERROR");
            }
        }
        
        return results;
    }
    
    private String processTask(String task) {
        // Имитация работы
        try {
            Thread.sleep((long) (Math.random() * 5000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
        return "Processed: " + task;
    }
    
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

// Использование с ExecutorCompletionService (лучше для большого количества задач)
class CompletionServiceProcessor {
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    
    public List<String> processTasks(List<String> tasks) throws InterruptedException {
        CompletionService<String> completionService = new ExecutorCompletionService<>(executor);
        
        // Submit all tasks
        tasks.forEach(task ->
            completionService.submit(() -> processTask(task))
        );
        
        // Collect results as they complete
        List<String> results = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            try {
                Future<String> future = completionService.poll(10, TimeUnit.SECONDS);
                if (future != null) {
                    results.add(future.get());
                } else {
                    results.add("TIMEOUT");
                }
            } catch (ExecutionException e) {
                results.add("ERROR: " + e.getCause().getMessage());
            }
        }
        
        return results;
    }
    
    private String processTask(String task) throws InterruptedException {
        Thread.sleep(1000);
        return "Processed: " + task;
    }
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `ExecutorService` для управления пулом потоков
- `Future.get(timeout)` для ограничения времени выполнения
- `ExecutorCompletionService` обрабатывает результаты по мере готовности
- Правильный shutdown: `shutdown()` → `awaitTermination()` → `shutdownNow()`

### ЗАДАЧА #10 | Уровень: Senior
**УСЛОВИЕ:** Реализовать rate limiter: не более N запросов в секунду.

**РЕШЕНИЕ:**
```java
// Вариант 1: Semaphore (простой)
class SemaphoreRateLimiter {
    private final Semaphore semaphore;
    private final int maxPermits;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    public SemaphoreRateLimiter(int maxPermitsPerSecond) {
        this.maxPermits = maxPermitsPerSecond;
        this.semaphore = new Semaphore(maxPermits);
        
        // Восстанавливаем permits каждую секунду
        scheduler.scheduleAtFixedRate(() -> {
            int released = maxPermits - semaphore.availablePermits();
            semaphore.release(released);
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }
    
    public void shutdown() {
        scheduler.shutdown();
    }
}

// Вариант 2: Token Bucket (более точный)
class TokenBucketRateLimiter {
    private final long capacity;
    private final long refillRate;  // tokens per second
    private long tokens;
    private long lastRefillTime;
    private final Lock lock = new ReentrantLock();
    
    public TokenBucketRateLimiter(long capacity, long refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }
    
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        long tokensToAdd = (elapsedNanos * refillRate) / 1_000_000_000L;
        
        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}

// Вариант 3: Guava RateLimiter (production-ready)
import com.google.common.util.concurrent.RateLimiter;

class GuavaRateLimiterExample {
    private final RateLimiter rateLimiter = RateLimiter.create(10.0);  // 10 permits/sec
    
    public void makeRequest() {
        rateLimiter.acquire();  // Блокируется, пока не получит permit
        // Выполняем запрос
    }
    
    public boolean tryMakeRequest() {
        if (rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            // Выполняем запрос
            return true;
        }
        return false;  // Rate limit exceeded
    }
}

// Тест
TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10);

for (int i = 0; i < 20; i++) {
    if (limiter.tryAcquire()) {
        System.out.println("Request " + i + " accepted");
    } else {
        System.out.println("Request " + i + " rejected (rate limit)");
    }
    Thread.sleep(50);
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Rate limiting критичен для защиты API
- Token Bucket алгоритм — индустриальный стандарт
- Guava RateLimiter — production-ready решение
- Понимание trade-offs: простота vs точность

## Продвинутые задачи

### ЗАДАЧА #11 | Уровень: Senior
**УСЛОВИЕ:** Реализовать thread-safe LRU Cache с максимальным размером.

**РЕШЕНИЕ:**
```java
class LRUCache<K, V> {
    private final int maxSize;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;
        
        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
    
    public LRUCache(int maxSize) {
        this.maxSize = maxSize;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }
    
    public V get(K key) {
        lock.readLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            
            // Move to front (upgrade to write lock)
            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                removeNode(node);
                addToFront(node);
                lock.readLock().lock();
            } finally {
                lock.writeLock().unlock();
            }
            
            return node.value;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                removeNode(existing);
                addToFront(existing);
                return;
            }
            
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToFront(newNode);
            
            if (map.size() > maxSize) {
                Node<K, V> lru = tail.prev;
                removeNode(lru);
                map.remove(lru.key);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
    
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
    
    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}

// Альтернатива: LinkedHashMap (проще, но менее эффективно)
class SimpleLRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxSize;
    
    public SimpleLRUCache(int maxSize) {
        super(16, 0.75f, true);  // accessOrder = true
        this.maxSize = maxSize;
    }
    
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}

// Thread-safe wrapper
class SynchronizedLRUCache<K, V> {
    private final SimpleLRUCache<K, V> cache;
    
    public SynchronizedLRUCache(int maxSize) {
        this.cache = new SimpleLRUCache<>(maxSize);
    }
    
    public synchronized V get(K key) {
        return cache.get(key);
    }
    
    public synchronized void put(K key, V value) {
        cache.put(key, value);
    }
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- LRU Cache — частая задача на собеседованиях
- Doubly linked list + HashMap для O(1) операций
- ReadWriteLock для concurrent read
- LinkedHashMap — более простая альтернатива

### ЗАДАЧА #12 | Уровень: Senior
**УСЛОВИЕ:** Реализовать CountDownLatch-like механизм с нуля.

**РЕШЕНИЕ:**
```java
class CustomCountDownLatch {
    private int count;
    private final Object lock = new Object();
    
    public CustomCountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count must be non-negative");
        }
        this.count = count;
    }
    
    public void await() throws InterruptedException {
        synchronized (lock) {
            while (count > 0) {
                lock.wait();
            }
        }
    }
    
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        synchronized (lock) {
            while (count > 0) {
                if (nanos <= 0) {
                    return false;
                }
                long start = System.nanoTime();
                TimeUnit.NANOSECONDS.timedWait(lock, nanos);
                long elapsed = System.nanoTime() - start;
                nanos -= elapsed;
            }
            return true;
        }
    }
    
    public void countDown() {
        synchronized (lock) {
            if (count > 0) {
                count--;
                if (count == 0) {
                    lock.notifyAll();
                }
            }
        }
    }
    
    public int getCount() {
        synchronized (lock) {
            return count;
        }
    }
}

// Использование
CustomCountDownLatch latch = new CustomCountDownLatch(3);

// 3 worker threads
for (int i = 0; i < 3; i++) {
    final int id = i;
    new Thread(() -> {
        System.out.println("Worker " + id + " starting");
        try {
            Thread.sleep((long) (Math.random() * 2000));
            System.out.println("Worker " + id + " finished");
            latch.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }).start();
}

// Main thread waits
System.out.println("Main thread waiting...");
latch.await();
System.out.println("All workers finished!");
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Понимание wait/notify механизма
- Правильная обработка spurious wakeups (while вместо if)
- Timeout с учётом elapsed time
- CountDownLatch — часто используется в реальных приложениях

---

📊 **Модель**: Claude Sonnet 4.5 | **Задач**: 12 | **Стоимость**: ~$1.00

*Версия: 1.0 | Январь 2026*


