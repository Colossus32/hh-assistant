# Java Streams — задачи для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Базовые операции

### ЗАДАЧА #1 | Уровень: Middle
**УСЛОВИЕ:** Дан `List<String>` слов. Найти количество слов длиной > 5 символов, начинающихся с заглавной буквы.

**РЕШЕНИЕ:**
```java
List<String> words = Arrays.asList("Hello", "world", "Java", "Stream", "API", "test");

long count = words.stream()
    .filter(word -> word.length() > 5)
    .filter(word -> Character.isUpperCase(word.charAt(0)))
    .count();

System.out.println(count);  // 2 (Stream, Hello)

// Альтернатива: через один filter
long count2 = words.stream()
    .filter(word -> word.length() > 5 && Character.isUpperCase(word.charAt(0)))
    .count();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Базовые операции: `filter()`, `count()`
- Цепочки фильтров vs комбинированное условие
- Ленивые вычисления: фильтры выполняются только при терминальной операции

### ЗАДАЧА #2 | Уровень: Middle
**УСЛОВИЕ:** `List<Integer>` чисел. Найти сумму квадратов чётных чисел.

**РЕШЕНИЕ:**
```java
List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

int sum = numbers.stream()
    .filter(n -> n % 2 == 0)
    .map(n -> n * n)
    .reduce(0, Integer::sum);

System.out.println(sum);  // 220 (4 + 16 + 36 + 64 + 100)

// Альтернатива: mapToInt для эффективности
int sum2 = numbers.stream()
    .filter(n -> n % 2 == 0)
    .mapToInt(n -> n * n)
    .sum();

// Или через IntStream
int sum3 = numbers.stream()
    .mapToInt(Integer::intValue)
    .filter(n -> n % 2 == 0)
    .map(n -> n * n)
    .sum();
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `map()` для трансформации элементов
- `reduce()` vs специализированные методы (`sum()`)
- `mapToInt()` эффективнее для примитивов (избегает boxing/unboxing)

### ЗАДАЧА #3 | Уровень: Middle
**УСЛОВИЕ:** `List<Person>` (name, age). Получить список имён людей старше 18 лет, отсортированных по алфавиту.

**РЕШЕНИЕ:**
```java
record Person(String name, int age) {}

List<Person> people = Arrays.asList(
    new Person("Alice", 25),
    new Person("Bob", 17),
    new Person("Charlie", 30),
    new Person("David", 15)
);

List<String> names = people.stream()
    .filter(p -> p.age() > 18)
    .map(Person::name)
    .sorted()
    .collect(Collectors.toList());

System.out.println(names);  // [Alice, Charlie]

// Альтернатива: sorted() с компаратором на Person
List<String> names2 = people.stream()
    .filter(p -> p.age() > 18)
    .sorted(Comparator.comparing(Person::name))
    .map(Person::name)
    .collect(Collectors.toList());

// Или toList() (Java 16+)
List<String> names3 = people.stream()
    .filter(p -> p.age() > 18)
    .map(Person::name)
    .sorted()
    .toList();  // Immutable list
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `map()` для извлечения полей
- `sorted()` без параметра использует natural ordering
- `toList()` (Java 16+) возвращает immutable list
- Порядок операций влияет на производительность (sorted() раньше → сортируем меньше элементов)

## Группировка и агрегация

### ЗАДАЧА #4 | Уровень: Middle
**УСЛОВИЕ:** `List<Employee>` (name, department, salary). Сгруппировать по департаменту и найти среднюю зарплату в каждом.

**РЕШЕНИЕ:**
```java
record Employee(String name, String department, double salary) {}

List<Employee> employees = Arrays.asList(
    new Employee("Alice", "IT", 80000),
    new Employee("Bob", "HR", 60000),
    new Employee("Charlie", "IT", 90000),
    new Employee("David", "HR", 65000),
    new Employee("Eve", "IT", 85000)
);

Map<String, Double> avgSalaryByDept = employees.stream()
    .collect(Collectors.groupingBy(
        Employee::department,
        Collectors.averagingDouble(Employee::salary)
    ));

System.out.println(avgSalaryByDept);
// {IT=85000.0, HR=62500.0}

// Дополнительная аналитика: количество + средняя зарплата
Map<String, DoubleSummaryStatistics> stats = employees.stream()
    .collect(Collectors.groupingBy(
        Employee::department,
        Collectors.summarizingDouble(Employee::salary)
    ));

