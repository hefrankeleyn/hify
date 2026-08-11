# Hify 代码组织规范（过程稿）

> 🔧 **本文档是过程稿**。定稿后其「红线清单」与「速查表」两节应压缩进 `../../../hify/CLAUDE.md`，本文档作为完整依据保留。
> 上游依据：`../02-决策/01_Hify代码组织（基准）.md`（模块划分与依赖方向以它为准）
> 适用范围：`hify-*` 全部后端模块。前端 `hify-web` 不在本文档范围。
> 整理日期：2026-08-11

---

## 〇、本文档的使用方式

本文档面向**代码的实际书写者（人或模型）**，规则按「能否被机械执行」编写：

- 每条规则要么给出**可复制的代码模板**，要么给出**一眼可查的判据**（某个包里出现某个 import 就是违规）；
- 出现「尽量」「建议」「适当」等词的地方，都是**没写清楚**，应当视为缺陷提出，而不是自行发挥；
- 第一节的「红线清单」与第九节的「速查表」是最高优先级，与本文档其它部分冲突时以它们为准。

---

## 一、红线清单（违反即错，无例外）

> 写完任何一个类，先对照这 15 条自查一遍。

| # | 红线 | 判据（怎么一眼查出来） |
|---|------|------|
| 1 | 跨模块只能引用对方 `api` 包 | 出现 `import com.hify.{其他模块}.internal.*` |
| 2 | 跨模块只能注入接口，不能注入实现类 | 注入的类型不在 `com.hify.*.api` 下 |
| 3 | `api` 包不许依赖本模块 `internal` | `api` 包内出现 `import ...internal...` |
| 4 | Controller 不许注入 Mapper | `internal.web` 包内出现 `import ...mapper...` |
| 5 | Entity 不许离开 `service` 层 | Controller 的方法签名、`api` 接口的方法签名里出现 `*Entity` |
| 6 | `@Transactional` 只许出现在 `internal.service` | 其它包出现该注解 |
| 7 | 事务方法内不许有远程调用 | `@Transactional` 方法体内出现 LLM / MCP / HTTP / 其它模块 `api` 的**写**方法调用 |
| 8 | 不许跨模块 JOIN | SQL 里出现两个不同前缀的表名（如 `chat_x` JOIN `agent_y`） |
| 9 | 不许建跨模块外键 | Flyway 脚本里 `FOREIGN KEY` 指向别的模块前缀的表 |
| 10 | 不许字段注入 | 出现 `@Autowired` 修饰字段（必须构造器注入 + `@RequiredArgsConstructor`） |
| 11 | 不许抛裸异常 | 出现 `throw new RuntimeException` / `throw new Exception` |
| 12 | 不许吞异常 | `catch` 块内既没有 `log.error/warn`，也没有重抛 |
| 13 | 日志不许字符串拼接 | `log.xxx("..." + var)`，必须用 `{}` 占位 |
| 14 | API Key / Token 不许进日志、不许明文进库 | 日志参数或 Entity 字段里出现未脱敏、未加密的 `apiKey` / `token` |
| 15 | 不许新建白名单外的包 | 包名不在第二节的模块包白名单里 |

---

## 二、模块内部的包结构

### 2.1 通用骨架

每个业务模块（`hify-provider` / `hify-agent` / `hify-chat` / `hify-mcp` / `hify-workflow` / `hify-knowledge`）的目录固定为：

```
hify-{module}/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/hify/{module}/
    │   │   ├── api/                      # 对外契约层（其他模块唯一可见）
    │   │   │   ├── package-info.java
    │   │   │   ├── XxxService.java       #   接口，只有接口
    │   │   │   └── dto/                  #   对外 DTO
    │   │   └── internal/                 # 实现，对外不可见
    │   │       ├── web/                  #   接入层
    │   │       │   └── dto/              #     Request / Response
    │   │       ├── service/              #   业务层（事务边界在这里）
    │   │       ├── mapper/               #   数据访问层
    │   │       ├── entity/               #   数据库映射对象
    │   │       ├── converter/            #   对象转换
    │   │       ├── config/               #   本模块 @ConfigurationProperties
    │   │       └── {专属包}/              #   厚模块专属，见 2.3 白名单
    │   └── resources/
    │       ├── mapper/{module}/          # MyBatis XML（仅复杂 SQL）
    │       └── db/migration/{module}/    # Flyway 脚本
    └── test/java/com/hify/{module}/
```

### 2.2 六层的一句话职责

