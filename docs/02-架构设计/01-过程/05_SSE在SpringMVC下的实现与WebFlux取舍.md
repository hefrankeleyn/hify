# SSE 在 Spring MVC 下的实现，以及要不要引 WebFlux（过程稿）

> 🔧 **过程稿**。回答两个问题：Spring MVC 怎么正确实现 SSE；WebFlux 该不该引。
> 关联：`04_LLM调用的线程超时重试与容错.md`（线程池 / 超时 / 心跳的具体参数在那篇，本篇不重复）
> 整理日期：2026-08-11

---

## 〇、结论

> **不引 WebFlux。Spring MVC 的 `SseEmitter` 就是正确答案，而且不是「将就」——在本项目的约束下它是更优解。**

一句话理由：**`SseEmitter` 已经解决了「长连接独占 Tomcat 线程」这个问题**（Servlet 3.0 异步），而 WebFlux 多解决的那部分——「业务线程也不阻塞」——**在上游 HTTP 客户端仍是阻塞式的前提下拿不到**，要拿到就得连 MyBatis-Plus 一起换掉。

| 问题 | 答案 |
|---|---|
| Tomcat 线程会被 SSE 长连接占住吗 | ❌ 不会。返回 `SseEmitter` 那一刻就释放了 |
| Tomcat 能同时挂多少条 SSE 连接 | 默认 `max-connections=8192`，**不是瓶颈** |
| 那真正的并发上限是什么 | **业务线程池的 `maxPoolSize`**（当前定 64） |
| 64 够吗 | 目标 50 人在线、峰值并发 3–5，**够 10 倍以上** |
| 什么时候才需要 WebFlux | 峰值并发 SSE **持续 > 200**，且已经走完第五节的两级升级 |

---

## 一、`SseEmitter` 到底做了什么

### 1.1 请求的生命周期

```
① 浏览器发起 POST /api/chat/completions
        ▼
② Tomcat 工作线程 (http-nio-8080-exec-N) 进入 Controller
        ▼
③ Controller 返回 SseEmitter 对象
        │  Spring 检测到返回值是 SseEmitter →  调用 request.startAsync()
        │  HTTP 响应保持打开,但 Servlet 请求进入「异步模式」
        ▼
④ ★ Tomcat 工作线程立即归还线程池 ★   ← 全部意义所在
        │  此后这条连接由 Tomcat 的 NIO Poller 线程(少量,与连接数无关)照看
        ▼
⑤ 你自己的线程 (llm-chat-N) 调 emitter.send(...) 往这条连接写数据
        ▼
⑥ emitter.complete()  →  AsyncContext.complete()  →  响应真正结束
```

**第 ④ 步是 `SseEmitter` 与阻塞式 `@ResponseBody` 的全部区别。**

对照一下：如果用 `@ResponseBody` 返回 `String` 并在方法里循环写 `HttpServletResponse.getWriter()`，第 ④ 步不存在——Tomcat 线程从头挂到尾。默认 `server.tomcat.threads.max=200`，200 个人同时对话就把 Tomcat 打满，**连管理后台的页面都打不开了**。这是 `CLAUDE.md` 里「必须用 `SseEmitter`，不用阻塞式 `@ResponseBody`」的原因。

### 1.2 澄清一个常见误解

> **`SseEmitter` 释放的是 Tomcat 线程，不是你的业务线程。**

第 ⑤ 步那个 `llm-chat-N` 线程，在整轮对话期间（1–5 分钟）是**被占满的**——它阻塞在 `InputStream.read()` 上等上游 token。

所以：

| 资源 | SSE 期间是否被独占 | 上限 |
|---|---|---|
| Tomcat 工作线程 | ❌ 否 | 200（用不到） |
| **业务线程池线程** | ✅ **是，整轮** | **64（这是真正的并发上限）** |
| Tomcat 连接槽位 | ✅ 是（一个 socket） | 8192（用不到） |

「引 WebFlux 能不能消掉那个被占满的业务线程」——能，但代价见第四节。

---

## 二、Spring MVC 侧的完整实现

### 2.1 Controller