stats.forEach((dept, stat) -> {
    System.out.printf("%s: count=%d, avg=%.2f, max=%.2f%n",
        dept, stat.getCount(), stat.getAverage(), stat.getMax());
});
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `groupingBy()` для группировки
- Downstream collectors: `averagingDouble()`, `summingDouble()`, `counting()`
- `summarizingDouble()` возвращает статистику (count, sum, min, max, avg)

### ЗАДАЧА #5 | Уровень: Senior
**УСЛОВИЕ:** `List<Order>` (customerId, amount). Найти топ-3 клиентов по сумме заказов.

**РЕШЕНИЕ:**
```java
record Order(String customerId, double amount) {}

List<Order> orders = Arrays.asList(
    new Order("C1", 100),
    new Order("C2", 200),
    new Order("C1", 150),
    new Order("C3", 300),
    new Order("C2", 250),
    new Order("C1", 50)
);

List<Map.Entry<String, Double>> top3 = orders.stream()
    .collect(Collectors.groupingBy(
        Order::customerId,
        Collectors.summingDouble(Order::amount)
    ))
    .entrySet().stream()
    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
    .limit(3)
    .collect(Collectors.toList());

top3.forEach(entry ->
    System.out.printf("Customer %s: %.2f%n", entry.getKey(), entry.getValue())
);
// C1: 300.00
// C3: 300.00
// C2: 450.00

// Альтернатива: через toMap с BinaryOperator
Map<String, Double> totalByCustomer = orders.stream()
    .collect(Collectors.toMap(
        Order::customerId,
        Order::amount,
        Double::sum  // Merge function для дубликатов
    ));

List<String> top3Customers = totalByCustomer.entrySet().stream()
    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
    .limit(3)
    .map(Map.Entry::getKey)
    .collect(Collectors.toList());
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Двухэтапная обработка: группировка → сортировка
- `comparingByValue()` для сортировки Map.Entry
- `toMap()` с merge function для агрегации

### ЗАДАЧА #6 | Уровень: Senior
**УСЛОВИЕ:** `List<Transaction>` (date, category, amount). Сгруппировать по году и категории, найти сумму для каждой комбинации.

**РЕШЕНИЕ:**
```java
record Transaction(LocalDate date, String category, double amount) {}

List<Transaction> transactions = Arrays.asList(
    new Transaction(LocalDate.of(2024, 1, 15), "Food", 50),
    new Transaction(LocalDate.of(2024, 2, 10), "Travel", 200),
    new Transaction(LocalDate.of(2025, 1, 20), "Food", 60),
    new Transaction(LocalDate.of(2024, 3, 5), "Food", 40),
    new Transaction(LocalDate.of(2025, 2, 12), "Travel", 300)
);

// Группировка по году и категории
Map<Integer, Map<String, Double>> result = transactions.stream()
    .collect(Collectors.groupingBy(
        t -> t.date().getYear(),
        Collectors.groupingBy(
            Transaction::category,
            Collectors.summingDouble(Transaction::amount)
        )
    ));

result.forEach((year, categoryMap) -> {
    System.out.println("Year: " + year);
    categoryMap.forEach((category, sum) ->
        System.out.printf("  %s: %.2f%n", category, sum)
    );
});
// Year: 2024
//   Food: 90.00
//   Travel: 200.00
// Year: 2025
//   Food: 60.00
//   Travel: 300.00

// Альтернатива: flat structure (Year + Category как ключ)
record YearCategory(int year, String category) {}

Map<YearCategory, Double> flatResult = transactions.stream()
    .collect(Collectors.groupingBy(
        t -> new YearCategory(t.date().getYear(), t.category()),
        Collectors.summingDouble(Transaction::amount)
    ));
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Вложенная группировка: `groupingBy(...)` внутри `groupingBy(...)`
- Альтернативный подход: composite key
- Выбор структуры данных зависит от дальнейшего использования

## FlatMap и сложные трансформации

### ЗАДАЧА #7 | Уровень: Middle
**УСЛОВИЕ:** `List<List<Integer>>` (список списков). Найти все уникальные чётные числа.

