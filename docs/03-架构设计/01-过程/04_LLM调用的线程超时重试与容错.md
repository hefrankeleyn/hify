# LLM 调用：线程管理 · 超时 · 重试 · 容错（过程稿）

> 🔧 **过程稿**。四个维度的完整方案，拍板后并入基准文档并压缩关键条目进 `CLAUDE.md`。
> 依据：`../../02-产品决策/03_Hify部署与运维（基准）.md`、`../02-决策/01_Hify代码组织（基准）.md`、`../02-决策/02_Hify模块内部分层（基准）.md`
> 落点：全部代码位于 `hify-provider` 的 `client` 包（不依赖 Mapper / Entity / Spring 配置，只依赖方法入参——见 `03_拆分hify-chat的改动量评估.md` 第五节）
> 整理日期：2026-08-11

---

## 〇、结论速览

| 维度 | 结论 |
|---|---|
| **线程** | 两个独立线程池（`llm-chat-` / `kb-index-`），**`core=32 / max=64 / queue=16`**。队列必须小——这是本方案里最反直觉的一处，见 1.2 |
| **超时** | **四道闸**：连接 3s / 首字节 30s（Ollama 90s）/ **流间隔 30s** / 整体 300s。SSE 端 300s + 15s 心跳 |
| **重试** | **铁律：首字节到达之后一律不重试。** 最多 2 次，指数退避 + 抖动，总预算 60s |
| **容错** | per-provider 信号量舱壁（Ollama 2–4，云 20）+ Resilience4j 时间窗熔断 + 断连立即取消上游 |
| **降级** | 一期**不做**自动切换备用模型。明确报错，并在错误码里标明「是否可重试」 |
| **依赖** | 只加 `resilience4j-circuitbreaker` 一个 jar。重试、舱壁、超时全部手写（合计约 150 行） |

---

## 一、线程管理

### 1.1 一次对话到底占用哪些线程

```
浏览器 ──HTTP──▶ Tomcat 工作线程
                    │ 立刻返回 SseEmitter,线程释放  ← 用 SseEmitter 的全部意义
                    ▼
              llm-chat 线程池的一个线程   ← 从这里开始,整轮对话独占它 1~5 分钟
                    │
                    ├─ RAG 检索(百毫秒)
                    ├─ HTTP 请求 LLM ──▶ 阻塞在 InputStream.read() 等 token
                    │                     ↑ 这里是全部时间的 95%
                    └─ 工具调用循环 → 再次请求 LLM
```

**关键事实：一条 SSE 对话 = 一个业务线程被占满整轮。** 峰值并发 5 就是 5 个线程常驻，50 就是 50 个。所以线程池配置直接等于「能同时服务多少人对话」。

> 一期不上非阻塞 HTTP 客户端。50 人规模下 64 个阻塞线程完全够用，引 WebClient / Reactor 要把整条编排链路改成响应式，对一个人是纯负债。触发阈值见 `01_Hify代码组织（基准）.md` 第十节第 2 项（峰值并发 SSE > 50）。

### 1.2 ⚠️ `ThreadPoolTaskExecutor` 的队列陷阱

`CLAUDE.md` 现在写的是 **core 16 / max 64 / queue 128**。这个配置对 SSE 是错的。

JDK `ThreadPoolExecutor` 的扩容顺序是：**核心线程满 → 进队列 → 队列满才扩到 max**。

于是 core 16 / queue 128 的真实行为是：

| 并发请求数 | 实际发生什么 |
|---|---|
| 1–16 | 正常，16 个线程在跑 |
| **17–144** | **全部进队列排队**，线程数**永远停在 16** |
| 145+ | 才开始扩容到 64 |

对短任务（毫秒级）排队无害。**对 SSE 这种分钟级长任务是致命的**：第 17 个用户发消息后，要等前面某一整轮对话彻底结束才轮到他，期间页面一个字都不出。而线程池里还有 48 个线程的余量从没被用上。

**修正配置：**