| 层 | 唯一职责 | 不该出现在这里的东西 |
|---|---|---|
| `api` | **声明**本模块对外能干什么 | 任何实现代码、任何 `internal` 引用 |
| `web` | HTTP 协议适配：收参、校验、调 service、包 `R<T>` | 业务判断、事务、Entity、Mapper |
| `service` | 业务规则 + 事务边界 + 编排本模块内部 | HTTP 概念（`HttpServletRequest`、`R<T>`）、SQL 拼接 |
| `mapper` | 单表 CRUD 与查询 | 业务逻辑、事务、跨表 JOIN（跨模块） |
| `entity` | 与数据库表一一对应 | 业务方法、任何非数据库字段 |
| `converter` | 对象之间的搬运 | 业务判断、IO、注入任何 Bean（**必须是静态方法工具类**） |

### 2.3 各模块的包白名单

**只允许出现下表列出的包。需要新增包时，先改本表。**

| 模块 | `api` 下 | `internal` 下允许的包 |
|---|---|---|
| `hify-common` | —（无 api/internal 之分） | `result` `exception` `constant` `util` `crypto` |
| `hify-provider` | `dto` | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` **`client`** |
| `hify-agent` | `dto` | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` |
| `hify-knowledge` | `dto` | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` **`pipeline` `vector` `storage` `domain`** |
| `hify-mcp` | `dto` | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` **`client` `domain`** |
| `hify-chat` | （一期为空） | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` **`sse` `runtime` `domain`** |
| `hify-workflow` | （一期为空） | `web`(+`dto`) `service` `mapper` `entity` `converter` `config` **`definition` `executor` `domain`** |
| `hify-app` | — | `config`（+ `src/test` 下的架构测试） |

加粗的是**厚模块专属包**，职责见 2.4。`domain` 只允许厚模块建，放不落库的领域对象。

### 2.4 厚模块专属包的职责与规则

| 包 | 所属模块 | 职责 | 硬规则 |
|---|---|---|---|
| `client` | provider / mcp | 与外部系统通信（LLM HTTP、MCP 协议）：请求构造、响应解析、重试、错误映射 | 禁止注入 Mapper；禁止 `@Transactional`；所有外部异常必须转成本模块的 `BizException` 或结果对象，不许原样外抛 |
| `pipeline` | knowledge | 文档处理链：解析 → 分块 → 向量化 → 入库 | 每一步是一个独立类，用接口串联；禁止 `@Transactional`（长耗时） |
| `vector` | knowledge | ★ `VectorStore` 接口 + `InMemoryVectorStore` 实现 | 接口方法不许出现 MyBatis / MySQL 概念，换实现时不改调用方 |
| `storage` | knowledge | ★ `FileStorage` 接口 + `LocalFileStorage` 实现 | 同上；出现第二个使用方时整体上移到 `hify-common` |
| `sse` | chat | `SseEmitter` 的创建、心跳、超时、异常、断连回调、上游取消 | 禁止业务逻辑；禁止 `@Transactional` |
| `runtime` | chat | 对话编排：装上下文 → 注入 RAG → 调模型 → 工具循环 | **禁止 `@Transactional`**（见 5.4）；禁止直接注入 Mapper，落库一律过 `service` |
| `definition` | workflow | JSON → `Graph` 的解析与校验 | 纯函数式，禁止注入任何 Bean 之外的状态 |
| `executor` | workflow | `NodeExecutor` 接口 + 各节点实现 + 图执行器 | 新增节点类型只允许「加一个实现类」，禁止改图执行器的分派逻辑 |
| `domain` | 四个厚模块 | 不落库的领域对象（`ChatContext` / `Graph` / `Segment`…） | 禁止 MyBatis 注解；禁止注入 Bean；可以有业务方法 |

★ 标记的两处是预期会被替换的实现点（换向量库、换对象存储）。

---

## 三、类的命名与位置（无歧义表）

| 角色 | 包 | 类名格式 | 示例 |
|---|---|---|---|
| 对外接口 | `api` | `Xxx{Query\|Command}Service` / 能力名 | `AgentQueryService`、`ChatModelClient` |
| 对外 DTO | `api.dto` | 业务名词，无后缀 | `AgentConfig`、`RetrievedChunk` |
| 控制器 | `internal.web` | `XxxController` | `AgentController` |
| 入参 | `internal.web.dto` | `Xxx{Create\|Update\|Query}Request` | `AgentCreateRequest` |
| 出参 | `internal.web.dto` | `Xxx{\|Detail}Response` | `AgentResponse`、`AgentDetailResponse` |
| 业务服务 | `internal.service` | `XxxService`（**类，不写接口**） | `AgentService` |
| api 接口实现 | `internal.service` | `Xxx...ServiceImpl` | `AgentQueryServiceImpl` |
| 数据访问 | `internal.mapper` | `XxxMapper extends BaseMapper<XxxEntity>` | `AgentMapper` |
| 表映射对象 | `internal.entity` | `XxxEntity` | `AgentEntity`、`ChatMessageEntity` |
| 转换器 | `internal.converter` | `XxxConverter`（全静态方法） | `AgentConverter` |
| 配置 | `internal.config` | `XxxProperties` | `ChatProperties` |

### 3.1 关于「Service 要不要写接口」

**只有 `api` 包里的对外契约必须是接口；`internal.service` 一律写类，不写接口。**

`XxxService` + `XxxServiceImpl` 的组合在只有一个实现时是纯噪音——每次跳转多一跳，每次改方法签名改两处。需要多实现的地方（`VectorStore`、`NodeExecutor`、`FileStorage`）本来就都在厚模块专属包里，那里该写接口就写接口。

`api` 接口的实现类是唯一例外，命名 `XxxServiceImpl`，放 `internal.service`：

```java
// hify-agent/internal/service/AgentQueryServiceImpl.java
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQueryServiceImpl implements AgentQueryService { ... }
```

---

## 四、各层的代码模板（照抄即可）

### 4.1 Entity

```java
package com.hify.agent.internal.entity;