```java
/**
 * 对话接口。
 * <p>返回 {@link SseEmitter} 后 Tomcat 线程立即释放,内容由 llm-chat 线程池产生。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SseSessionManager sseSessionManager;
    private final ChatRuntime chatRuntime;
    private final ThreadPoolTaskExecutor llmChatExecutor;

    /**
     * 发起一轮流式对话。
     *
     * @param request 对话请求（agentId、会话 id、用户消息）
     * @return SSE 发射器，立即返回，内容异步推送
     */
    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@Valid @RequestBody ChatCompletionRequest request) {
        log.info("[API] 发起对话, agentId={}, conversationId={}",
                request.getAgentId(), request.getConversationId());

        SseEmitter emitter = sseSessionManager.create(request.getConversationId());

        // ★ ThreadLocal 在异步线程里不存在,必须现在取出来显式传进去(见 3.2)
        Long userId = UserContext.getUserId();
        String traceId = MDC.get("traceId");

        llmChatExecutor.execute(() -> {
            MDC.put("traceId", traceId);
            try {
                chatRuntime.stream(request, userId, emitter);
                emitter.complete();
            } catch (Exception e) {
                // ★ 异步线程抛出的异常不会进 @RestControllerAdvice(见 3.1)
                sseSessionManager.sendError(emitter, e);
            } finally {
                MDC.clear();
            }
        });

        return emitter;   // 方法到此结束,Tomcat 线程回池
    }
}
```

**注意 `produces = TEXT_EVENT_STREAM_VALUE`** —— 不写的话 Content-Type 可能不是 `text/event-stream`，部分代理和浏览器会当普通响应缓冲起来。

### 2.2 三类事件的约定

前端要能区分「正常内容 / 出错 / 结束」，所以给事件命名，不要全部裸发：

```java
/** 推送一个增量文本片段 */
emitter.send(SseEmitter.event().name("message").data(delta, MediaType.APPLICATION_JSON));

/** 推送错误(可重试性由 retryable 字段带给前端,见 04 文档 4.3) */
emitter.send(SseEmitter.event().name("error").data(errorEvent, MediaType.APPLICATION_JSON));

/** 正常结束,带本轮统计 */
emitter.send(SseEmitter.event().name("done").data(summary, MediaType.APPLICATION_JSON));

/** 心跳:注释帧,不触发前端任何回调,仅用于探活与保活(见 04 文档 2.3) */
emitter.send(SseEmitter.event().comment("hb"));
```

### 2.3 必须做的全局配置

```java
/**
 * Web 异步支持配置。
 * <p>不显式设置 taskExecutor 时,Spring 用无上界的 SimpleAsyncTaskExecutor
 * 处理异步分发,并在启动日志里给出警告。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ThreadPoolTaskExecutor llmChatExecutor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(llmChatExecutor);
        configurer.setDefaultTimeout(300_000L);   // 与 SseEmitter 超时保持一致
    }
}
```

```yaml
server:
  tomcat:
    threads:
      max: 200            # 处理普通请求,SSE 不占用,保持默认即可
    max-connections: 8192 # ★ 长连接的真实上限在这里,默认值已远超需要
    connection-timeout: 20s
spring:
  mvc:
    async:
      request-timeout: 300000
```

> **`threads.max` 和 `max-connections` 是两件事**，这是判断「MVC 撑不撑得住长连接」的关键。Tomcat 用 NIO：少量 Poller 线程照看所有已建立的连接，只有**有数据要处理**的连接才去线程池取线程。SSE 连接绝大部分时间没有入站数据，所以它们占的是 `max-connections` 的槽位（8192），不是 `threads.max`（200）。

### 2.4 Nginx（不配就没有打字机效果）

```nginx
location /api/chat/ {
    proxy_pass http://hify_backend;
    proxy_http_version 1.1;          # SSE 需要 HTTP/1.1
    proxy_set_header Connection '';  # 清掉 Connection: close

    proxy_buffering off;             # ★ 不关就会攒批下发
    proxy_cache off;
    gzip off;                        # ★ gzip 会缓冲直到攒够一个压缩块

    proxy_read_timeout 360s;         # 必须 > SseEmitter 的 300s
    proxy_send_timeout 360s;
}
```

后端也顺手带一个头，供其它反代识别：

```java
response.setHeader("X-Accel-Buffering", "no");
response.setHeader("Cache-Control", "no-cache");
```

---

## 三、七个陷阱

### 3.1 异步线程里的异常**不会**进全局异常处理器 ⚠️