```java
/**
 * SSE 对话专用线程池。
 * <p>队列必须小:长任务场景下大队列会让请求排队而不是扩容线程,
 * 表现为「第 17 个用户干等到前一轮对话结束」。
 */
@Bean("llmChatExecutor")
public ThreadPoolTaskExecutor llmChatExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(32);              // 覆盖常态并发,不排队
    executor.setMaxPoolSize(64);               // 峰值上限
    executor.setQueueCapacity(16);             // ★ 小队列:只吸收瞬时毛刺
    executor.setKeepAliveSeconds(120);
    executor.setThreadNamePrefix("llm-chat-");
    // 满了就明确拒绝,不用 CallerRunsPolicy——那会让 Tomcat 线程去跑分钟级任务
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);   // 优雅停机:给在途对话 30 秒收尾
    executor.initialize();
    return executor;
}
```

**内存核算**（容器 1GB / 堆 512m）：64 线程 × 1MB 默认栈 = **64MB 堆外**。加 Metaspace 约 100MB、堆 512MB，总计约 680MB，1GB 容器有余量。**不要把 max 调到 128 以上**，栈会吃掉容器给堆外留的那点空间。

**拒绝时的行为**：捕获 `RejectedExecutionException` → 立即返回明确错误「当前对话数已达上限，请稍后重试」。这符合既定的失败降级策略（LLM 侧问题明确报错，不静默排队）。

### 1.3 池必须隔离

**至少两个池，绝不共用：**

| 池 | 用途 | 配置 | 不隔离会怎样 |
|---|---|---|---|
| `llm-chat-` | SSE 对话编排 | core 32 / max 64 / queue 16 | — |
| `kb-index-` | 知识库文档解析、分块、向量化 | core 2 / max 4 / queue 64 | 一次批量导入 50 个文档，**把对话线程全吃光**，所有人卡死 |

`kb-index-` 是 CPU 密集型，线程数按核数配（2–4），队列可以大（任务能等）。这与对话池的取向正好相反。

### 1.4 三条配置红线

1. **禁止使用默认的 `SimpleAsyncTaskExecutor`** —— 它每个任务新建一个线程且无上界，50 并发下一路建到 OOM。
2. **必须给 Spring MVC 异步显式指定 executor**，否则 `SseEmitter` 的超时与错误分发仍走默认池：
   ```java
   @Override
   public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
       configurer.setTaskExecutor(llmChatExecutor());
       configurer.setDefaultTimeout(300_000L);   // 与 SseEmitter 超时一致
   }
   ```
3. **HTTP 客户端的线程池也要显式给**。JDK `HttpClient` 默认用无界 cached pool：
   ```java
   HttpClient.newBuilder()
       .connectTimeout(Duration.ofSeconds(3))
       .executor(llmHttpExecutor)        // ★ 显式指定,别用默认
       .build();
   ```
   一期用 **JDK 11+ `HttpClient`**（JDK 内置、原生支持流式 body、零额外依赖），**全局共用一个实例**，不要每次请求 new——那会泄漏连接池。

---

## 二、超时：四道闸

单一超时值挡不住 LLM 的失败形态。**「连不上」「模型在想」「流卡住」「无限生成」是四件事，要四个值。**

### 2.1 四道闸的定义

| # | 闸 | 默认值 | 挡什么 | 实现方式 |
|---|---|---|---|---|
| 1 | **连接超时** | 3s | 服务不可达、DNS 挂了 | `HttpClient.connectTimeout()` |
| 2 | **首字节超时（TTFB）** | 30s | 服务在但不干活、请求排队 | `HttpRequest.timeout()`（覆盖到响应头） |
| 3 | **流间隔超时** ★ | 30s | **流开始后卡死**——最常见也最难查 | **必须自己实现**，见 2.2 |
| 4 | **整体超时** | 300s | 模型无限生成、死循环 | 读流循环里判总耗时 |
| 5 | **SseEmitter 超时** | 300s + 15s 心跳 | 前端连接的生命周期 | 见 2.3 |

**第 3 道闸是重点**：标准 HTTP 客户端的 read timeout 只覆盖「第一个字节」，流一旦开始，后面每收到一个 token 就重置计时，**上游卡住 10 分钟不发数据也不会超时**。必须在读流循环里自己算「距离上一个 chunk 多久了」。

### 2.2 流间隔超时的实现