/**
 * Agent 定义表。
 * <p>对应表 {@code agent_definition}。本类只做表映射，不承载任何业务逻辑。
 */
@Data
@TableName("agent_definition")
public class AgentEntity {

    /** 主键 id */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Agent 名称，同一部署内不要求唯一 */
    private String name;

    /** 系统提示词 */
    private String systemPrompt;

    /**
     * 所选模型提供商 id。
     * <p>跨模块引用只存 id，<b>不建外键</b>，也不放 Provider 对象字段。
     */
    private Long providerId;

    /** 所选模型名，如 {@code gpt-4o} / {@code qwen2.5:7b} */
    private String modelName;

    /** 绑定的知识库 id 列表，JSON 数组，形如 {@code [1,2,3]} */
    private String knowledgeBaseIds;

    /** 绑定的 MCP 工具引用列表，JSON 数组 */
    private String mcpToolRefs;

    /** 状态：1 启用 / 0 停用。删除一律做停用，不物理删除 */
    private Integer status;

    /** 创建人 id */
    @TableField(fill = FieldFill.INSERT)
    private Long creatorId;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 未删 / 1 已删 */
    @TableLogic
    private Integer deleted;
}
```

**Entity 强制约定**：

1. 每张表必备五个字段：`id` / `create_time` / `update_time` / `deleted` / `creator_id`；
2. `create_time` / `update_time` / `creator_id` 由 `MetaObjectHandler` 自动填充，**代码里禁止手工 set**；
3. 逻辑删除统一 `@TableLogic`，业务代码里禁止出现 `deleted = 0` 的手写条件；
4. **Entity 里禁止出现对象引用字段**（不许有 `private ProviderEntity provider`）；
5. JSON 列用 `String` 存，在 `converter` 里做序列化/反序列化，**不在 Entity 上挂 TypeHandler**（换实现时更好查）。

### 4.2 Mapper

```java
package com.hify.agent.internal.mapper;

/**
 * Agent 定义表数据访问。
 * <p>只做本模块单表操作，禁止 JOIN 其它模块前缀的表。
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
```

**Mapper 强制约定**：

1. 一律 `extends BaseMapper<XxxEntity>`，**不使用 MyBatis-Plus 的 `IService` / `ServiceImpl`**——它会把 CRUD 语义混进业务 Service，并诱导 Controller 直接 `service.save(entity)` 传 Entity（违反红线 5）；
2. 简单查询用 `LambdaQueryWrapper` 写在 `service` 里；
3. 复杂 SQL 写 XML，放 `resources/mapper/{module}/XxxMapper.xml`，**禁止 `@Select` / `@Update` 注解 SQL**；
4. **禁止无 `where` 且无分页的 `selectList`**：列表查询一律走 `selectPage`；
5. 跨模块的数据只用 id 关联，需要对方数据时调对方 `api`（红线 8）。

### 4.3 Converter

```java
package com.hify.agent.internal.converter;

/**
 * Agent 相关对象转换。
 * <p>只搬运字段，不做任何业务判断，不注入任何 Bean。
 */
@UtilityClass
public class AgentConverter {