`@RestControllerAdvice` 只拦 Controller 方法**同步执行期间**抛出的异常。Controller 已经 `return emitter` 了，之后在 `llm-chat-N` 线程里抛的任何异常，**全局异常处理器完全看不到**——表现为前端连接莫名其妙断掉，后端日志里只有一条线程池的 `Exception in thread`。

**必须在异步任务里自己兜底**（2.1 已经这么写了）：

```java
/** 统一的错误下发:先尝试推 error 事件,失败说明连接已断,直接结束。 */
public void sendError(SseEmitter emitter, Exception e) {
    log.error("对话异常", e);
    StreamErrorEvent event = toErrorEvent(e);   // 见 04 文档 4.3
    try {
        emitter.send(SseEmitter.event().name("error").data(event));
        emitter.complete();
    } catch (Exception ignored) {
        emitter.completeWithError(e);           // 连接已断,直接终结
    }
}
```

### 3.2 `ThreadLocal` 全部失效

异步线程里这些东西**都是空的**：

| 失效的东西 | 后果 | 处理 |
|---|---|---|
| `UserContext`（登录用户） | 落库时 `creatorId` 为 null | Controller 里取出，作为参数传入 |
| `MDC`（日志 traceId） | 日志链路断开，排障时对不上 | Controller 里取出，异步线程开头 `MDC.put`，`finally` 里 `clear` |
| `RequestContextHolder` | 拿不到 request | 需要的信息在 Controller 里取好传进去 |

**规则：进入异步执行前把需要的上下文取出来显式传参；禁止在异步线程里读任何 `ThreadLocal`。**

### 3.3 `emitter.send()` 抛 `IOException` 是**正常路径**

用户关了浏览器标签页，下一次 `send` 会抛 `IOException: Broken pipe`。这**不是错误**，是探测到客户端断连的正常信号——`log.warn` + 释放资源即可，**不要 `log.error` 打全栈**，否则日志会被刷屏。

（心跳机制就是靠这个在 15 秒内发现断连的，见 `04` 文档 2.3。）

### 3.4 `complete()` 之后再 `send()` 抛 `IllegalStateException`

超时和异常回调可能已经悄悄 complete 了 emitter，而你的生成循环还在跑。用一个状态位守住：

```java
if (session.isClosed()) {
    log.debug("会话已关闭,丢弃增量, conversationId={}", conversationId);
    return;   // 生成循环下一轮会检查到 cancelled 并退出
}
```

`release()` 也要幂等（`04` 文档 4.4 已定）。

### 3.5 事务不能跨 `send`

`@Transactional` 方法里发 SSE = 数据库连接被占住整轮对话。**落库拆成前后两个短事务**，中间的生成过程无事务。规则已定在 `02_Hify模块内部分层（基准）.md` 6.2 / 6.3，此处只做交叉引用。

### 3.6 ⚠️ 前端**不能用原生 `EventSource`**

这是最容易到前端联调时才发现的一条。

浏览器原生 `EventSource` 有两个硬限制：

1. **只支持 GET**，不能带请求体。对话要传 `agentId` + `messages` + 配置，塞进 URL query 不现实（长度限制 + 编码地狱）；
2. **不能自定义请求头**，带不了 `Authorization`。

**方案：前端用 `fetch` + `ReadableStream` 手工解析 SSE**（或用 `@microsoft/fetch-event-source` 这个库封装）。

```ts
const res = await fetch('/api/chat/completions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify(payload),
  signal: abortController.signal,      // ★ 用户点「停止」时 abort,后端 onError 触发
});

const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
// 按 SSE 协议解析:空行分隔事件,event: / data: 前缀
```

**顺带一个好处**：`EventSource` 断线会**自动重连**——在对话场景是有害的（会重新触发一轮生成、重复扣 token）。`fetch` 不会自动重连，正是我们要的。

> **后端不受影响**：`SseEmitter` 产出的是标准 `text/event-stream`，`fetch` 侧照 SSE 协议解析即可。这条只约束前端选型。

### 3.7 优雅停机会掐断在途对话

发版时 Tomcat 关闭，在途的 SSE 连接会被直接断开，用户看到句子说到一半没了。

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

