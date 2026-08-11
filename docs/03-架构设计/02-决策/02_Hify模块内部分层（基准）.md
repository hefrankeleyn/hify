# Hify 模块内部分层（基准）

> 📌 **本文档是 Hify 各模块内部包结构与分层职责的唯一依据（source of truth）**，如与其它任何文档冲突，**以本文档为准**。
> 模块划分、依赖方向、Maven 组织见同目录 `01_Hify代码组织（基准）.md`。
> **本文档取代** `01_Hify代码组织（基准）.md` 第四节，以及过程稿 `../01-过程/02_Hify代码组织规范.md` 的第二、三节——取代范围见第二节。
> 决策日期：2026-08-11

---

## 一、最终决策

每个业务模块（`hify-provider` / `hify-agent` / `hify-chat` / `hify-mcp` / `hify-workflow` / `hify-knowledge`）内部采用**扁平九包结构**：

```
hify-{module}/src/main/java/com/hify/{module}/
├── controller/        # REST 接口
├── service/           # 业务逻辑接口   ← 同时是本模块的对外契约
├── service/impl/      # 业务逻辑实现
├── mapper/            # MyBatis-Plus Mapper
├── entity/            # 数据库实体
├── dto/               # 请求 / 响应对象
├── config/            # 配置类
├── exception/         # 模块级错误码与自定义异常
└── constant/          # 模块级常量
```

**不再有 `api` / `internal` 两层顶层包。** 模块对外暴露什么，由 `service` 包（接口）承担；实现细节的封闭，由 `service/impl`、`mapper`、`entity` 三个包的**不可跨模块引用**保证（第五节）。

### 1.1 相对上一版方案的变化

| 项 | 上一版（`api` / `internal`） | **本决策** |
|---|---|---|
| 顶层包 | `api` + `internal` 两层，业务包在 `internal` 下 | **扁平九包，无中间层** |
| 对外契约载体 | `api` 包 | **`service` 包（接口）** |
| Service 接口 | 不写（单实现的接口是噪音） | **必写**，理由见 1.2 |
| 对外 DTO | `api/dto` | **`dto` 包，按命名区分**（4.2） |
| Web 入参出参 | `internal/web/dto` | **同上，合并进 `dto`** |
| 对象转换 | `internal/converter` 包 | **无此包**，转换放 DTO 静态工厂（4.4） |
| 包路径深度 | `com.hify.agent.internal.service.AgentService` | `com.hify.agent.service.AgentService` |

### 1.2 为什么这一版 `service` + `service/impl` 是必要的

上一版过程稿里写过「单实现的接口是纯噪音，`internal.service` 一律写类」。**那个结论成立的前提是 `api` 包已经承担了对外契约**——有 `api` 在，`internal.service` 就纯粹是内部实现，再套一层接口确实是白拆。

去掉 `api` 包之后这个前提没了。此时如果 `service` 包里放的是实现类，模块就**没有任何一个地方能表达"我对外提供什么"**——`hify-chat` 依赖 `hify-agent` 后，看到的是 `AgentService` 这个具体类的全部 public 方法，包括 `delete()`、`updateStatus()` 这些管理面操作。

所以在扁平结构下，`service`（接口）/ `service/impl`（实现）的拆分**不是分层洁癖，它就是模块边界本身**：

```
com.hify.agent.service.*          ← 契约,允许被别的模块引用
com.hify.agent.service.impl.*     ← 实现,禁止被别的模块引用
com.hify.agent.mapper/entity/...  ← 实现,禁止被别的模块引用
```

这个映射还有一个工程上的便利：ArchUnit 里 `com.hify.*.service`（不带 `..`）**精确匹配接口包而不包含 `service.impl` 子包**，一条规则就能把边界钉死（第八节）。

---

## 二、取代关系（避免两份基准打架）