    /**
     * 创建请求 → 数据库实体。
     *
     * @param request 创建请求，不能为 null
     * @return 待落库实体，审计字段由自动填充负责，此处不设置
     */
    public AgentEntity toEntity(AgentCreateRequest request) {
        AgentEntity entity = new AgentEntity();
        entity.setName(request.getName());
        entity.setSystemPrompt(request.getSystemPrompt());
        entity.setProviderId(request.getProviderId());
        entity.setModelName(request.getModelName());
        entity.setKnowledgeBaseIds(JsonUtil.toJson(request.getKnowledgeBaseIds()));
        entity.setMcpToolRefs(JsonUtil.toJson(request.getMcpToolRefs()));
        entity.setStatus(StatusConstant.ENABLED);
        return entity;
    }

    /**
     * 数据库实体 → 对外运行时配置（api.dto）。
     *
     * @param entity 数据库实体，不能为 null
     * @return 供 chat / workflow 使用的运行时配置
     */
    public AgentConfig toConfig(AgentEntity entity) {
        return AgentConfig.builder()
                .agentId(entity.getId())
                .systemPrompt(entity.getSystemPrompt())
                .providerId(entity.getProviderId())
                .modelName(entity.getModelName())
                .knowledgeBaseIds(JsonUtil.toLongList(entity.getKnowledgeBaseIds()))
                .mcpToolRefs(JsonUtil.toList(entity.getMcpToolRefs(), McpToolRef.class))
                .build();
    }
}
```

**Converter 强制约定**：`@UtilityClass`（Lombok，自动 final + 私有构造 + 静态方法）；只允许 `toXxx` 系列静态方法；**禁止注入 Bean、禁止调 Mapper、禁止业务判断**（如「status 为 0 时抛异常」属于 service 的活）。

### 4.4 Service

```java
package com.hify.agent.internal.service;

/**
 * Agent 管理业务逻辑。
 * <p>本类是本模块的事务边界，所有写操作在此开启事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;

    /**
     * 创建 Agent。
     *
     * @param request 创建请求
     * @return 新建 Agent 的主键 id
     * @throws BizException 名称为空或模型未选择时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(AgentCreateRequest request) {
        log.info("创建 Agent 开始, name={}, providerId={}, modelName={}",
                request.getName(), request.getProviderId(), request.getModelName());

        // 业务校验：绑定关系只校验格式，不校验对方是否存在（见规范 6.4）
        if (request.getProviderId() == null || StrUtil.isBlank(request.getModelName())) {
            log.warn("创建 Agent 失败, 未指定模型, name={}", request.getName());
            throw new BizException(AgentErrorCode.MODEL_REQUIRED);
        }

        AgentEntity entity = AgentConverter.toEntity(request);
        agentMapper.insert(entity);

        log.info("创建 Agent 成功, agentId={}, name={}", entity.getId(), entity.getName());
        return entity.getId();
    }

    /**
     * 分页查询 Agent 列表。
     *
     * @param request 查询条件，含分页参数
     * @return 分页结果
     */
    public PageResult<AgentResponse> page(AgentQueryRequest request) {
        log.debug("分页查询 Agent, keyword={}, pageNo={}", request.getKeyword(), request.getPageNo());

        LambdaQueryWrapper<AgentEntity> wrapper = Wrappers.<AgentEntity>lambdaQuery()
                .like(StrUtil.isNotBlank(request.getKeyword()), AgentEntity::getName, request.getKeyword())
                .orderByDesc(AgentEntity::getId);

        Page<AgentEntity> page = agentMapper.selectPage(
                new Page<>(request.getPageNo(), request.getPageSize()), wrapper);

        return PageResult.of(page.getTotal(),
                page.getRecords().stream().map(AgentConverter::toResponse).toList());
    }
}
```

**Service 强制约定**：

1. `@Slf4j` + `@Service` + `@RequiredArgsConstructor`，依赖全部 `private final`（红线 10）；
2. 写方法必须 `@Transactional(rollbackFor = Exception.class)`；**只读方法不加**；
3. 每个 public 方法**入口一条 `log.info`（写）或 `log.debug`（读）记录关键入参**，成功路径一条结果日志，失败分支 `log.warn` + 抛 `BizException`；
4. **禁止出现 HTTP 概念**：不许注入 `HttpServletRequest`，不许返回 `R<T>`；
5. **禁止在事务方法里调用其它模块的 api 写方法或任何远程调用**（红线 7）。

### 4.5 Controller

```java
package com.hify.agent.internal.web;