```java
/**
 * 带流间隔与整体超时的 SSE 读取循环。
 *
 * @param stream    上游响应流
 * @param consumer  每个增量文本片段的消费者
 * @param cfg       该 provider 的超时配置
 * @throws BizException 流间隔超时或整体超时
 */
private void readStream(InputStream stream, Consumer<String> consumer, TimeoutConfig cfg) {
    long start = System.currentTimeMillis();
    long lastChunkAt = start;

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            long now = System.currentTimeMillis();

            // 闸 3:两个 chunk 之间超过阈值,判定上游卡死
            if (now - lastChunkAt > cfg.getStreamIdleMillis()) {
                log.error("上游流卡死, provider={}, 已静默={}ms", cfg.getProviderName(), now - lastChunkAt);
                throw new BizException(ProviderErrorCode.STREAM_IDLE_TIMEOUT);
            }
            // 闸 4:整体耗时上限
            if (now - start > cfg.getTotalMillis()) {
                log.error("上游流超总时长, provider={}, 已耗时={}ms", cfg.getProviderName(), now - start);
                throw new BizException(ProviderErrorCode.STREAM_TOTAL_TIMEOUT);
            }

            if (line.isEmpty()) continue;          // SSE 的空行分隔
            lastChunkAt = now;
            parseAndEmit(line, consumer);
        }
    } catch (IOException e) {
        // 断连时主动 close() 触发的 IOException 属于正常路径,不要 error 级
        log.warn("上游流读取中断, provider={}", cfg.getProviderName(), e);
        throw new BizException(ProviderErrorCode.STREAM_BROKEN);
    }
}
```

> **注意**：这个循环本身也是「断连时取消上游」的落点——外部 `close()` 掉 `stream` 会让 `readLine()` 抛 `IOException`，循环退出，连接释放。见 4.4。

### 2.3 SSE 端超时：结清 `CLAUDE.md` 待定项 1

`CLAUDE.md` 待定项 1 在纠结「60 秒防僵尸连接 vs 带 RAG + 工具调用可能超时被截断」。**这是个假两难**——因为两个目标由两个不同机制解决：

| 目标 | 用短超时解决 | 用心跳解决 |
|---|---|---|
| 防僵尸连接（用户关了页面，服务端还挂着） | ❌ 最多也要等 60 秒才回收 | ✅ **15 秒内必然发现**：往已关闭的连接写心跳会立即抛异常 → 触发 `onError` → 释放资源 + 取消上游 |
| 不截断长生成 | ❌ 直接冲突 | ✅ 不冲突 |

**结论：`SseEmitter` 超时设 300 秒，配 15 秒心跳。**

```java
/** SSE 生命周期管理。心跳既保活代理层,又是探测客户端断连的手段。 */
public SseEmitter create(String conversationId) {
    SseEmitter emitter = new SseEmitter(300_000L);   // 5 分钟,略大于上游整体超时

    // 15 秒一次注释帧:不进前端 message 回调,只用于探活
    ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
        try {
            emitter.send(SseEmitter.event().comment("hb"));
        } catch (Exception e) {
            // 客户端已断开 —— 这正是我们要的信号
            log.info("心跳失败,判定客户端断连, conversationId={}", conversationId);
            emitter.completeWithError(e);
        }
    }, 15, 15, TimeUnit.SECONDS);

    // 三个回调都要挂,缺一个就漏一种断连场景
    emitter.onCompletion(() -> release(conversationId, heartbeat));
    emitter.onTimeout(()    -> { log.warn("SSE 超时, conversationId={}", conversationId);
                                 release(conversationId, heartbeat); });
    emitter.onError(e       -> { log.warn("SSE 异常, conversationId={}", conversationId, e);
                                 release(conversationId, heartbeat); });
    return emitter;
}
```

**配套要求**：Nginx 必须 `proxy_buffering off` + 关闭 gzip（否则心跳和 token 都被攒批），且 `proxy_read_timeout` 要 > 300s。

### 2.4 超时值按 provider 配置

一套值套不住四家。**Ollama 是本地推理，冷启动要加载模型到显存，首字节可能 60 秒以上**；云 API 通常 2–5 秒。

```yaml
hify:
  provider:
    timeout:
      default:  { connect: 3s, ttfb: 30s,  stream-idle: 30s, total: 300s }
      ollama:   { connect: 3s, ttfb: 90s,  stream-idle: 60s, total: 600s }   # 本地模型加载慢
```

超时值属于 provider 配置的一部分，随 provider 记录走，不是全局常量。

---

## 三、重试

### 3.1 铁律：首字节之后不重试

这是流式场景与普通 RPC 最大的区别，也是最容易写错的地方。

> **一旦第一个 token 已经推给前端，任何失败都不许重试。**