**РЕШЕНИЕ:**
```java
List<List<Integer>> lists = Arrays.asList(
    Arrays.asList(1, 2, 3),
    Arrays.asList(2, 4, 5),
    Arrays.asList(4, 6, 7)
);

List<Integer> uniqueEvens = lists.stream()
    .flatMap(List::stream)  // Stream<List<Integer>> → Stream<Integer>
    .filter(n -> n % 2 == 0)
    .distinct()
    .sorted()
    .collect(Collectors.toList());

System.out.println(uniqueEvens);  // [2, 4, 6]

// Альтернатива: через Set для автоматической уникальности
Set<Integer> uniqueEvensSet = lists.stream()
    .flatMap(List::stream)
    .filter(n -> n % 2 == 0)
    .collect(Collectors.toSet());
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `flatMap()` для "раскрытия" вложенных структур
- `distinct()` для уникальности (использует equals/hashCode)
- Set vs List + distinct()

### ЗАДАЧА #8 | Уровень: Senior
**УСЛОВИЕ:** `List<Person>` (name, List<String> phoneNumbers). Получить все уникальные номера телефонов.

**РЕШЕНИЕ:**
```java
record Person(String name, List<String> phoneNumbers) {}

List<Person> people = Arrays.asList(
    new Person("Alice", Arrays.asList("+1234", "+5678")),
    new Person("Bob", Arrays.asList("+5678", "+9012")),
    new Person("Charlie", Arrays.asList("+3456"))
);

Set<String> allPhones = people.stream()
    .flatMap(p -> p.phoneNumbers().stream())
    .collect(Collectors.toSet());

System.out.println(allPhones);  // [+1234, +5678, +9012, +3456]

// Дополнительно: Map<Person, String> (первый телефон каждого человека)
Map<Person, String> firstPhones = people.stream()
    .filter(p -> !p.phoneNumbers().isEmpty())
    .collect(Collectors.toMap(
        Function.identity(),
        p -> p.phoneNumbers().get(0)
    ));

// Map<String, List<Person>> (по номеру телефона → список людей)
Map<String, List<Person>> phoneToPersons = people.stream()
    .flatMap(person ->
        person.phoneNumbers().stream()
            .map(phone -> Map.entry(phone, person))
    )
    .collect(Collectors.groupingBy(
        Map.Entry::getKey,
        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
    ));
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `flatMap()` для work with nested collections
- `Map.entry()` для создания промежуточных пар
- `Collectors.mapping()` для трансформации в downstream collector

### ЗАДАЧА #9 | Уровень: Senior
**УСЛОВИЕ:** `List<String>` предложений. Найти частоту каждого слова (case-insensitive).

**РЕШЕНИЕ:**
```java
List<String> sentences = Arrays.asList(
    "Hello world",
    "Hello Java",
    "Java is great"
);

Map<String, Long> wordFrequency = sentences.stream()
    .flatMap(sentence -> Arrays.stream(sentence.split("\\s+")))
    .map(String::toLowerCase)
    .collect(Collectors.groupingBy(
        Function.identity(),
        Collectors.counting()
    ));

System.out.println(wordFrequency);
// {hello=2, world=1, java=2, is=1, great=1}

// Топ-3 самых частых слов
List<Map.Entry<String, Long>> top3Words = wordFrequency.entrySet().stream()
    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
    .limit(3)
    .collect(Collectors.toList());

top3Words.forEach(entry ->
    System.out.printf("%s: %d%n", entry.getKey(), entry.getValue())
);
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `flatMap()` + `split()` для разбиения на слова
- `Collectors.counting()` для подсчёта частоты
- `Function.identity()` как ключ группировки

## Продвинутые collectors

### ЗАДАЧА #10 | Уровень: Senior
**УСЛОВИЕ:** `List<Student>` (name, grade, passed). Разделить на два списка: passed и failed.

**РЕШЕНИЕ:**
```java
record Student(String name, int grade, boolean passed) {}

List<Student> students = Arrays.asList(
    new Student("Alice", 85, true),
    new Student("Bob", 55, false),
    new Student("Charlie", 90, true),
    new Student("David", 40, false)
);

Map<Boolean, List<Student>> partitioned = students.stream()
    .collect(Collectors.partitioningBy(Student::passed));

List<Student> passedStudents = partitioned.get(true);
List<Student> failedStudents = partitioned.get(false);

System.out.println("Passed: " + passedStudents.size());
System.out.println("Failed: " + failedStudents.size());

// Дополнительно: средний балл в каждой группе
Map<Boolean, Double> avgGradeByStatus = students.stream()
    .collect(Collectors.partitioningBy(
        Student::passed,
        Collectors.averagingInt(Student::grade)
    ));

System.out.println("Passed avg: " + avgGradeByStatus.get(true));
System.out.println("Failed avg: " + avgGradeByStatus.get(false));
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `partitioningBy()` для binary split (всегда 2 группы)
- Ключ — всегда Boolean (true/false)
- Downstream collector для дополнительной агрегации

