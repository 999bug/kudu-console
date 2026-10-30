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
- **【红线】包装类型拆箱前判空**：`Long`/`Integer` 直接赋给 `long`/`int` 是 NPE 高发点，尤其是"以前是 int、后来改成 Long"的字段。可选字段在 getter 兜底并注释原因，必填字段保持 fail-fast。
- **【建议】可空返回值用注解表达**（`@Nullable` / `@NonNull`），比注释可靠。

## 3. 并发与虚拟线程

- **【红线】虚拟线程（JDK 21+）护共享状态时不用 `synchronized`**。JDK 24 之前 `synchronized` 会把载体线程 pin 住，吞吐直接塌。用 `ReentrantLock` + `try/finally` 解锁。
  - 判定标准只有一条：**锁内是否可能阻塞**（网络写、磁盘、JNI、等待其它锁）。会阻塞就必须换 JUC 锁，不阻塞的短临界区无所谓。
- **【红线】虚拟线程不做 CPU 密集计算**，也不在里面跑长时 JNI / 阻塞式文件 IO——那会占满载体线程，等于白用。
- **【默认】虚拟线程不要池化**。一个任务一个线程（`Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("app-",0).factory())`），池化会退化成平台线程池，还会让 ThreadLocal 污染扩散。
- **【默认】`ThreadLocal` 不存大对象**。虚拟线程数量可达百万级，会把内存放大到不可控；需要上下文传递时优先方法参数或 `ScopedValue`。
- **【红线】ThreadLocal 跨异步必须显式传播**：提交前捕获（`TaskDecorator` / 手动 set），任务内恢复，**finally 清**。三条清理路径缺一不可：请求 Filter（请求结束）、任务装饰器（任务结束）、业务 finally。漏一处的表现是"上一个任务的用户/平台串到下一个任务"。
- **【红线】禁止裸用 `Executors.newFixedThreadPool` / `newCachedThreadPool`**：前者无界队列会 OOM，后者线程数无上限。用 `ThreadPoolExecutor` 显式指定队列容量、拒绝策略、线程名。
- **【红线】有状态的第三方 SDK 客户端不要跨线程共享**，每次 new（原因见 `integration.md` 第 6 节）。
- **【默认】限流原语放全局**：`Semaphore` / `CountDownLatch` 在循环外创建。写在每个客户端/每批任务内部，会导致"并发数只在单客户端内生效"，跨客户端退化成串行。
- **【默认】多线程收集结果时按场景选集合**：只追加用 `ConcurrentLinkedQueue`，去重用 `ConcurrentHashMap.newKeySet()`，计数用 `AtomicInteger` / `LongAdder`。用 `ArrayList` 收集并发结果必然丢数据。
- **【默认】用协作式取消**：不用 `Thread.stop()`（会释放所有锁）、不依赖 `Future.cancel(true)` 的语义。停止标志 + `throwIfStopped()` 在 I/O 间隙轮询 + `interrupt()`。
- **【默认】锁内零 I/O**：锁外取数据，锁内只做指针/引用 swap（微秒级）。需要兜底时用 `tryLock(timeout)`，不要无限等。
- **【默认】JNI / 原生调用会 pin 载体线程**，能缓存结果就缓存（密码解密结果这类），把调用频次降下来。
- **【默认】`CompletableFuture` 指定执行器**，不要用默认 `ForkJoinPool.commonPool` 跑阻塞任务；异常必须 `handle` / `exceptionally` 兜底，否则静默丢失。

## 4. 超时与重试