用户屏幕上已经出现了半句话。重试会让第二次生成的内容**接在半句话后面**——模型不是确定性的，用户看到的是两段不连贯的文本拼在一起，比直接报错糟糕得多。

所以重试判断需要一个显式的状态位：

```java
/** 是否已向前端推送过内容。一旦为 true,后续任何失败都不再重试。 */
private volatile boolean firstTokenEmitted = false;
```

| 失败发生的时刻 | 处理 |
|---|---|
| 建连 / 等首字节阶段 | ✅ 可重试（用户还在看 loading，无感知） |
| **首字节之后** | ❌ **绝不重试**，直接推 error 事件并结束流 |

### 3.2 错误分类表

覆盖 OpenAI 兼容端点与 Anthropic 原生端点两套错误语义：

| HTTP / 异常 | Anthropic `error.type` | 可重试 | 处理 |
|---|---|:---:|---|
| 连接被拒 / DNS 失败 / 连接超时 | — | ✅ | 退避后重试 |
| **首字节超时** | — | ✅ | 重试 **1 次**（不是 2 次，见 3.3） |
| **429** | `rate_limit_error` | ✅ | **优先读 `Retry-After` 响应头**；没有则用退避值 |
| 500 | `api_error` | ✅ | 退避后重试 |
| 502 / 503 / 504 | — | ✅ | 退避后重试 |
| **529** | `overloaded_error` | ✅ | Anthropic 特有的「服务过载」，退避后重试 |
| 400 | `invalid_request_error` | ❌ | 请求本身有问题（含上下文超长），重试必然再失败 |
| 401 | `authentication_error` | ❌ | API Key 错，明确报「模型凭证无效」 |
| 403 | `permission_error` | ❌ | 无权限/无该模型访问权 |
| 404 | `not_found_error` | ❌ | 模型 ID 不存在 |
| 413 | `request_too_large` | ❌ | 请求体过大，应做上下文裁剪而不是重试 |
| **流中断（首字节后）** | — | ❌ | 见 3.1 |

**不可重试的错误必须区分成两类推给前端**，因为用户能做的事不一样：

- **配置问题**（401 / 403 / 404）→ 「模型配置有误，请检查提供商设置」+ 跳转配置页
- **请求问题**（400 / 413）→ 「对话上下文过长，请开启新会话」

### 3.3 退避与预算

```
第 1 次重试:  500ms  ± 30% 抖动
第 2 次重试: 1500ms  ± 30% 抖动
最多 2 次(总共 3 次尝试)
```

**必须有总预算**：从最初发起请求算起，**超过 60 秒不再重试**，直接失败。

原因很实际：首字节超时是 30 秒，如果重试 2 次而不设预算，最坏情况是 30 + 30 + 30 = 90 秒纯等待，用户早就刷新页面走了。带预算的话第二次重试会被预算挡下——这也是为什么首字节超时只重试 1 次。

**抖动是必要的**：50 个用户同时命中同一个 429，无抖动会让他们在同一毫秒一起重试，把上游再打挂一次。

### 3.4 手写，不引 Spring Retry

重试逻辑和「首字节前后」这个状态强耦合，`@Retryable` 的注解模型表达不了。手写 20 行反而清楚：

```java
/**
 * 带重试的流式调用。
 * <p><b>重试只在首字节到达前发生</b>——见规范 3.1。
 */
public void streamWithRetry(ChatRequest request, StreamCallback callback) {
    long deadline = System.currentTimeMillis() + RETRY_BUDGET_MILLIS;   // 60s 总预算
    int attempt = 0;

    while (true) {
        try {
            doStream(request, callback);        // 内部在推第一个 token 时置 firstTokenEmitted
            return;
        } catch (BizException e) {
            attempt++;
            boolean retryable = !callback.isFirstTokenEmitted()          // 铁律
                    && isRetryable(e)                                     // 错误分类表
                    && attempt <= MAX_ATTEMPTS                            // 2 次
                    && System.currentTimeMillis() < deadline;             // 预算

            if (!retryable) {
                log.error("LLM 调用最终失败, provider={}, attempt={}, emitted={}, code={}",
                        request.getProviderName(), attempt,
                        callback.isFirstTokenEmitted(), e.getErrorCode().getCode());
                throw e;
            }

            long backoff = backoffWithJitter(attempt, e);   // 429 优先用 Retry-After
            log.warn("LLM 调用失败准备重试, provider={}, attempt={}, backoff={}ms, code={}",
                    request.getProviderName(), attempt, backoff, e.getErrorCode().getCode());
            sleep(backoff);
        }
    }
}
```