### ЗАДАЧА #11 | Уровень: Senior
**УСЛОВИЕ:** `List<String>` имён. Собрать в строку через запятую, но не более 3 первых.

**РЕШЕНИЕ:**
```java
List<String> names = Arrays.asList("Alice", "Bob", "Charlie", "David", "Eve");

String result = names.stream()
    .limit(3)
    .collect(Collectors.joining(", "));

System.out.println(result);  // Alice, Bob, Charlie

// С префиксом и суффиксом
String result2 = names.stream()
    .limit(3)
    .collect(Collectors.joining(", ", "Names: [", "]"));

System.out.println(result2);  // Names: [Alice, Bob, Charlie]

// Если элементов больше → добавить "..."
String result3 = names.stream()
    .limit(3)
    .collect(Collectors.collectingAndThen(
        Collectors.joining(", "),
        s -> names.size() > 3 ? s + ", ..." : s
    ));

System.out.println(result3);  // Alice, Bob, Charlie, ...
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `Collectors.joining()` для конкатенации строк
- `collectingAndThen()` для постобработки результата
- Избегаем ручной итерации + StringBuilder

### ЗАДАЧА #12 | Уровень: Senior
**УСЛОВИЕ:** `List<Product>` (name, category, price). Получить Map<Category, Product> с самым дорогим товаром в каждой категории.

**РЕШЕНИЕ:**
```java
record Product(String name, String category, double price) {}

List<Product> products = Arrays.asList(
    new Product("Laptop", "Electronics", 1200),
    new Product("Phone", "Electronics", 800),
    new Product("Shirt", "Clothing", 50),
    new Product("Jeans", "Clothing", 80),
    new Product("Headphones", "Electronics", 200)
);

Map<String, Product> mostExpensiveByCategory = products.stream()
    .collect(Collectors.groupingBy(
        Product::category,
        Collectors.collectingAndThen(
            Collectors.maxBy(Comparator.comparingDouble(Product::price)),
            Optional::get
        )
    ));

mostExpensiveByCategory.forEach((category, product) ->
    System.out.printf("%s: %s ($%.2f)%n", category, product.name(), product.price())
);
// Electronics: Laptop ($1200.00)
// Clothing: Jeans ($80.00)

// Альтернатива: toMap с BinaryOperator
Map<String, Product> mostExpensive2 = products.stream()
    .collect(Collectors.toMap(
        Product::category,
        Function.identity(),
        BinaryOperator.maxBy(Comparator.comparingDouble(Product::price))
    ));
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `collectingAndThen()` + `maxBy()` для поиска максимального элемента
- `Optional::get` безопасно, т.к. группы непустые
- `toMap()` с merge function — альтернативный подход

## Performance и параллелизм

### ЗАДАЧА #13 | Уровень: Senior
**УСЛОВИЕ:** `List<Integer>` (10M элементов). Найти сумму квадратов чисел > 5000. Оптимизировать.

**РЕШЕНИЕ:**
```java
List<Integer> numbers = IntStream.rangeClosed(1, 10_000_000)
    .boxed()
    .collect(Collectors.toList());

// ❌ ПЛОХО: boxing/unboxing + sequential
long sum1 = numbers.stream()
    .filter(n -> n > 5_000_000)
    .map(n -> n * n)
    .mapToLong(Long::valueOf)
    .sum();

// ✅ ХОРОШО: IntStream (избегаем boxing)
long sum2 = numbers.stream()
    .mapToInt(Integer::intValue)
    .filter(n -> n > 5_000_000)
    .mapToLong(n -> (long) n * n)
    .sum();

// ✅ ЕЩЁ ЛУЧШЕ: параллельный stream
long sum3 = numbers.parallelStream()
    .mapToInt(Integer::intValue)
    .filter(n -> n > 5_000_000)
    .mapToLong(n -> (long) n * n)
    .sum();

// ✅ ОПТИМАЛЬНО: прямой IntStream (без коллекции)
long sum4 = IntStream.rangeClosed(5_000_001, 10_000_000)
    .parallel()
    .mapToLong(n -> (long) n * n)
    .sum();

// Измерение производительности
long start = System.nanoTime();
long result = sum4;
long duration = (System.nanoTime() - start) / 1_000_000;
System.out.printf("Result: %d, Time: %d ms%n", result, duration);
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Примитивные streams (IntStream, LongStream) эффективнее для числовых операций
- `parallelStream()` для CPU-intensive задач
- Избегаем boxing/unboxing
- Прямой IntStream.range() эффективнее List

### ЗАДАЧА #14 | Уровень: Senior
**УСЛОВИЕ:** Когда НЕ стоит использовать parallel streams?

**РЕШЕНИЕ:**
```java
// ❌ ПЛОХО: маленькая коллекция
List<Integer> small = Arrays.asList(1, 2, 3, 4, 5);
int sum = small.parallelStream().mapToInt(Integer::intValue).sum();
// Overhead от параллелизма > выигрыш