| 文档 | 状态 |
|---|---|
| `01_Hify代码组织（基准）.md` **第一、二、三、五、六、七、八、九、十节** | ✅ **继续有效**（模块划分、四层依赖方向、pom 组织、`hify-common` 边界、四条硬规矩、分级设计、模块封顶、Flyway / 扫描 / 构建约定） |
| `01_Hify代码组织（基准）.md` **第四节「模块内部结构：`api` 与 `internal`」** | ❌ **被本文档取代**，整节作废（含 4.4 的 ArchUnit 规则，以本文档第八节为准） |
| `01_Hify代码组织（基准）.md` **9.2「包名与命名」中「模块内顶层子包只有 api 和 internal」一行** | ❌ **被本文档第三节取代** |
| `../01-过程/02_Hify代码组织规范.md` **第二、三节** | ❌ 被本文档第三、四节取代 |
| `../01-过程/02_Hify代码组织规范.md` **第五、六、七节**（事务、跨模块调用、异常日志） | ✅ 继续有效，包路径按本文档换算（`internal.service` → `service.impl`） |
| `../01-过程/02_Hify代码组织规范.md` **第四节代码模板** | ⚠️ 包声明需按本文档换算，其余有效 |

> `01_Hify代码组织（基准）.md` 需要按上表打补丁。**在补丁落地前，第四节以本文档为准。**

---

## 三、九个包的职责边界

### 3.1 职责与依赖表

**「允许依赖」列之外的引用一律违规。** 所有包都可依赖 `hify-common` 与本模块的 `constant` / `exception`，不再重复列出。

| 包 | 唯一职责 | 允许依赖 | **禁止出现在这里的东西** | 跨模块可见 |
|---|---|---|---|---|
| `controller` | HTTP 协议适配：收参、`@Valid` 校验、调 service、包 `R<T>` | `service`、`dto` | 业务判断、`@Transactional`、Entity、Mapper、`service.impl` | ❌ |
| `service` | **声明**本模块能干什么（接口，无实现） | `dto` | 任何实现代码、Entity、Mapper、`Page`/`Wrapper`、HTTP 概念 | ✅ **仅此包 + `dto`** |
| `service/impl` | 业务规则 + 事务边界 + 编排 | `mapper`、`entity`、`dto`、`config`、**其它模块的 `service`** | HTTP 概念（`HttpServletRequest`、`R<T>`）、手写 SQL | ❌ |
| `mapper` | 单表 CRUD 与查询 | `entity` | 业务逻辑、`@Transactional`、跨模块 JOIN | ❌ |
| `entity` | 与数据库表一一对应 | 无 | 业务方法、对象引用字段、非数据库字段 | ❌ |
| `dto` | 数据传输对象（HTTP 入参出参 + 跨模块契约对象） | 无 | 业务方法、MyBatis 注解、注入任何 Bean | ✅ |
| `config` | `@ConfigurationProperties` + 本模块 Bean 装配 | 本模块任意包 | 业务逻辑 | ❌ |
| `exception` | 模块错误码枚举（+ 极少数自定义异常） | 无 | 业务逻辑 | ✅（错误码需要被前端识别） |
| `constant` | 模块级常量与枚举 | 无 | 业务逻辑、可变状态 | ✅ |

### 3.2 类的命名与位置（无歧义表）

| 角色 | 包 | 类名格式 | 示例 |
|---|---|---|---|
| 控制器 | `controller` | `XxxController` | `AgentController` |
| 业务接口 | `service` | `XxxService` | `AgentService` |
| 业务实现 | `service.impl` | `XxxServiceImpl` | `AgentServiceImpl` |
| 数据访问 | `mapper` | `XxxMapper extends BaseMapper<XxxEntity>` | `AgentMapper` |
| 表映射对象 | `entity` | `XxxEntity` | `AgentEntity`、`ChatMessageEntity` |
| HTTP 入参 | `dto` | `Xxx{Create\|Update\|Query}Request` | `AgentCreateRequest` |
| HTTP 出参 | `dto` | `Xxx{\|Detail}Response` | `AgentResponse` |
| **跨模块契约对象** | `dto` | **业务名词，无 Request/Response 后缀** | `AgentConfig`、`RetrievedChunk`、`ToolSpec` |
| 配置属性 | `config` | `XxxProperties` | `ChatProperties` |
| 错误码 | `exception` | `XxxErrorCode`（枚举 implements `ErrorCode`） | `AgentErrorCode` |
| 常量 | `constant` | `XxxConstant` / `XxxEnum` | `AgentStatusConstant` |