### 3.5 工具调用循环里的特殊约束

对话不是一次请求，是「调模型 → 调工具 → 再调模型」的循环。**重试的粒度必须是单次 LLM 请求，不是整轮对话。**

危险场景：模型第 1 轮调用了「发邮件」工具（已经真的发出去了），第 2 轮 LLM 请求失败——如果重试整轮，邮件会**再发一次**。

**规则：**

1. 重试只包住**单次 HTTP 请求**，不包住工具调用循环；
2. **工具已执行之后的那一轮 LLM 请求，重试上限降为 0**——只允许重试纯连接失败（连接根本没建立，上游一定没收到请求，且工具不会被重复执行）；
3. 工具调用轮次上限（防死循环）：**最多 5 轮**，超过则把当前结果交回用户并记 `log.warn`。

---

## 四、容错

### 4.1 舱壁：per-provider 并发上限

线程池是全局的，但**一个慢 provider 不该拖垮所有人**。给每个 provider 一个信号量：

```java
/** 每个 provider 独立的并发闸门,防止单一慢上游吃光全局线程池。 */
private final Map<Long, Semaphore> bulkheads = new ConcurrentHashMap<>();

if (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
    log.warn("provider 并发已满, providerId={}, 可用许可=0", providerId);
    throw new BizException(ProviderErrorCode.PROVIDER_BUSY);
}
try { /* 发起调用 */ } finally { semaphore.release(); }
```

**并发上限按 provider 性质配，差别很大：**

| provider | 上限 | 理由 |
|---|---:|---|
| Ollama | **2–4** | 本地单机推理，超过显存并发能力后**所有请求一起变慢**，不是排队而是集体劣化 |
| OpenAI / Claude / Gemini | 20 | 云端弹性，瓶颈在自己的线程池不在对方 |

Ollama 这个数字最容易配错：以为「本地不要钱可以随便打」，实际是本地最脆弱的一环。

### 4.2 熔断：值得引一个 jar

**引 `resilience4j-circuitbreaker`**（单个 jar，纯内存，不需要任何外部组件，不增加运维负担）。

为什么值得：Ollama 服务停了以后，**每个请求都要等满 3 秒连接超时**。50 个用户轮流试，就是持续 3 秒/次的集体卡顿。熔断打开后立即失败（微秒级），用户马上看到「模型服务不可用」而不是转圈 3 秒。

```java
CircuitBreakerConfig.custom()
    .slidingWindowType(SlidingWindowType.TIME_BASED)   // ★ 时间窗,不是计数窗
    .slidingWindowSize(60)                             // 60 秒窗口
    .minimumNumberOfCalls(5)                           // 至少 5 次调用才开始统计
    .failureRateThreshold(50)                          // 失败率 > 50% 打开
    .waitDurationInOpenState(Duration.ofSeconds(30))   // 打开 30 秒后进半开
    .permittedNumberOfCallsInHalfOpenState(2)          // 半开时放 2 个试探
    .recordExceptions(IOException.class, TimeoutException.class)
    .ignoreExceptions(BizException.class)              // ★ 业务错误(400/401)不计入
    .build();
```

**两处必须注意：**

1. **用时间窗（`TIME_BASED`）不用计数窗**。3–5 QPS 下，计数窗要几分钟才填满 20 次调用，熔断永远来不及触发。
2. **`ignoreExceptions(BizException.class)`**：401（Key 配错）、400（上下文超长）是**请求自己的问题，不是上游故障**，计入失败率会让熔断被无辜打开。

**熔断器按 providerId 分实例**——OpenAI 挂了不能影响 Ollama。

### 4.3 降级：一期明确不做自动切换

技术上完全可以「Claude 挂了自动切 GPT」。**一期不做**，三条理由：

1. 用户选了 Claude 却收到 GPT 的回答，是**更糟的体验**——风格、能力、上下文长度全不同，而用户不知道发生了什么；
2. 成本不可控——切到贵模型没有任何提示；
3. 一个人开发，这条链路的测试成本远高于收益。

**既定策略是：明确报错推给前端。** 但错误信息要携带足够信息让前端做出正确的 UI：