/**
 * Agent 管理接口。
 * <p>只做协议适配：参数校验 → 调 service → 包 {@link R}。不含任何业务判断。
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * 创建 Agent。
     *
     * @param request 创建请求，字段校验由 {@code @Valid} 完成
     * @return 新建 Agent 的 id
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody AgentCreateRequest request) {
        log.info("[API] 创建 Agent, name={}", request.getName());
        return R.ok(agentService.create(request));
    }

    /**
     * 分页查询 Agent。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AgentResponse>> page(@Valid AgentQueryRequest request) {
        return R.ok(agentService.page(request));
    }
}
```

**Controller 强制约定**：

1. 路径统一 `/api/{模块复数名}`，如 `/api/agents` `/api/providers` `/api/knowledge-bases` `/api/mcp-servers` `/api/workflows` `/api/chat`；
2. 返回值一律 `R<T>`；**`api` 包的接口返回裸对象，绝不包 `R`**（`R` 是 HTTP 层概念，不是模块契约的一部分）；
3. **禁止写 `try-catch`**，异常一律由全局 `@RestControllerAdvice` 处理；
4. 字段校验用 `@Valid` + Jakarta Validation 注解写在 Request DTO 上，**不在 Controller 里写 if 判空**；
5. 方法体不许超过 3 行（日志 + 调用 + 返回）。超过说明业务逻辑漏到了 web 层。

### 4.6 `api` 接口与其实现

```java
// hify-agent/api/AgentQueryService.java
package com.hify.agent.api;

/**
 * Agent 对外查询服务。
 * <p>只暴露编排层（chat / workflow）真正需要的能力；管理面的增删改查不在此列。
 */
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

**`api` 包强制约定**：

1. 只允许放**接口**和**不可变 DTO**（`@Value` 或 `@Data @Builder`）；
2. 方法签名里**禁止出现 Entity、Request/Response DTO、MyBatis 的 `Page`**；
3. 每个方法的 Javadoc 必须写清 **null 语义**（能否传 null、能否返回 null）和**抛什么异常**——这是跨模块调用方唯一能看到的契约；
4. **按调用方需求裁剪**：调用方只要「按 id 查配置」，就只写这一个方法，不要把内部 Service 复制一遍。`api` 越薄，模块越独立。

---

## 五、事务规则

### 5.1 事务边界只有一处

**`@Transactional` 只允许出现在 `internal.service` 包的 public 方法上。** 其它任何包出现它都是错的（红线 6）。

### 5.2 事务方法内禁止的四件事

| 禁止 | 原因 |
|---|---|
| 调用 LLM / MCP / 任何 HTTP | 远程调用耗时以秒计，会长时间占住数据库连接 |
| 调用其它模块 `api` 的**写**方法 | 跨模块共享事务，把两个模块在数据库层焊死（见 5.3） |
| `Thread.sleep` / 等待锁 / 等待 Future | 同第一条 |
| 发起 `SseEmitter` 或向其 `send` | 流式输出的生命周期以分钟计，绝不能在事务里 |

### 5.3 跨模块调用不共享事务

**规矩：跨模块调用，被调方自己管事务；调用方不指望对方能被自己回滚。**

具体形态：模块 `api` 的方法要么是**只读查询**，要么**自身是完整的事务单元**（实现类内部标 `@Transactional`）。

跨模块的写操作若需要「一起成功或一起失败」，**说明模块划分错了**，应当合并到一个模块，而不是靠共享事务粘起来。

### 5.4 长流程（chat / workflow）的事务形态

对话与工作流执行是分钟级的长流程，**整个流程不许包在一个事务里**。落库拆成若干短事务：

```
用户发起对话
  ├─ [短事务 1] 创建会话（首轮时）+ 落库用户消息        ← service 层,毫秒级
  ├─ (无事务) RAG 检索 → 调模型流式输出 → 工具调用循环   ← runtime 包,秒~分钟级
  └─ [短事务 2] 落库助手消息 + 更新会话时间             ← service 层,毫秒级
```

所以 `chat.internal.runtime` 与 `workflow.internal.executor` 包**禁止标注 `@Transactional`**，它们通过调用本模块 `service` 的方法完成落库，每次调用是一个独立短事务。

### 5.5 异步线程里的上下文

`SseEmitter` 的内容在自定义线程池的线程里产生，**`ThreadLocal`（如登录用户上下文）不会自动传递**。

规则：**进入异步执行前，把需要的上下文取出来，作为方法参数显式传入**。禁止在异步线程里读 `UserContext.get()`。

---

## 六、跨模块调用规则