### 3.3 Entity 与 DTO 的强制规则

**Entity（五条）**：

1. 每张表必备五字段：`id` / `create_time` / `update_time` / `deleted` / `creator_id`；
2. `create_time` / `update_time` / `creator_id` 由 `MetaObjectHandler` 自动填充，**代码里禁止手工 set**；
3. 逻辑删除统一 `@TableLogic`，业务代码禁止手写 `deleted = 0`；
4. **禁止对象引用字段**（不许有 `private ProviderEntity provider`），跨模块只存 id、不建外键；
5. JSON 列用 `String` 存，序列化在 DTO 静态工厂里做，**不挂 TypeHandler**。

**DTO（三条）**：

1. `*Request` 加 Jakarta Validation 注解，校验在 DTO 上声明，**不在 Controller 里写 if 判空**；
2. **跨模块契约对象用 `@Value` + `@Builder` 做成不可变**，`*Request` / `*Response` 用 `@Data`；
3. `dto` 包里禁止出现业务方法——**唯一例外是 4.4 定义的 `from` / `to` 静态转换方法**。

---

## 四、这个结构的三个缺口与补法

扁平九包比 `api`/`internal` 简单，代价是三处边界失去了目录层面的载体。逐个补上——**三条补法都不新增顶层包，不动第一节的目录树**。

### 4.1 缺口一：模块对外暴露面过宽 → `@ModuleApi` 标记

`service` 包里既有对外契约（`AgentService.getConfig()`），也有纯内部的业务接口。全部对外可见意味着 `chat` 能注入 `AgentService` 并调 `delete()`。

**决策：在 `hify-common` 定义一个标记注解，`service` 包里对外的接口必须标注它；跨模块只允许注入带此注解的接口。**

```java
package com.hify.common.annotation;

/**
 * 模块对外契约标记。
 * <p>标注在 {@code com.hify.{module}.service} 包的接口上，表示该接口允许被其它模块注入调用。
 * <p>未标注的 service 接口视为模块内部接口，跨模块引用将在 ArchUnit 测试中失败。
 * <p><b>标注前先问一句：这个方法是调用方真的需要，还是内部接口的复制？契约越薄，模块越独立。</b>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModuleApi {

    /** 说明这个契约给谁用、解决什么问题，便于日后判断能否收窄 */
    String value() default "";
}
```

```java
package com.hify.agent.service;

/**
 * Agent 对外查询服务。
 * <p>只暴露编排层真正需要的能力；管理面的增删改查在 {@link AgentService} 中，不对外。
 */
@ModuleApi("供 chat / workflow 装配 Agent 运行时配置")
public interface AgentQueryService {

    /**
     * 按 id 查询 Agent 运行时配置。
     *
     * @param agentId Agent 主键 id，不能为 null
     * @return Agent 运行时配置，永不返回 null
     * @throws BizException Agent 不存在或已停用时抛出
     */
    AgentConfig getConfig(Long agentId);
}
```

**成本**：一个注解类 + 每个对外接口一行 + 一条 ArchUnit 规则（第八节）。
**收益**：保住「契约越薄越好拆」这条纪律——没有它，`service` 包会逐渐变成模块的全量 public API。

> **可退回选项**：不加注解，视 `service` 包全部接口为对外，仅靠 pom 依赖矩阵限制爆炸半径。此时 4.1 作废、第八节对应规则删掉，其余不变。**不推荐**，但如果注解被认为是过度设计，这是唯一可接受的退路——**不接受「靠自觉别调不该调的方法」**。

### 4.2 缺口二：`dto` 包混装两类对象 → 按命名切分

`dto` 里同时住着 HTTP 层对象和跨模块契约对象。跨模块传 `*Request` 会把两个模块通过 HTTP 层结构焊在一起。

**决策：按类名后缀区分，不新增子包。**

| 类名 | 性质 | 允许出现在 |
|---|---|---|
| `*Request` / `*Response` | HTTP 层专用 | `controller` 方法签名、`service` 接口签名（**仅限本模块内调用**） |
| 业务名词（`AgentConfig`、`RetrievedChunk`、`ToolSpec`） | 跨模块契约 | 任意 |