```java
/** 推给前端的错误事件。isRetryable 决定前端是否显示「重试」按钮。 */
@Value
@Builder
public class StreamErrorEvent {
    /** 错误码,如 provider.stream.idle_timeout */
    String code;
    /** 面向用户的提示 */
    String message;
    /** 用户重试是否可能成功:超时/429/熔断 → true;401/400 → false */
    boolean retryable;
    /** 需要用户去改配置时,前端据此跳转 */
    String actionHint;   // "check_provider_config" / "new_conversation" / null
}
```

### 4.4 断连时立即取消上游（这条最省钱）

用户关了页面，如果不取消上游请求，**模型还在继续生成，token 照烧**。一次长回答可能是几千 token。

```java
/** 释放一次对话占用的全部资源。三个 SseEmitter 回调都会走到这里。 */
private void release(String conversationId, ScheduledFuture<?> heartbeat) {
    heartbeat.cancel(false);
    StreamSession session = sessions.remove(conversationId);
    if (session == null) return;

    session.setCancelled(true);          // 读流循环下一轮检查到就退出
    IoUtil.closeQuietly(session.getUpstreamStream());   // ★ 关闭上游流,触发 IOException 打断阻塞的 read
    session.getUpstreamFuture().cancel(true);           // ★ 取消 HttpClient 的异步任务

    log.info("对话资源已释放, conversationId={}, 已生成 token≈{}, 耗时={}ms",
            conversationId, session.getTokenCount(), session.elapsed());
}
```

**`release` 必须幂等**——三个回调可能都触发（比如先 `onError` 再 `onCompletion`），用 `sessions.remove()` 的返回值判空是最简单的幂等做法。

### 4.5 可观测：先只打日志，不建表

每次调用结束打一条结构化日志，够用了：

```java
log.info("LLM 调用结束, provider={}, model={}, result={}, ttfb={}ms, total={}ms, " +
         "promptTokens={}, completionTokens={}, attempts={}",
         providerName, modelName, result, ttfbMillis, totalMillis,
         promptTokens, completionTokens, attempts);
```

**`ttfb`（首字节耗时）是最有用的一个指标**——第二节所有超时值的调整都依赖它的实际分布。跑一周看 P95，再回头调 `ttfb` 阈值。

一期**不建 `provider_call_log` 表**：50 人规模下从日志 grep 就够，建表要配套写清理任务和查询界面，是提前投入。用量统计需求出现时再建（那时走 `@TransactionalEventListener` 旁路，见 `02_Hify模块内部分层（基准）.md` 5.2）。

---

## 五、代码落点：装饰器模式

**协议适配**和**容错**是两件正交的事，分开两个类，不要糅在一起：

```
com.hify.provider
├── service/
│   └── ChatModelClient.java              @ModuleApi 对外契约(接口)
└── client/
    ├── OpenAiCompatChatModelClient.java  只管协议:构造请求、解析 SSE、映射错误
    ├── AnthropicChatModelClient.java     只管协议:Anthropic 原生 Messages API
    ├── ResilientChatModelClient.java     ★ 装饰器:熔断 → 舱壁 → 重试 → 超时
    ├── StreamReader.java                 四道闸的读流循环(2.2)
    └── TimeoutConfig.java / RetryPolicy.java
```

```java
/**
 * 容错装饰器:为任意协议实现叠加熔断、舱壁、重试、超时。
 * <p>包装顺序即执行顺序:熔断(最外,失败最快) → 舱壁 → 重试 → 实际调用。
 */
@Slf4j
@RequiredArgsConstructor
public class ResilientChatModelClient implements ChatModelClient {

    /** 被装饰的协议实现 */
    private final ChatModelClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Semaphore bulkhead;
    private final RetryPolicy retryPolicy;
}
```

**这个结构同时满足 `03_拆分hify-chat的改动量评估.md` 第五节的约束**：`client` 包只依赖方法入参（`baseUrl` / 解密后的 `apiKey` / `modelName` / 超时与容错配置对象），不注入 `ProviderMapper`、不读 `ProviderEntity`、不读 Spring 配置。将来 `provider` 一分为二时，整个包可以原样抽成 jar。

---

## 六、四家 provider 的实际差异