### 6.1 唯一合法形态

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntime {

    /* ↓ 全部来自其它模块的 api 包,全部是接口,全部构造器注入 */
    private final AgentQueryService agentQueryService;    // hify-agent.api
    private final ChatModelClientFactory clientFactory;   // hify-provider.api
    private final RetrievalService retrievalService;      // hify-knowledge.api
    private final ToolExecutor toolExecutor;              // hify-mcp.api
}
```

同步调用、普通方法调用。**一期不引入 Spring 事件、不引入 MQ。**

> 唯一允许用 Spring 事件的场景：主流程不该被它拖慢、也不该被它失败影响的旁路（用量统计、审计日志），且必须用 `@TransactionalEventListener`。除此之外一律同步调用——事件会把调用链变成隐式的，排障要靠全局搜事件类型。

### 6.2 允许的依赖关系

以 `../02-决策/01_Hify代码组织（基准）.md` 第 3.3 节的依赖矩阵为准。四层方向：

```
L4 app  →  L3 chat / workflow  →  L2 agent / knowledge / mcp  →  L1 provider  →  L0 common
```

只能向下依赖；**同层禁止**（`chat ↔ workflow`、`knowledge ↔ mcp` 都是违规）；可以跳级向下。

需要一条矩阵里没有的依赖时：先确认不是抽象反了 → 改被依赖方的 pom 声明 → **同步更新基准文档的矩阵**。

### 6.3 跨模块传什么、不传什么

| 允许传 | 禁止传 |
|---|---|
| 基本类型、id（`Long`） | Entity（红线 5） |
| 对方 `api.dto` 里定义的对象 | 本模块的 Request / Response DTO |
| `hify-common` 里的通用对象（`PageResult`） | MyBatis 的 `Page` / `IPage` / `Wrapper` |
| 本模块 `api.dto` 里定义的入参对象 | `HttpServletRequest` / `SseEmitter` |

**业务 DTO 一律放各模块自己的 `api.dto`，禁止提到 `hify-common`。** 把业务 DTO 提到 common 是最隐蔽的耦合后门——它让两个本不该有关系的模块通过一个共享结构体长在一起，而 pom 拦不住（谁都能依赖 common）。

### 6.4 关联关系只存 id，不做存在性校验

Agent 绑定模型 / 知识库 / MCP 工具，**只存 id，管理面保存时不校验对方是否存在**。

原因：若 `agent` 保存时要校验知识库和 MCP 工具存在，就需要 `agent → knowledge` 和 `agent → mcp` 两条同层依赖，直接违规。三个校验要么都做要么都不做，结论是都不做。

**替代方案（三条一起）**：

1. 前端下拉列表分别调各模块自己的 REST 接口，用户只能选到存在的对象；
2. 存在性校验**推迟到运行时由 `chat` 执行**——`chat` 依赖三者都是合法的向下依赖，模型被停用/知识库被删表现为对话时一条明确错误；
3. **删除一律做「停用」（`status = 0`），不做物理删除，不做反向引用检查。** 需要「还有 3 个 Agent 在用」这种影响面提示时，由前端调 `GET /api/agents?providerId=x` 在 UI 层组合。

> 通则：**跨模块的反向查询上移到前端解决，后端不建反向依赖。** 这条能挡掉大部分循环依赖。

### 6.5 下层需要上层的信息 → 让上层传进来

`mcp` 执行工具时若需要「当前会话是谁」，**禁止反向去查 `chat`**。在 `mcp.api` 定义无业务含义的上下文载体，由调用方填：

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

### 6.6 跨模块调用失败必须显式处理

调用其它模块的 `api` 时，**禁止不处理异常直接向上抛**。三种既定策略：

| 被调方 | 失败时的期望行为 | 代码形态 |
|---|---|---|
| `provider`（LLM） | **明确报错推给前端**，不静默失败、不无限等待 | 捕获后转 `BizException`，通过 SSE 的 error 事件下发 |
| `mcp`（工具） | **不中断对话**：调用失败作为工具结果交回模型 | 捕获后构造 `ToolResult.failure(msg)` 继续循环 |
| `knowledge`（检索） | **降级为不注入知识库**，对话仍可用 | 捕获后 `log.warn` + 返回空列表，主流程继续 |

---

## 七、异常、日志、返回值

### 7.1 异常

```java
// com.hify.common.exception.ErrorCode —— 接口,各模块自己定义枚举实现
public interface ErrorCode {
    /** 错误码,格式 {模块}.{业务}.{原因},如 agent.create.model_required */
    String getCode();
    /** 面向用户的错误提示 */
    String getMessage();
}
```

| 规则 | 说明 |
|---|---|
| 只抛 `BizException` | `throw new BizException(AgentErrorCode.MODEL_REQUIRED)`，禁止裸 `RuntimeException`（红线 11） |
| 错误码枚举归模块所有 | `agent` 的错误码放 `hify-agent`，**不许集中到 common**（否则 common 会依赖业务概念） |
| 全局处理器在 `hify-common` | `@RestControllerAdvice`，`BizException` → 业务错误码 + 200/400；未知异常 → `log.error` 全栈 + 通用提示 |
| 不吞异常 | `catch` 块必须至少有 `log.warn/error`，否则视为吞（红线 12） |
| 外部异常不外泄 | `client` 包必须把 `IOException` / SDK 异常转成 `BizException` 或结果对象 |

### 7.2 日志

| 位置 | 级别 | 内容 |
|---|---|---|
| Controller 入口 | `info` | `[API] 动作, 关键标识`，**不打全量 body** |
| Service 写方法入口 | `info` | 关键入参（id、名称），不打大字段（提示词、文档内容） |
| Service 读方法入口 | `debug` | 查询条件 |
| 业务分支不通过 | `warn` | 原因 + 关键上下文，随后抛 `BizException` |
| 外部调用失败 | `error` | 目标 + 耗时 + 异常，带堆栈 |
| 长流程关键节点 | `info` | 「RAG 命中 N 条」「工具调用 X 第 N 次」「对话完成，耗时 Xms，token Y」 |

**四条硬规则**：

1. 一律 `{}` 占位，禁止字符串拼接（红线 13）；
2. **`apiKey` / `token` / MCP Server 鉴权信息一律不进日志**；必须打时用 `SensitiveUtil.mask()`（保留首尾各 4 位）（红线 14）；
3. **流式输出禁止逐 token 打日志**，只在流开始、流结束、异常三处打；
4. 循环体内禁止 `info`，需要时降为 `debug`。

### 7.3 返回值

| 场景 | 类型 |
|---|---|
| Controller | `R<T>`（`code` / `message` / `data`） |
| Controller 分页 | `R<PageResult<T>>` |
| `api` 接口 | **裸对象**，不包 `R` |
| 查不到单个对象 | `api` 接口抛 `BizException`；`service` 内部方法返回 `null` 由调用方判 |
| 查不到列表 | 返回空集合，**禁止返回 null** |

---

## 八、`hify-app` 的全局配置清单

`hify-app` 不含业务代码，只放下列内容：

| 文件 | 内容 |
|---|---|
| `HifyApplication.java` | `@SpringBootApplication` + `@MapperScan("com.hify.*.internal.mapper")` + `@EnableAsync`（通配失效时退化为 `@MapperScan(basePackages="com.hify", annotationClass=Mapper.class)`） |
| `config/AsyncConfig.java` | **SSE 专用 `ThreadPoolTaskExecutor`**：core 16 / max 64 / queue 128 / 命名 `sse-`。**禁止使用默认的 `SimpleAsyncTaskExecutor`** |
| `config/MybatisPlusConfig.java` | `PaginationInnerInterceptor`（指定 `DbType.MYSQL`）+ `OptimisticLockerInnerInterceptor` |
| `config/MetaObjectHandlerImpl.java` | 自动填充 `createTime` / `updateTime` / `creatorId` |
| `config/RedisConfig.java` | 序列化方式、key 前缀 `hify:` |
| `config/WebMvcConfig.java` | CORS、拦截器（登录上下文） |
| `application.yml` | **唯一主配置**：数据源、Redis、Flyway、日志、各模块 `hify.{module}.*` 的值 |
| `src/test/.../ArchitectureTest.java` | ArchUnit 规则（见 8.1） |

### 8.1 必须存在的架构测试

```java
@AnalyzeClasses(packages = "com.hify", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 红线 1：跨模块不许引用对方 internal */
    @ArchTest static final ArchRule 跨模块只能访问_api;

    /** 红线 3：api 包不许依赖 internal */
    @ArchTest static final ArchRule api_不依赖_internal = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..internal..");

    /** 红线 4：web 层不许碰 Mapper */
    @ArchTest static final ArchRule web_不依赖_mapper = noClasses()
            .that().resideInAPackage("..internal.web..")
            .should().dependOnClassesThat().resideInAPackage("..internal.mapper..");

    /** 红线 6：@Transactional 只许出现在 service 包 */
    @ArchTest static final ArchRule 事务只在_service = methods()
            .that().areAnnotatedWith(Transactional.class)
            .should().beDeclaredInClassesThat().resideInAPackage("..internal.service..");

    /** 红线 10：不许字段注入 */
    @ArchTest static final ArchRule 不许字段注入 = noFields()
            .should().beAnnotatedWith(Autowired.class);

    /** 包级无环 */
    @ArchTest static final ArchRule 包无环 = slices()
            .matching("com.hify.(*)..").should().beFreeOfCycles();
}
```

> 骨架按 ArchUnit 实际 API 补全，总量控制在 40 行内。**这些测试跑在 `mvn test` 里，不允许 `@Disabled`。**

---

## 九、执行清单

### 9.1 新增一个 CRUD 功能（照顺序做）

1. 写 Flyway 脚本 → `hify-{module}/resources/db/migration/{module}/V20260811_01__create_xxx.sql`，表名带模块前缀，含五个必备字段；
2. 写 `XxxEntity` → `internal/entity`；
3. 写 `XxxMapper extends BaseMapper<XxxEntity>` → `internal/mapper`；
4. 写 `XxxCreateRequest` / `XxxQueryRequest` / `XxxResponse` → `internal/web/dto`，校验注解写在字段上；
5. 写 `XxxConverter`（`@UtilityClass`）→ `internal/converter`；
6. 写 `XxxService` → `internal/service`，写方法加 `@Transactional(rollbackFor = Exception.class)`；
7. 写 `XxxController` → `internal/web`，返回 `R<T>`，方法体不超过 3 行；
8. **如果其它模块需要用到它**：在 `api` 加一个裁剪过的接口 + `api/dto`，在 `internal/service` 加 `XxxServiceImpl`；否则 `api` 保持不动。

### 9.2 「这个类该放哪」决策树

```
这个类是给别的模块用的吗?
├─ 是 → 是接口或 DTO 吗?
│        ├─ 是 → api/ 或 api/dto/
│        └─ 否 → 它是实现 → internal/service/XxxServiceImpl
└─ 否 → 它主要在做什么?
         ├─ 处理 HTTP 请求      → internal/web/
         ├─ HTTP 的入参出参     → internal/web/dto/
         ├─ 业务规则 / 事务      → internal/service/
         ├─ 数据库单表操作      → internal/mapper/
         ├─ 和数据库表一一对应  → internal/entity/
         ├─ 只搬字段            → internal/converter/
         ├─ 读配置              → internal/config/
         ├─ 调外部系统          → internal/client/   (仅 provider / mcp)
         ├─ 不落库的领域对象    → internal/domain/   (仅厚模块)
         └─ 都不是              → 对照 2.3 白名单;不在表里就是设计有问题,先讨论