- **【红线】超时预算必须层层小于上游**。写法：`本层总预算 + 单次调用最坏耗时 < 调用方等待上限`，并在配置项注释里写清推导。否则会出现「调用方已超时失败，本层还在跑并回写状态」，界面表现为"报错了又自己变好"。
- **【红线】`orTimeout` / `get(timeout)` 不会取消后台任务**。超时返回后，后台可能继续执行并写共享状态。要么真取消，要么让后台在超时后不再写缓存/状态。
- **【默认】HTTP 客户端的三个超时都要显式设**：连接、读取（response）、socket。只设前两个时，对端"连接还在但不再响应"会让线程无限阻塞。
- **【默认】连接池容量显式配置**（`maxPerRoute` / `maxTotal`）。框架默认值（常见 5/25）在并发场景一定不够，表现是排队而不是报错，很难定位。
- **【默认】重试前先判可重试性**：网络异常、超时、5xx、限流可重试；4xx、参数校验失败、认证失败不可重试。无差别重试会把"密码错误"放大成"账号被锁"。
- **【默认】失败退避的 key 用"账号 + 目标"，不用密码**：账号锁定是按账号计的，用密码做 key 会让人换个密码就绕过保护。退避要有初始值、上限、成功清零；配置变更（密码改动）放行一次。
- **【默认】长任务用"无进展超时"而不是总时长上限**：判据要选能反映真实进展的信号（行级/条级计数），只数"分片完成数"会把大分片误杀；单表跑几天只要持续有进展就不该失败。

## 5. 缓存

- **【红线】Guava `Cache.get(key, loader)` 的异常要 catch 两个**：loader 抛受检异常包成 `ExecutionException`，抛非受检异常包成 `UncheckedExecutionException`。只 catch 前者，登录失败后的清理与类型转换永远不会执行。
- **【默认】缓存 key 要包含"会改变结果"的凭据指纹**（密码 hash、token 版本）。否则用户改了配置但缓存照旧，表现为"改了密码还显示在线"。
- **【默认】TTL 对齐服务端生命周期，不要猜**：服务端控制过期时间时，客户端靠 401/错误码触发失效，TTL 只做内存兜底（容量上限）。
- **【默认】缓存要能失效**：至少留一条 `invalidate()` 路径，供 401、配置变更调用。
- **【默认】被高频轮询的接口必须有缓存层**：前端 10 秒轮询一次 = 8640 次/天，指向真实下游会把对方账号刷锁。分级：缓存命中 → 轻量探活 → 才真实认证。
- **【默认】并发加载要合并**：多线程同时未命中时用 `get(key, loader)` 的原子加载，不要自己写 check-then-act。

## 6. 异常与错误

- **【红线】禁止空 catch**：`catch (Exception e) {}` 是最常见的故障放大器。
- **【红线】禁止 `e.printStackTrace()`**，用日志框架。
- **【红线】禁止抛裸 `RuntimeException` / `IllegalArgumentException`**。用语义化异常子类 + 稳定错误码，上层才能按类型处理、按码上报。
- **【默认】受检异常转业务异常**，保留 `cause` 链（`new BizException(code, msg, e)`），不要只打日志后返回 null。
- **【默认】用 try-with-resources 管资源**，不手写 `finally { close() }`。
- **【默认】不用异常做流程控制**（用异常判断"存不存在"属于反模式，先查再走）。
- **【默认】业务语义有优先级**：用户主动停止 > 普通异常；聚合多个异常时按语义排序，别让先到的技术异常覆盖掉"用户停止了"这个事实。
- **【建议】对外 API 的 code 是契约**，新增必须同步文档。

## 7. 日志

- **【默认】用 SLF4J 占位符**（`log.info("table={} rows={}", t, n)`），不用字符串拼接。
- **【默认】日志消息用英文**（异常消息、CLI 输出同理），代码注释与文档用中文——日志要贴工单、被 grep、被不同语言的同事读。
- **【红线】日志不打凭据、token、完整连接串、个人数据**。需要保留排障信息时脱敏：首尾各留 4 位、中间打码。
- **【默认】异常作为最后一个参数传入**（`log.error("failed table={}", t, e)`），否则堆栈丢失。
- **【默认】高频路径降级 + 状态翻转才输出**：心跳、轮询、推送这类每几秒一次的日志用 debug；状态没变用 debug，首次与翻转用 info/warn；持续失败定期（如每 10 分钟）补一条 warn，避免刷满磁盘。
- **【默认】不打全量对象**：`toString()` 可能带敏感字段且有性能开销，打关键字段即可。
- **【建议】级别语义**：error = 需人介入；warn = 可自愈但要知道；info = 关键业务节点；debug = 排障细节。