**两条硬规则**：

1. **标注了 `@ModuleApi` 的接口，方法签名里禁止出现 `*Request` / `*Response` / `*Entity`**（可 ArchUnit 检查）；
2. 业务 DTO 一律留在各模块自己的 `dto` 包，**禁止提到 `hify-common`**——common 里只放 `R<T>` / `PageQuery` / `PageResult` 这类无业务含义的对象。把业务 DTO 提到 common 是最隐蔽的耦合后门，pom 拦不住（谁都能依赖 common）。

### 4.3 缺口三：厚模块塞不进九个包 → 追加模块专属包

九包骨架覆盖 CRUD 型代码。但 `chat` 的 SSE 生命周期管理、`workflow` 的节点执行器、`provider` 的 LLM 客户端、`knowledge` 的文档处理链**都不是业务逻辑也不是数据访问**，硬塞进 `service/impl` 会让它变成三十个类的垃圾桶。

**决策：九包是通用骨架，下表列出的模块专属包作为 `{module}` 下的同级包追加。白名单封闭，不在表里的包不许建。**

| 模块 | 追加的包 | 职责 | 硬规则 |
|---|---|---|---|
| `provider` | `client` | LLM HTTP 客户端：请求构造、SSE 解析、重试、错误映射 | 禁止注入 Mapper；禁止 `@Transactional`；外部异常必须转 `BizException` 或结果对象，不许原样外抛 |
| `mcp` | `client` | MCP 协议客户端、工具清单发现与缓存兜底 | 同上 |
| `knowledge` | `pipeline` | 解析 → 分块 → 向量化 → 入库 | 每步一个独立类用接口串联；禁止 `@Transactional`（长耗时） |
| `knowledge` | `vector` | ★ `VectorStore` 接口 + `InMemoryVectorStore` | 接口方法不许出现 MyBatis / MySQL 概念 |
| `knowledge` | `storage` | ★ `FileStorage` 接口 + `LocalFileStorage` | 出现第二个使用方时整体上移 `hify-common` |
| `chat` | `sse` | `SseEmitter` 创建、心跳、超时、断连回调、取消上游请求 | 禁止业务逻辑；禁止 `@Transactional` |
| `chat` | `runtime` | 对话编排：装上下文 → 注入 RAG → 调模型 → 工具循环 | **禁止 `@Transactional`**（见 6.3）；禁止注入 Mapper，落库一律过 `service` |
| `workflow` | `definition` | JSON → `Graph` 解析与校验 | 纯函数式，无可变状态 |
| `workflow` | `executor` | `NodeExecutor` 接口 + 各节点实现 + 图执行器 | 新增节点类型只允许「加一个实现类」，禁止改分派逻辑 |
| `chat` `workflow` `knowledge` `mcp` | `domain` | 不落库的领域对象（`ChatContext` / `Graph` / `Segment` / `ToolResult`） | 禁止 MyBatis 注解；禁止注入 Bean；**可以有业务方法** |

★ 两处是预期会被替换的实现点（换向量库、换对象存储），换实现时不改调用方。

**专属包的跨模块可见性：一律 ❌ 不可见**，唯一例外是 `domain` 包里被 `@ModuleApi` 接口用作参数/返回值的类（如 `mcp.domain.ToolResult`）。

> `agent` 模块不追加任何包——它是纯配置 CRUD，九包足够。

### 4.4 没有 `converter` 包，转换代码放哪

**决策：作为静态工厂方法放在目标 DTO 上；Entity 侧不写转换。**

```java
package com.hify.agent.dto;

/**
 * Agent 列表 / 详情响应对象。
 */
@Data
public class AgentResponse {

    /** Agent 主键 id */
    private Long id;

    /** Agent 名称 */
    private String name;

    /** 所选模型名 */
    private String modelName;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /**
     * 由数据库实体构造响应对象。
     * <p>只搬字段，不做任何业务判断——需要判断的属于 service 层。
     *
     * @param entity 数据库实体，不能为 null
     * @return 响应对象
     */
    public static AgentResponse from(AgentEntity entity) {
        AgentResponse response = new AgentResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setModelName(entity.getModelName());
        response.setStatus(entity.getStatus());
        return response;
    }
}
```

