# Hify 技术选型（基准）

> 📌 **本文档是 Hify 技术栈的唯一依据（source of truth）**，如与其它任何文档冲突，**以本文档为准**。
> 功能范围见同目录 `01_Hify产品决策（基准）.md`。
> 决策日期：2026-08-10

---

## 一、选型原则

> **技术选型不是选最好的技术，是选最匹配当前约束的技术。**

三条约束决定一切：

| 约束 | 对技术选型的含义 |
|------|------|
| **一个人开发** | 开发效率第一。任何需要「自己攒一套框架层」的方案都是负债。 |
| **面向团队内部 20–50 人** | 峰值 3–5 QPS，性能不是瓶颈；瓶颈是 LLM 长连接（SSE）管理。 |
| **本地部署** | 数据不出内网，容器越少越好，每多一个中间件就多一份运维。 |

---

## 二、最终技术栈

| 层 | 选型 | 一句话理由 |
|----|------|------|
| **后端** | Spring Boot 3.x + MyBatis-Plus + MySQL 8.x + Redis 7.x | 工程化成熟，一个人能快速交付完整系统。 |
| **前端** | Vue 3 + TypeScript + Element Plus | 管理后台场景成熟度最高。 |
| **容器化** | Docker + Docker Compose | 最稳、最简。 |

**明确决定：不拆 Python 服务。** 一个人一套技术栈，不分裂。

---

## 三、决策理由

### 1. 最熟这个栈，一个人开发效率第一

一个人的项目，「不熟悉的技术栈」不是学习成本问题，是**遇到坑时能不能快速爬出来**的问题。熟悉度的权重高于一切纸面指标。

### 2. Java 的 AI SDK 够用

- OpenAI、Claude 均提供**官方 Java SDK**（Anthropic 官方 SDK 覆盖 Java、Go、Python、TypeScript 等七种语言，Java 是一等公民）。
- **RAG 的向量化直接调 API 即可**，不需要 LangChain 这类 Python 专属框架。
- MCP 是**协议**不是库（stdio / HTTP + JSON-RPC），有官方 Java SDK，Spring AI 1.1 已完整集成——「区别于聊天机器人的关键能力」不构成 Python 的护城河。

### 3. 一个人一套技术栈，不分裂

引入 Python 边车服务意味着：两套依赖管理、两套部署单元、两套调试链路、两套排障经验。对一个人来说，跨栈的心智切换成本超过它能带来的生态收益。

### 结论

> AI 生态的劣势确实存在，但**工程化能力和技术栈统一性的优势更大**。

---

## 四、备选方案与否决理由

| 方案 | 否决理由 |
|------|------|
| **Go + React** | 优势（运维轻、高并发）在 3–5 QPS 场景下毫无价值；劣势（开发慢、AI 生态最薄、缺 ORM 与框架层抽象）全部命中。适合高并发基础设施，不适合一人开发的内部平台。 |
| **Python FastAPI + React** | AI 生态确实最强，但工程化能力差距大（见下节），且引入不熟悉的栈。**AI 特有代码只占一期工作量的 20–30%，其余 70% 是标准 Web 工程**——Python 的优势作用在少数派上，Spring 的优势作用在多数派上。 |

---

## 五、补充：Spring Boot vs FastAPI 工程化能力要点

> 以下为支撑上述决策的技术依据，**不改变、不扩展**第二节的选型结论。

先澄清一个前提：**Spring Boot 是应用框架，FastAPI 是 Web 框架。** 公平对比是「Spring Boot vs FastAPI + SQLAlchemy + Alembic + pydantic-settings + APScheduler + 自写胶水」。差距的本质不是能不能做到，而是**这层胶水谁写、谁维护**。

### 事务管理：质的差距

| Spring 能力 | FastAPI / SQLAlchemy 对应 | 差距 |
|------|------|------|
| 7 种传播行为（`REQUIRED` / `REQUIRES_NEW` / `NESTED` …） | 无声明式机制 | **架构级缺失** |
| 声明式嵌套，Service 方法自由组合 | 手动把 `session` 传给每个函数 | 调用链每加一层多一个参数 |
| `REQUIRES_NEW` 独立事务 | 手动新建 session + 独立 commit | 生命周期与异常自管 |
| `@TransactionalEventListener(AFTER_COMMIT)` | 全局 `after_commit` 事件 | 拿不到业务上下文 |
| `rollbackFor` 精确回滚规则 | 手写 try/except | — |

**对 Hify 的分水岭场景**：审计日志——主流程失败也要保留。Spring 里是加个 `@Transactional(propagation = REQUIRES_NEW)` 的事；FastAPI 里要开第二个 session、自管提交与异常，并保证主事务回滚不影响它。

**Spring 侧需知道的两个坑**（属于「知道就没事」，非架构缺失）：
1. 默认只回滚 `RuntimeException` / `Error`，受检异常不回滚——需显式写 `rollbackFor = Exception.class`。
2. 同类内自调用 `this.xxx()` 走不到 AOP 代理，事务注解失效。

### 异常处理：差距比想象中小

- **全局异常处理**：两边都支持按类型分派、沿继承链匹配，基本打平。
- **参数校验**：Pydantic 的错误信息与 OpenAPI 生成质量**优于** Spring，这一项 FastAPI 赢。
- **真正的差距在数据访问异常翻译**：Spring 的 `DataAccessException` 体系把各数据库厂商错误码统一翻译成 `DuplicateKeyException`、`DataIntegrityViolationException`、`OptimisticLockingFailureException` 等语义化异常，厂商无关；SQLAlchemy 只给到 `IntegrityError`，要区分唯一键冲突还是外键失败得解析厂商错误码或匹配错误消息字符串——**这是脆的**。

