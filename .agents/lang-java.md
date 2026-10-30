# Java 规范

**加载时机**：检测到 `pom.xml` / `build.gradle`，或要新增、修改 Java 代码。

## 1. 版本与语言特性

- **【默认】JDK 版本以 `pom.xml` 的 `java.version` / Gradle toolchain 为准**。不写"Java 8 以上"这种模糊表述，也不凭习惯用高版本语法。
- **【红线】不使用项目声明版本之外的语法**。JDK 8 项目不写 `var`、`record`、文本块、`switch` 模式匹配；JDK 17 项目不必手写 builder 去模拟 record。
- **【默认】用当前版本的现代写法，禁止 Java 6 风格**：
  - 手写 `for` + 累加 代替 `Stream`（简单遍历可以保留 for，别硬套流）
  - 匿名内部类 代替 lambda
  - 满屏 `if (x != null)` 代替 `Optional` / 早返回 / `Objects.requireNonNull`
  - `String.format` 拼 SQL、字符串 `+` 拼多行文本（用文本块）
  - `instanceof` + 强转 两行（用模式匹配）
- **【建议】`var` 只用于类型显而易见的局部变量**（`var list = new ArrayList<String>()` 可以，`var r = service.call()` 不行）。

## 2. 空值与 Optional

- **【红线】集合、数组、Map 的返回值禁止 `null`**，返回空集合（`List.of()` / `Collections.emptyList()`）。
- **【默认】`Optional` 只用于返回值**。禁止作为字段、方法参数、集合元素——它不实现 `Serializable`，也不是"可空标记"。
- **【红线】禁止 `optional.get()` 不判空**，用 `orElseThrow(...)` / `map` / `ifPresent`。
- **【红线】禁止 `orElse(null)`**，那是把 Optional 又变回 null。
- **【默认】入参校验用 `Objects.requireNonNull(x, "x 不能为空")`**，报错信息写清哪个参数。
- **【建议】可空返回值用注解表达**（`@Nullable` / `@NonNull`），比注释可靠。

## 3. 并发与虚拟线程

- **【红线】虚拟线程（JDK 21+）护共享状态时不用 `synchronized`**。JDK 24 之前 `synchronized` 会把载体线程 pin 住，吞吐直接塌。用 `ReentrantLock` + `try/finally` 解锁。
- **【红线】虚拟线程不做 CPU 密集计算**，也不在里面跑长时 JNI / 阻塞式文件 IO——那会占满载体线程，等于白用。
- **【默认】虚拟线程不要池化**。一个任务一个线程（`Thread.ofVirtual().start(...)`），池化会退化成平台线程池。
- **【默认】`ThreadLocal` 不存大对象**。虚拟线程数量可达百万级，ThreadLocal 会把内存放大到不可控；需要上下文传递时优先方法参数或 `ScopedValue`。
- **【红线】禁止裸用 `Executors.newFixedThreadPool` / `newCachedThreadPool`**：前者无界队列会 OOM，后者线程数无上限。用 `ThreadPoolExecutor` 显式指定队列容量、拒绝策略、线程名。
- **【默认】`CompletableFuture` 指定执行器**，不要用默认 `ForkJoinPool.commonPool` 跑阻塞任务；异常必须 `handle` / `exceptionally` 兜底，否则静默丢失。

## 4. 异常与错误

- **【红线】禁止空 catch**：`catch (Exception e) {}` 是最常见的故障放大器。
- **【红线】禁止 `e.printStackTrace()`**，用日志框架。
- **【默认】受检异常转业务异常**，保留 `cause` 链（`new BizException(code, msg, e)`），不要只打日志后返回 null。
- **【默认】用 try-with-resources 管资源**，不手写 `finally { close() }`。
- **【默认】不用异常做流程控制**（用异常判断"存不存在"属于反模式，先查再走）。
- **【建议】自定义异常带稳定 `code`**，对外 API 的 code 是契约，新增必须同步文档。

## 5. 日志

- **【默认】用 SLF4J 占位符**（`log.info("table={} rows={}", t, n)`），不用字符串拼接。
- **【红线】日志不打凭据、token、完整连接串、个人数据**。
- **【默认】异常作为最后一个参数传入**（`log.error("失败 table={}", t, e)`），否则堆栈丢失。
- **【默认】不打全量对象**：`toString()` 可能带敏感字段且有性能开销，打关键字段即可。
- **【建议】级别语义**：error = 需人介入；warn = 可自愈但要知道；info = 关键业务节点；debug = 排障细节。

## 6. 依赖与构建

- **【红线】不擅自升级依赖大版本 / 换构建工具**。升级附收益、破坏面、回滚方式。
- **【默认】不加"全家桶"依赖**。Spring 项目按需引 starter，不要为了一个工具类引整套框架。
- **【默认】依赖冲突用 `mvn dependency:tree -Dincludes=...` 查证**，不靠猜。
- **【红线】SNAPSHOT 依赖不上生产镜像**。
- **【建议】构建参数注入版本信息**（`APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME`），不把版本号写死在代码里。

## 7. 测试

- **【默认】沿用项目既有测试栈**（JUnit 5 + AssertJ / Mockito），不引入第二套。
- **【默认】纯逻辑抽成无副作用方法**再测；不要在单测里连真实数据库、真实集群。
- **【默认】时间和随机数通过注入（`Clock` / `Random`）**，否则测试不可重复。
- **【红线】不为了让测试过而放宽断言或删用例**。用例错了改用例，代码错了改代码，交付说明里讲清是哪一种。
- **【建议】每个 bug 修复补一个能复现的用例**，否则同一个坑会回来。

## 8. 反例清单（见到就改）

```java
// ✗ 虚拟线程里用 synchronized 护共享状态（JDK 24 前会 pin 载体线程）
synchronized (lock) { counter++; }

// ✗ Optional 当参数 / 字段
public void save(Optional<String> name) { ... }

// ✓ 返回值用 Optional，但参数用可空 + requireNonNull
public void save(String name) { Objects.requireNonNull(name, "name"); }

// ✗ 吞异常
try { doIt(); } catch (Exception e) { }

// ✗ 无界线程池
Executors.newFixedThreadPool(10)

// ✓ 有界 + 拒绝策略 + 命名
new ThreadPoolExecutor(10, 20, 60, SECONDS,
    new ArrayBlockingQueue<>(200), namedFactory("io-"), new CallerRunsPolicy());
```