**三条规则**：

1. **方向固定**：`XxxResponse.from(entity)`、`AgentConfig.from(entity)`、`request.toEntity()`。目标类持有转换方法，**Entity 类里不写任何转换方法**（否则 `entity` 包会反向依赖 `dto`）；
2. 转换方法里**禁止业务判断、禁止 IO、禁止注入 Bean**；「status 为 0 时抛异常」属于 service；
3. 一期不引 MapStruct（待确认项 3）。列表转换写 `list.stream().map(AgentResponse::from).toList()`。

---

## 五、跨模块调用规则

### 5.1 唯一合法形态

```java
package com.hify.chat.runtime;

/**
 * 对话编排器。
 * <p>本类不持有事务，落库通过调用本模块 service 完成（见 6.3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRuntime {

    /* ↓ 跨模块依赖:全部来自对方 service 包、全部是标了 @ModuleApi 的接口、全部构造器注入 */
    private final AgentQueryService agentQueryService;    // com.hify.agent.service
    private final ChatModelClientFactory clientFactory;   // com.hify.provider.service
    private final RetrievalService retrievalService;      // com.hify.knowledge.service
    private final ToolExecutor toolExecutor;              // com.hify.mcp.service

    /* ↓ 本模块依赖 */
    private final ChatMessageService chatMessageService;  // com.hify.chat.service
}
```

### 5.2 五条规则

| # | 规则 | 判据 |
|---|---|---|
| 1 | 跨模块只能引用对方的 **`service`（接口层）+ `dto` + `constant` + `exception`** | 出现 `import com.hify.{他}.{controller\|service.impl\|mapper\|entity\|config\|client\|runtime\|...}` |
| 2 | 跨模块注入的接口必须带 `@ModuleApi` | 注入类型上无该注解 |
| 3 | 依赖方向按 `01_Hify代码组织（基准）.md` 3.3 的矩阵，**只能向下、同层禁止** | pom 里出现矩阵外的 `hify-*` 依赖 |
| 4 | 跨模块**只传 id 与对方 `dto`**，禁止传 Entity / `*Request` / `Page` / `Wrapper` / `SseEmitter` | 方法签名 |
| 5 | 同步调用、构造器注入。一期不引 Spring 事件、不引 MQ | 出现 `ApplicationEventPublisher` |

> 规则 5 的唯一例外：主流程不该被它拖慢、也不该被它失败影响的旁路（用量统计、审计日志），且必须用 `@TransactionalEventListener`。

### 5.3 关联关系只存 id，不做存在性校验

Agent 绑定模型 / 知识库 / MCP 工具，**只存 id，管理面保存时不校验对方是否存在**。

原因：若 `agent` 保存时要校验知识库和 MCP 工具存在，就需要 `agent → knowledge`、`agent → mcp` 两条同层依赖，直接违规；三个校验要么都做要么都不做，结论是都不做。

替代方案（三条一起）：

1. 前端下拉列表分别调各模块自己的 REST 接口，用户只能选到存在的对象；
2. 存在性校验**推迟到运行时由 `chat` 执行**——`chat` 依赖三者都是合法的向下依赖；模型被停用 / 知识库被删，表现为对话时一条明确错误；
3. **删除一律做「停用」（`status = 0`），不物理删除，不做反向引用检查。** 需要「还有 3 个 Agent 在用」这类影响面提示时，由前端调 `GET /api/agents?providerId=x` 在 UI 层组合。

> 通则：**跨模块的反向查询上移到前端解决，后端不建反向依赖。** 这条能挡掉大部分循环依赖。

### 5.4 下层需要上层的信息 → 让上层传进来

`mcp` 执行工具时若需要「当前是哪个会话」，**禁止反向查 `chat`**。在 `mcp.dto` 定义无业务含义的上下文载体，由调用方填：