### 其它工程化能力

| 能力 | Spring Boot | FastAPI 侧 |
|------|------|------|
| 依赖注入 | 应用级容器，HTTP / 定时任务 / CLI 共用 | `Depends` 仅**请求作用域**，非 HTTP 场景用不了 |
| 定时任务 | `@Scheduled` 一行注解 | 无内置，引 APScheduler 或另起进程 |
| AOP 横切 | 日志 / 审计 / 重试集中织入 | 装饰器，逐个方法贴 |
| 配置管理 | `@ConfigurationProperties` + Profile | pydantic-settings 可用，无 Profile 机制 |
| 监控 | Actuator 开箱即用 | 自行接 prometheus instrumentator |
| 重构安全 | 编译期类型检查，IDE 重命名可靠 | 运行期，mypy 可选且常与 SQLAlchemy 冲突 |

### 一个生产风险

FastAPI 的 async 模型下，在 `async def` 里误调阻塞函数（仅有同步版本的 SDK、一次 `requests.get`）会**卡死整个事件循环**，所有请求一起挂。Spring Boot 一线程一请求的模型笨，但笨得安全。对「SSE 长连接是主要瓶颈」的 Hify 而言，这一点权重不低。

### 综合判断

| 维度 | 差距 |
|------|------|
| 事务管理 | **大**（质的差距） |
| 异常处理 | **小**（量的差距，主要在异常翻译） |
| 参数校验 | FastAPI 领先 |
| DI / 定时 / AOP / 监控 | 中等偏大 |
| 长期可维护性 | **大**，随代码量放大 |

**结论：中等偏大，且随项目规模增长而放大。** 关键不在「FastAPI 做不到」，而在做到的方式是**自己攒一套**——自攒的框架层没有文档、没有社区、没人替你测。

---

## 六、明确接受的代价

选 Spring Boot 就是接受这三件事，**不再讨论**：

| 代价 | 应对 |
|------|------|
| **新模型特性支持滞后** — 各家出新能力，Python SDK 首发，Java 侧等数月 | 内部工具场景可接受；能力落后一代不影响一期主线 |
| **遇到只有 Python 有的库要自己实现** — 如复杂 PDF 版式解析、扫描件识别 | 一期只做 TXT 已规避大部分；真需要时在 Java 侧实现或砍掉该功能 |
| **社区答案少** — Spring AI / LangChain4j 的踩坑资料比 Python 少一个数量级 | 靠读源码解决；这是选定栈的固有成本 |

> **注**：曾考虑「起一个 Python 边车服务只负责 AI 部分」作为逃生口，**已明确否决**——一个人一套技术栈，不分裂。若将来真撞上非 Python 不可的需求，在 Java 侧实现或砍掉该功能，不引入第二套栈。

---

## 七、Java 侧 AI 框架选择（待定）

一期需在两者之间择一，**均为 Java 原生，不违反「不依赖 Python 专属框架」的约束**：

| 框架 | 特点 | 适用 |
|------|------|------|
| **Spring AI** | 1.0 于 2025-05 GA，1.1 带来完整 MCP 集成、20+ 模型后端、Advisors API | 要 Spring 原生集成与可观测性 |
| **LangChain4j** | 1.0 同期稳定，20+ 模型提供商、30+ 向量存储；RAG 工具链（切分器 / 嵌入存储 / 检索器 / 摄取器分离）更成熟 | 要最广的提供商与向量库覆盖 |

**倾向**：一期模型接入面窄（本地 + 少量云 API），可考虑**先不引框架**，直接用官方 SDK + 一套 OpenAI 兼容客户端。理由：当前主流供应商（Ollama、vLLM、DeepSeek、通义、Kimi 等）普遍提供 OpenAI 兼容端点，一套配置化客户端即可覆盖绝大多数，比引入框架更轻。等接入面变宽或 RAG 复杂度上升再引。

---

## 八、待定项

| # | 待定项 | 说明 |
|---|--------|------|
| 1 | **向量存储方案** | **MySQL 8.x 无原生向量类型**（`VECTOR` 为 9.0 引入）。需决定：加向量库容器（Chroma / Qdrant），还是向量存 MySQL JSON 字段、检索时内存暴力计算。倾向后者——一期 TXT 场景分段量约几百至几千条，毫秒级即可返回，省一个容器和一套运维。详见 `01_Hify产品决策（基准）.md` 第六节。 |
| 2 | **Java 侧 AI 框架** | 见上节，一期可先不引。 |

---

## 附：部署与运维预期

| 项 | 要求 |
|----|------|
| **部署** | Docker Compose 一键启动，单机部署 |
| **容量** | 20–50 人同时在线，峰值 3–5 QPS，Spring Boot 单实例轻松应对 |
| **SSE** | 用 `SseEmitter`（勿用阻塞式 `@ResponseBody`）；设 60 秒超时防僵尸连接；Nginx 配 `proxy_buffering off`，否则流式被攒批、失去打字机效果 |
| **缓存** | 只缓存变更极少的配置（Redis）；不缓存 LLM 响应；对话历史直读库 |
| **持久化** | MySQL / 向量数据 / 上传文档必须挂 volume，**容器内不存任何数据** |
| **JVM** | `-Xmx512m`，防容器 OOM Kill |
| **监控** | 起步 Spring Boot Actuator + 日志；后期接 Prometheus + Grafana |
| **扩容** | 一期单机；架构做模块化设计，不堵死后续拆分的路 |