// ❌ ПЛОХО: операции с shared mutable state
List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
List<Integer> results = new ArrayList<>();  // NOT thread-safe

numbers.parallelStream()
    .map(n -> n * 2)
    .forEach(results::add);  // Race condition!

// ✅ ХОРОШО: используем thread-safe collector
List<Integer> results2 = numbers.parallelStream()
    .map(n -> n * 2)
    .collect(Collectors.toList());  // Thread-safe

// ❌ ПЛОХО: операции с side-effects
List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
names.parallelStream()
    .forEach(name -> System.out.println(name));  // Порядок не гарантирован

// ✅ ХОРОШО: используем forEachOrdered (но теряем параллелизм)
names.parallelStream()
    .forEachOrdered(System.out::println);

// ❌ ПЛОХО: I/O bound операции
List<String> urls = Arrays.asList("url1", "url2", "url3");
urls.parallelStream()
    .map(url -> httpClient.get(url))  // Блокирующий I/O
    .collect(Collectors.toList());
// Лучше использовать async I/O или thread pool с большим количеством потоков

// Когда использовать parallel:
// 1. Большая коллекция (>10K элементов)
// 2. CPU-intensive операции (вычисления, криптография)
// 3. Stateless операции
// 4. Хорошо разбиваемые структуры (ArrayList > LinkedList)
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Parallel streams не всегда быстрее
- Shared mutable state → race conditions
- I/O bound задачи не подходят для parallel streams
- Overhead от параллелизма на малых данных

## Сложные кейсы

### ЗАДАЧА #15 | Уровень: Senior
**УСЛОВИЕ:** `List<Event>` (userId, eventType, timestamp). Для каждого пользователя найти время между первым и последним событием.

**РЕШЕНИЕ:**
```java
record Event(String userId, String eventType, LocalDateTime timestamp) {}

List<Event> events = Arrays.asList(
    new Event("U1", "login", LocalDateTime.of(2025, 1, 1, 10, 0)),
    new Event("U1", "view", LocalDateTime.of(2025, 1, 1, 10, 5)),
    new Event("U2", "login", LocalDateTime.of(2025, 1, 1, 11, 0)),
    new Event("U1", "logout", LocalDateTime.of(2025, 1, 1, 10, 30)),
    new Event("U2", "logout", LocalDateTime.of(2025, 1, 1, 11, 45))
);

Map<String, Long> sessionDurations = events.stream()
    .collect(Collectors.groupingBy(
        Event::userId,
        Collectors.collectingAndThen(
            Collectors.toList(),
            eventList -> {
                LocalDateTime first = eventList.stream()
                    .map(Event::timestamp)
                    .min(LocalDateTime::compareTo)
                    .orElseThrow();
                LocalDateTime last = eventList.stream()
                    .map(Event::timestamp)
                    .max(LocalDateTime::compareTo)
                    .orElseThrow();
                return Duration.between(first, last).toMinutes();
            }
        )
    ));

sessionDurations.forEach((userId, duration) ->
    System.out.printf("User %s: %d minutes%n", userId, duration)
);
// User U1: 30 minutes
// User U2: 45 minutes

// Альтернатива: через teeing (Java 12+)
Map<String, Long> sessionDurations2 = events.stream()
    .collect(Collectors.groupingBy(
        Event::userId,
        Collectors.teeing(
            Collectors.minBy(Comparator.comparing(Event::timestamp)),
            Collectors.maxBy(Comparator.comparing(Event::timestamp)),
            (min, max) -> Duration.between(
                min.orElseThrow().timestamp(),
                max.orElseThrow().timestamp()
            ).toMinutes()
        )
    ));
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- `collectingAndThen()` для постобработки сгруппированных данных
- `teeing()` (Java 12+) для комбинирования двух collectors
- Работа с временными интервалами

---

📊 **Модель**: Claude Sonnet 4.5 | **Задач**: 15 | **Стоимость**: ~$0.85

*Версия: 1.0 | Январь 2026*