```java
/** 工具调用上下文:调用方需要工具知道什么就传什么,mcp 模块绝不反向获取 */
@Value
@Builder
public class ToolCallContext {
    /** 调用来源标识,如 "chat:{conversationId}" / "workflow:{runId}",仅用于日志追踪 */
    String traceRef;
    /** 当前用户 id */
    Long userId;
}
```

### 5.5 跨模块调用失败必须显式处理

调用其它模块的 `@ModuleApi` 接口时，**禁止不处理异常直接向上抛**：

| 被调方 | 期望行为 | 代码形态 |
|---|---|---|
| `provider`（LLM） | **明确报错推给前端**，不静默失败、不无限等待 | 捕获后转 `BizException`，通过 SSE 的 error 事件下发 |
| `mcp`（工具） | **不中断对话**：失败作为工具结果交回模型 | 捕获后构造 `ToolResult.failure(msg)` 继续循环 |
| `knowledge`（检索） | **降级为不注入知识库**，对话仍可用 | 捕获后 `log.warn` + 返回空列表，主流程继续 |

---

## 六、事务规则

### 6.1 事务边界只有一处

**`@Transactional` 只允许出现在 `service.impl` 包的 public 方法上。** 其它任何包（含 `controller`、`runtime`、`executor`、`pipeline`、`client`、`sse`）出现它都是错的。

写方法一律 `@Transactional(rollbackFor = Exception.class)`，只读方法不加。

### 6.2 事务方法内禁止的四件事

| 禁止 | 原因 |
|---|---|
| 调用 LLM / MCP / 任何 HTTP | 远程调用耗时以秒计，长时间占住数据库连接 |
| 调用其它模块 `@ModuleApi` 的**写**方法 | 跨模块共享事务，把两个模块在数据库层焊死 |
| `Thread.sleep` / 等待锁 / 等待 Future | 同第一条 |
| 创建 `SseEmitter` 或向其 `send` | 流式输出生命周期以分钟计 |

### 6.3 跨模块不共享事务 + 长流程拆短事务

**跨模块调用，被调方自己管事务；调用方不指望对方能被自己回滚。** 模块对外接口要么是只读查询，要么自身是完整事务单元。跨模块写操作若需要「一起成功或一起失败」，说明模块划分错了，应当合并模块。

对话与工作流是分钟级长流程，**整体不许包在一个事务里**：

```
用户发起对话
  ├─ [短事务 1] 创建会话（首轮）+ 落库用户消息       ← service.impl,毫秒级
  ├─ (无事务)   RAG 检索 → 流式调模型 → 工具调用循环  ← runtime 包,秒~分钟级
  └─ [短事务 2] 落库助手消息 + 更新会话时间          ← service.impl,毫秒级
```

### 6.4 异步线程里的上下文

`SseEmitter` 的内容在自定义线程池的线程里产生，**`ThreadLocal`（登录用户上下文）不会自动传递**。规则：**进入异步执行前把需要的上下文取出来，作为方法参数显式传入**；禁止在异步线程里读 `UserContext.get()`。

---

## 七、红线清单（适配扁平九包，违反即错）

> 写完任何一个类，先对照这 16 条自查。

| # | 红线 | 判据 |
|---|------|------|
| 1 | 跨模块只能引用对方 `service`(接口) / `dto` / `constant` / `exception` | 出现 `import com.hify.{他}.{其它包}` |
| 2 | 跨模块注入的接口必须带 `@ModuleApi` | 注入类型无此注解 |
| 3 | `service` 包（接口）不许依赖 `service.impl` / `mapper` / `entity` | 接口签名或 import |
| 4 | Controller 不许注入 Mapper，也不许注入 `service.impl` | `controller` 包出现 `import ...mapper...` / `...service.impl...` |
| 5 | Entity 不许出现在 Controller 方法签名和 `@ModuleApi` 接口签名里 | 方法签名出现 `*Entity` |
| 6 | `*Request` / `*Response` 不许出现在 `@ModuleApi` 接口签名里 | 方法签名 |
| 7 | `@Transactional` 只许出现在 `service.impl` | 其它包出现该注解 |
| 8 | 事务方法内不许有远程调用 | `@Transactional` 方法体内出现 LLM / MCP / HTTP / 跨模块写调用 |
| 9 | 不许跨模块 JOIN | SQL 里出现两个不同模块前缀的表名 |
| 10 | 不许建跨模块外键 | Flyway 脚本里 `FOREIGN KEY` 指向别模块前缀的表 |
| 11 | 不许字段注入 | 出现 `@Autowired` 修饰字段 |
| 12 | 不许抛裸异常 | 出现 `throw new RuntimeException` / `new Exception` |
| 13 | 不许吞异常 | `catch` 块内既无 `log.error/warn` 也无重抛 |
| 14 | 日志不许字符串拼接 | `log.xxx("..." + var)`，必须 `{}` 占位 |
| 15 | API Key / Token 不许进日志、不许明文进库 | 未脱敏 / 未加密 |
| 16 | 不许新建白名单外的包 | 包名不在第一节九包 + 4.3 追加表中 |