## 8. JSON 序列化

- **【默认】优先用带类型的绑定**（POJO / record），原始 `JsonElement` 转 `Map` 只用于确实需要动态结构的场景。
- **【红线】原始 JSON 里的数字不要直接强转**。Gson 的 `JsonPrimitive.getAsNumber()` 返回 `LazilyParsedNumber`，强转 `Integer`/`Double` 会 `ClassCastException`。按值显式分档：含小数点或指数 → `Double`；整数按范围 → `int` / `long` / `BigInteger`。
- **【默认】`Gson` 实例线程安全但创建有成本**，做成单例常量；不同序列化策略用枚举缓存，别每次 `new GsonBuilder()`。
- **【默认】工具类禁止实例化**（私有构造 + 抛 `UnsupportedOperationException`），避免被 new 出来后带着状态乱跑。
- **【默认】`null` 与"字段不存在"在契约上要区分清楚**：序列化时是否输出 null 字段，全局统一。

## 9. 依赖与构建

- **【红线】不擅自升级依赖大版本 / 换构建工具**。升级附收益、破坏面、回滚方式。
- **【默认】不加"全家桶"依赖**。Spring 项目按需引 starter，不要为了一个工具类引整套框架。
- **【默认】依赖冲突用 `mvn dependency:tree -Dincludes=...` 查证**，不靠猜。
- **【红线】SNAPSHOT 依赖不上生产镜像**。
- **【建议】构建参数注入版本信息**（`APP_VERSION` / `GIT_COMMIT` / `BUILD_TIME`），不把版本号写死在代码里。

## 10. 测试

- **【默认】沿用项目既有测试栈**（JUnit 5 + AssertJ / Mockito），不引入第二套。
- **【默认】纯逻辑抽成无副作用方法**再测；不要在单测里连真实数据库、真实集群。
- **【默认】时间和随机数通过注入（`Clock` / `Random`）**，否则测试不可重复。
- **【默认】开发期专用的慢测试用标签分组**（JUnit 的 `@Tag("dev")`）并在打包时排除，而不是整体设 `skipTests`。
- **【默认】给 IO 留测试缝**：包私有构造器 / 参数注入、`@VisibleForTesting` 标注、把纯逻辑抽成静态方法。没有测试缝的 IO 代码只能靠真机验证，等于没测试。
- **【红线】不为了让测试过而放宽断言或删用例**。用例错了改用例，代码错了改代码，交付说明里讲清是哪一种。
- **【建议】每个 bug 修复补一个能复现的用例**，否则同一个坑会回来。

## 11. 反例清单（见到就改）

```java
// ✗ 虚拟线程里用 synchronized 护阻塞 I/O（JDK 24 前会 pin 载体线程）
synchronized (lock) { session.sendMessage(msg); }

// ✓
lock.lock();
try { session.sendMessage(msg); } finally { lock.unlock(); }

// ✗ Optional 当参数 / 字段
public void save(Optional<String> name) { ... }

// ✓ 返回值用 Optional，参数用可空 + requireNonNull
public void save(String name) { Objects.requireNonNull(name, "name"); }

// ✗ 并发收集用 ArrayList（多线程 add → 丢数据 / CME）
List<String> failed = new ArrayList<>();
List<String> failed = new ConcurrentLinkedQueue<>();

// ✗ 只 catch 了一个缓存包装异常
try { cache.get(k, loader); } catch (ExecutionException e) { cleanup(); }

// ✗ 无界线程池
Executors.newFixedThreadPool(10)

// ✓ 有界 + 拒绝策略 + 命名
new ThreadPoolExecutor(10, 20, 60, SECONDS,
    new ArrayBlockingQueue<>(200), namedFactory("io-"), new CallerRunsPolicy());
```