| provider | 协议 | 认证 | 流终止标志 | 需要注意 |
|---|---|---|---|---|
| **OpenAI** | OpenAI SSE | `Authorization: Bearer` | `data: [DONE]` | 最稳定，作为基准实现 |
| **Gemini** | OpenAI 兼容端点 | `Authorization: Bearer` | `data: [DONE]` | 走兼容端点即可，不用原生 `streamGenerateContent` |
| **Ollama** | OpenAI 兼容端点 `/v1/chat/completions` | 通常无 | `data: [DONE]` | **首字节慢**（冷启动加载模型）、**并发能力低**（2–4） |
| **Claude** | **原生 Messages API**（见下） | **`x-api-key`** + `anthropic-version: 2023-06-01` | **`message_stop` 事件** | **需要单独适配器** |

### 6.1 Claude 为什么要单独写

Anthropic 的流式响应是**带 `event:` 名的多类型 SSE**，与 OpenAI 的单一 `data:` chunk 结构不同：

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_...",...}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

event: message_stop
data: {"type":"message_stop"}
```

事件类型共六种：`message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`。

四处与 OpenAI 兼容客户端不兼容：

1. **认证头**是 `x-api-key`，不是 `Authorization: Bearer`；且必须带 `anthropic-version: 2023-06-01`；
2. **增量文本**在 `delta.text`（`content_block_delta` 事件内），不是 `choices[0].delta.content`；
3. **流终止**靠 `message_stop` 事件，**没有 `[DONE]` 哨兵**；
4. **多一个错误码 529 `overloaded_error`**（服务过载），必须计入可重试集合。

**两个选项：**

| | 手写适配器 | 官方 `com.anthropic:anthropic-java` |
|---|---|---|
| 工作量 | 约 150 行 | 依赖一行 |
| 一致性 | 与其余三家共用同一套容错装饰器 | SDK 自带重试（`maxRetries` 默认 2），需要**关掉**避免与 3.3 的重试叠加 |
| 依赖 | 零 | 多一个依赖 |

**倾向手写适配器**：容错逻辑必须与另外三家完全一致（同一套熔断/舱壁/预算），引 SDK 反而要花力气把它自带的重试和超时关干净。**待拍板**。

---

## 七、配置项清单

```yaml
hify:
  provider:
    # 线程池
    executor:
      chat:  { core: 32, max: 64, queue: 16, keep-alive: 120s }
      index: { core: 2,  max: 4,  queue: 64, keep-alive: 60s }
    # 超时(可 per-provider 覆盖)
    timeout:
      default: { connect: 3s, ttfb: 30s, stream-idle: 30s, total: 300s }
      ollama:  { connect: 3s, ttfb: 90s, stream-idle: 60s, total: 600s }
    # 重试
    retry:
      max-attempts: 2
      base-backoff: 500ms
      multiplier: 3
      jitter: 0.3
      budget: 60s
      ttfb-max-attempts: 1        # 首字节超时只重试 1 次
    # 舱壁
    bulkhead:
      default: 20
      ollama: 3
      acquire-timeout: 500ms
    # 熔断
    circuit-breaker:
      window-type: time_based
      window-size: 60s
      minimum-calls: 5
      failure-rate: 50
      open-duration: 30s
      half-open-calls: 2
    # 工具调用循环
    tool-loop:
      max-rounds: 5

hify:
  chat:
    sse:
      timeout: 300s
      heartbeat: 15s
```

---

## 八、需要拍板的

| # | 事项 | 倾向 |
|---|---|---|
| 1 | **`CLAUDE.md` 的线程池参数要改** | core 16→**32**、queue 128→**16**。理由见 1.2，这是本文档唯一一处「现有基准是错的」 |
| 2 | **SSE 超时定 300s + 15s 心跳**（结清 `CLAUDE.md` 待定项 1） | 采纳。短超时防不了僵尸连接，心跳才能 |
| 3 | **引 `resilience4j-circuitbreaker`** | 引。单 jar、纯内存、零运维，解决「挂掉的 provider 拖慢所有人」 |
| 4 | **Claude 手写适配器 vs 官方 Java SDK** | 倾向手写（约 150 行），换容错逻辑四家完全一致 |
| 5 | **一期不做备用模型自动切换** | 采纳，理由见 4.3 |
| 6 | **一期不建 `provider_call_log` 表** | 采纳，先打结构化日志，用 `ttfb` 的 P95 回头调超时值 |