---

## 八、ArchUnit 规则（`hify-app/src/test`）

```java
/**
 * 架构约束校验。
 * <p>模块间「要不要依赖」由 pom 在编译期强制；「依赖之后能看到多少」由本测试强制。
 * <p><b>本测试不允许 @Disabled。</b>
 */
@AnalyzeClasses(packages = "com.hify", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 红线 1 + 2：跨模块只能访问对方 service 接口(带 @ModuleApi) / dto / constant / exception */
    @ArchTest
    static final ArchRule 跨模块只能访问对外契约 = slices()
            .matching("com.hify.(*)..").namingSlices("$1")
            .should().notDependOnEachOther()
            .ignoreDependency(DescribedPredicate.alwaysTrue(),
                    JavaClass.Predicates.annotatedWith(ModuleApi.class)
                            // 注意:"com.hify.*.service" 不带 ".." ,精确匹配接口包,不含 service.impl
                            .or(resideInAnyPackage("com.hify.*.dto..",
                                                   "com.hify.*.constant..",
                                                   "com.hify.*.exception..",
                                                   "com.hify.common..")));

    /** 红线 3：service 接口层不许依赖实现与持久化 */
    @ArchTest
    static final ArchRule service接口不依赖实现 = noClasses()
            .that().resideInAPackage("com.hify.*.service")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..service.impl..", "..mapper..", "..entity..");

    /** 红线 4：controller 不许碰 mapper 与 impl */
    @ArchTest
    static final ArchRule controller只依赖service接口 = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..mapper..", "..service.impl..");

    /** 红线 7：@Transactional 只许出现在 service.impl */
    @ArchTest
    static final ArchRule 事务只在impl = methods()
            .that().areAnnotatedWith(Transactional.class)
            .should().beDeclaredInClassesThat().resideInAPackage("..service.impl..");

    /** 红线 11：不许字段注入 */
    @ArchTest
    static final ArchRule 不许字段注入 = noFields().should().beAnnotatedWith(Autowired.class);

    /** 包级无环 */
    @ArchTest
    static final ArchRule 包无环 = slices().matching("com.hify.(*)..").should().beFreeOfCycles();
}
```

> 骨架按 ArchUnit 实际版本 API 补全，总量控制在 50 行内。红线 5、6 若表达成本过高，可只保留代码评审检查。

---

## 九、执行清单

### 9.1 新增一个 CRUD 功能（照顺序做）

1. Flyway 脚本 → `resources/db/migration/{module}/V20260811_01__create_xxx.sql`，表名带模块前缀，含五个必备字段；
2. `XxxEntity` → `entity/`；
3. `XxxMapper extends BaseMapper<XxxEntity>` → `mapper/`；
4. `XxxCreateRequest` / `XxxQueryRequest` / `XxxResponse`（含 `from` 静态方法）→ `dto/`；
5. `XxxErrorCode` 枚举 → `exception/`（本功能有新错误码时）；
6. `XxxService` 接口 → `service/`；**其它模块要用的话加 `@ModuleApi`，并把方法裁剪到调用方真正需要的范围**；
7. `XxxServiceImpl` → `service/impl/`，写方法加 `@Transactional(rollbackFor = Exception.class)`；
8. `XxxController` → `controller/`，返回 `R<T>`，方法体不超过 3 行。

### 9.2 「这个类该放哪」决策树