配合线程池的 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)`（`04` 文档 1.2 已配）。**30 秒兜不住 5 分钟的长对话**——剩下的靠前端：收到连接异常断开且未收到 `done` 事件时，提示「服务已更新，请重新发送」。这条记入 `CLAUDE.md` 待定项 4（变更与回滚约定）。

---

## 四、为什么不引 WebFlux

### 4.1 WebFlux 多解决的到底是什么

`SseEmitter` 和 WebFlux 的 `Flux<ServerSentEvent>` **都不占 Tomcat/Netty 的 IO 线程**。差别只有一处：

| | Spring MVC + `SseEmitter` | WebFlux + `Flux` |
|---|---|---|
| IO 线程 | 不占（Servlet 异步） | 不占（Netty event loop） |
| **产生内容期间** | **占一个业务线程，阻塞等上游** | **不占任何线程**，靠回调驱动 |

**WebFlux 的全部增量收益，就是消掉「等 token 时阻塞的那个业务线程」。**

### 4.2 但这个收益在本项目拿不到

要拿到它，**整条链路都必须非阻塞**——只要有一环阻塞，就等于在 event loop 上跑阻塞代码，而 event loop 线程只有 CPU 核数个（比如 4 个），阻塞掉几个就是**全站瘫痪**，比 MVC 严重得多。

逐环核对：

| 环节 | 一期实现 | 是否阻塞 | 换成非阻塞的代价 |
|---|---|:---:|---|
| 上游 LLM HTTP | JDK `HttpClient` 同步读流 | ❌ 阻塞 | 换 `sendAsync` / WebClient，编排改回调式 |
| 会话与消息落库 | **MyBatis-Plus** | ❌ 阻塞 | **换 R2DBC**——MyBatis-Plus 不支持响应式 |
| 事务 | `@Transactional` | ❌ 阻塞 | 换响应式事务管理器 |
| Redis | Lettuce | ✅ 本就支持响应式 | — |
| 向量检索 | 内存暴力计算 | CPU 密集 | 需 `publishOn(boundedElastic)` 隔离 |

**「换掉 MyBatis-Plus」= 推翻 `02_Hify技术选型（基准）.md` 的核心选型。** 为了一个当前用不上的并发能力，代价过大。

### 4.3 容量核算：64 够不够

| 指标 | 数值 | 来源 |
|---|---|---|
| 目标同时在线 | 50 人 | `03_Hify部署与运维（基准）.md` |
| 峰值并发 SSE | **3–5** | 同上（对话周期中位数约 90 秒，人多数时间在读和打字） |
| 业务线程池 max | **64** | `04` 文档 1.2 |
| 余量 | **约 13 倍** | |

即便按 `01_Hify代码组织（基准）.md` 第十节推演到 **3000 注册用户**，峰值并发 SSE 也只有 **100–150**。那时的正确动作是第五节的 Level 1 + Level 2，**仍然不需要 WebFlux**。

### 4.4 一个人开发的隐性成本

这几条不体现在性能指标里，但每天都在付：

| 项 | MVC | WebFlux |
|---|---|---|
| 异常堆栈 | 可读，能定位到业务代码行 | 大段 Reactor 内部帧，需要 `onOperatorDebug`（有性能代价） |
| 断点调试 | 正常 | 断点在 lambda 里，调用栈与执行栈不一致 |
| `ThreadLocal` / MDC | 可用（需手动传，见 3.2） | **不可用**，必须改用 Reactor Context |
| `@Transactional` | 直接用 | 需响应式事务，语义与用法都不同 |
| 排查资料 | 海量 | 少，且很多是错的 |
| 出问题时 | 能爬出来 | **爬不出来的概率显著更高** |

`02_Hify技术选型（基准）.md` 的第一原则是「熟悉度权重高于一切纸面指标——不熟悉的技术栈，问题不在学习成本，在遇到坑时能不能快速爬出来」。这条在这里直接适用。

### 4.5 反过来说：什么情况下 WebFlux 才是对的

诚实地列出来——如果本项目是下面这些形态，答案会反过来：

| 场景 | 为什么 WebFlux 更合适 |
|---|---|
| 几千条常驻长连接（IM、推送网关、协作编辑） | 每连接一个线程的模型撑不住，线程栈内存先爆 |
| 纯网关 / 转发型服务，几乎不碰数据库 | 没有 MyBatis-Plus 这个阻塞点，全链路非阻塞天然成立 |
| 团队已经全员熟悉响应式 | 4.4 的成本不存在 |

Hify 三条都不沾。

---

## 五、真到扛不住那天：三级升级路径

**关键结论：Level 2 就能覆盖到几百并发，而 Level 3（WebFlux）大概率永远不需要。**

| 级别 | 动作 | 能撑到 | 代码改动 | 触发条件 |
|---|---|---|---|---|
| **L1** | 调线程池（core 64 / max 128）+ 加 JVM 堆 | ~120 并发 | **零** | 峰值并发 SSE > 50 |
| **L2** | **上游 HTTP 换非阻塞**（`HttpClient.sendAsync` + `Flow.Subscriber`），业务线程不再阻塞等 token | **几百** | **只改 `provider/client` 一个包** | 峰值并发 SSE > 120 |
| **L2'** | 多实例 + Nginx 负载均衡 | 线性扩展 | **零**（状态已外置） | 单实例 CPU 持续 > 70% |
| **L3** | WebFlux 全链路 + R2DBC | 数千 | **推翻技术栈** | 峰值并发 SSE 持续 > 500 且 L1/L2/L2' 都用尽 |

### L2 值得展开：不引 WebFlux 也能非阻塞

这是最容易被忽略的一点——**「非阻塞 HTTP 客户端」和「响应式 Web 框架」是两件独立的事**。

JDK 11+ 的 `HttpClient.sendAsync()` 配合 `Flow.Subscriber` 就是非阻塞的：token 到达时由 HttpClient 的 IO 线程回调你的 `onNext`，你在回调里直接 `emitter.send()`，**全程不需要一个业务线程挂在那里等**。

```java
/** L2 形态:上游流由 HttpClient 的 IO 线程回调驱动,不占用业务线程池。 */
httpClient.sendAsync(request, HttpResponse.BodyHandlers.fromLineSubscriber(
        new SseLineSubscriber(emitter, timeoutConfig)));