```

### 9.3 提交前自查（10 秒版）

| ✔ | 检查 |
|---|---|
| ☐ | 15 条红线过一遍 |
| ☐ | 新建的包在 2.3 白名单里 |
| ☐ | 每个 public 方法有 Javadoc（参数 / 返回值 / 异常） |
| ☐ | 每个方法有入口日志和失败分支日志 |
| ☐ | 能用 Lombok 的地方没手写样板代码 |
| ☐ | `mvn test` 通过（含 ArchUnit） |

---

## 十、本文档与 `CLAUDE.md` 的关系

`CLAUDE.md` 是每次对话都会加载的上下文，**必须短**。定稿后按下列方式切分：

| 内容 | 去向 |
|---|---|
| 第一节「15 条红线」 | **压进 `CLAUDE.md`**，逐条保留 |
| 2.3 包白名单、第三节命名表、9.2 决策树 | **压进 `CLAUDE.md`**，这三样决定「新代码放哪」，最高频 |
| 第四节代码模板 | 留在本文档，需要时让模型读本文件 |
| 五 / 六 / 七节的详细规则 | 留在本文档；`CLAUDE.md` 里只留一句指针 |

---

## 十一、本文档产生的待确认项

| # | 事项 | 说明 |
|---|------|------|
| 1 | **`hify-agent` 应改为只依赖 `hify-common`** | 6.4 的推导结论。基准文档 3.3 目前写的是 `agent → common, provider`，需要修订 |
| 2 | **Entity 类名后缀定为 `Entity`** | 备选是 `DO`。本文档统一用 `Entity`，与包名 `entity` 一致 |
| 3 | **不使用 MyBatis-Plus `IService` / `ServiceImpl`** | 理由见 4.2；这是一个明确取舍，需确认 |
| 4 | **登录上下文 `UserContext` 放 `hify-common`** | 依赖 `01_Hify产品决策（基准）` 待定项 2（登录与角色）是否做；若做，用户表 CRUD 暂放 `hify-app` |
| 5 | **是否引 MapStruct** | 本文档按手写 Converter 编写；若引入，4.3 的模板要改 |