```
它主要在做什么?
├─ 处理 HTTP 请求                → controller/
├─ 声明业务能力(接口)            → service/        (对外的加 @ModuleApi)
├─ 实现业务规则 / 开事务         → service/impl/
├─ 数据库单表操作                → mapper/
├─ 和数据库表一一对应            → entity/
├─ 在层与层 / 模块与模块之间传数据 → dto/
├─ 读配置 / 装配 Bean            → config/
├─ 错误码                        → exception/
├─ 常量、枚举                    → constant/
├─ 调外部系统(LLM / MCP)         → client/      (仅 provider / mcp)
├─ SSE 连接管理                  → sse/         (仅 chat)
├─ 对话编排                      → runtime/     (仅 chat)
├─ 图解析 / 节点执行             → definition/ executor/  (仅 workflow)
├─ 文档处理 / 向量 / 文件存储    → pipeline/ vector/ storage/  (仅 knowledge)
├─ 不落库的领域对象              → domain/      (仅四个厚模块)
└─ 都不是 → 对照 4.3 白名单;不在表里就是设计有问题,先讨论再写
```

### 9.3 提交前自查

| ✔ | 检查 |
|---|---|
| ☐ | 16 条红线过一遍 |
| ☐ | 新建的包在九包骨架或 4.3 白名单里 |
| ☐ | 新增的 `@ModuleApi` 接口，方法是否已裁剪到调用方真正需要的范围 |
| ☐ | 每个 public 方法有 Javadoc（参数 / 返回值 / 异常） |
| ☐ | 每个方法有入口日志和失败分支日志 |
| ☐ | 能用 Lombok 的地方没手写样板代码 |
| ☐ | `mvn test` 通过（含 ArchUnit） |

---

## 十、一句话结论

> **模块内部扁平九包：`controller` / `service` / `service.impl` / `mapper` / `entity` / `dto` / `config` / `exception` / `constant`，厚模块按白名单追加专属包。**
> **`api` / `internal` 两层目录取消，模块边界改由 `service`（接口，带 `@ModuleApi`）承担——这也是本结构下 `service` + `service/impl` 必须拆的原因：接口包就是对外契约本身，不是分层洁癖。**
> **跨模块只能引用对方 `service` / `dto` / `constant` / `exception`，只传 id 与 dto，不共享事务，不建反向依赖。**

---

## 十一、待确认项

| # | 事项 | 说明 | 倾向 |
|---|------|------|------|
| 1 | **`@ModuleApi` 注解引不引** | 见 4.1。不引则 `service` 包全部接口对外可见 | **倾向引**；退回选项只有「全部对外」，不接受靠自觉 |
| 2 | **厚模块专属包（4.3）是否认可** | 这是本文档在九包骨架之上的**追加**，不是用户原始决策的一部分。不认可的话，`sse` / `runtime` / `executor` 等约 40 个类需塞进 `service/impl` | 倾向认可 |
| 3 | **是否引 MapStruct** | 本文档按 DTO 静态工厂编写（4.4）；引入则 4.4 作废 | 一期不引 |
| 4 | **`hify-agent` 依赖修订** | 5.3 的推导结论是 `agent` 只依赖 `common`；`01_Hify代码组织（基准）.md` 3.3 目前写的是 `agent → common, provider`，需同步修订 | 改为只依赖 `common` |
| 5 | **`01_Hify代码组织（基准）.md` 打补丁** | 按第二节的取代关系，删除其第四节、修订 9.2 一行、修订 3.3 一行 | 待执行 |

---

## 附：与既有约定的对照

`../../../hify/CLAUDE.md` 中「模块边界四条规矩」在本结构下的落地位置：

| CLAUDE.md 原文 | 本文档对应 | 强制方式 |
|---|---|---|
| 模块间只通过接口调用，不跨模块直连对方的 DAO / Mapper | 红线 1、2 | ArchUnit |
| 数据库层不跨模块 JOIN | 红线 9 | 代码评审 + SQL 检查 |
| 状态外置，服务本身无状态 | 6.4 | 代码评审 |
| 文件存储走抽象接口 | 4.3 `knowledge/storage` | 代码评审 |