```

Controller 仍然返回 `SseEmitter`，Spring MVC 一行不改，MyBatis-Plus 一行不改。**改动范围就是 `hify-provider` 的 `client` 包**——这正是 `01_Hify代码组织（基准）.md` 第十节第 2 项列的「上游 LLM 的 HTTP 客户端换非阻塞」，改动封闭在一个包内。

代价是编排代码变成回调式，可读性下降——所以不是现在做，是并发真的上去了再做。

> 如果那时想用 `WebClient` 而不是裸 `HttpClient`：可以**只引 `spring-boot-starter-webflux` 拿 `WebClient`，Web 层继续用 MVC**。两个 starter 同时存在时 Spring Boot 以 MVC 为准（`spring-boot-starter-web` 优先），不会把应用变成响应式应用。这是常见做法，不构成「引入 WebFlux」。

---

## 六、一句话结论

> **Spring MVC 的 `SseEmitter` 已经解决了长连接占用 Tomcat 线程的问题；WebFlux 多解决的「业务线程也不阻塞」在 MyBatis-Plus 仍是阻塞式的前提下拿不到，要拿到就得推翻技术选型。**
> **50 人规模下 64 个业务线程有 13 倍余量；真扛不住时，先调参数、再把上游 HTTP 换成非阻塞（只动 `provider/client` 一个包），这两级就能覆盖到几百并发——WebFlux 大概率永远轮不到。**
> **本篇最实际的一条产出是 3.6：前端不能用原生 `EventSource`（不支持 POST、不能带 Authorization、且会自动重连导致重复生成），必须用 `fetch` + `ReadableStream`。**

---

## 七、需要拍板的

| # | 事项 | 倾向 |
|---|---|---|
| 1 | **不引 WebFlux** | 采纳。升级路径见第五节，L3 保留但不预备 |
| 2 | **前端用 `fetch` + `ReadableStream`，不用 `EventSource`** | 采纳。是否引 `@microsoft/fetch-event-source` 由前端定 |
| 3 | **SSE 事件命名 `message` / `error` / `done` + 注释帧心跳** | 采纳，需与前端约定后写进接口文档 |
| 4 | **优雅停机 30 秒兜不住长对话** | 前端提示「服务已更新，请重新发送」；记入 `CLAUDE.md` 待定项 4 |
| 5 | **异步线程的异常兜底统一放 `SseSessionManager.sendError`** | 采纳。`@RestControllerAdvice` 对异步线程无效（3.1） |
