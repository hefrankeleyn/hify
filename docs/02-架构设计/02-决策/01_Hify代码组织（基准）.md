# Hify 代码组织（基准）

> 📌 **本文档是 Hify 代码组织形态的唯一依据（source of truth）**，如与其它任何文档冲突，**以本文档为准**。
> 上游依据：`../../02-产品决策/01_Hify产品决策（基准）.md`、`02_Hify技术选型（基准）.md`、`03_Hify部署与运维（基准）.md`
> 对比过程见 `../01-过程/01_代码组织方案对比.md`（过程稿，仅记录推演，不作依据）
> 决策日期：2026-08-11

---

## 一、最终决策

> **模块化单体（Modular Monolith）—— 用 Maven 多模块在编译期切分边界，运行时仍是一个进程、一个 jar、一个数据库。**

```
hify/
├── pom.xml                 # 父 POM，聚合全部模块，统一版本管理
├── hify-app/               # 启动模块，Spring Boot Application
├── hify-provider/          # 模型提供商管理
├── hify-agent/             # Agent 管理与配置
├── hify-chat/              # 对话引擎
├── hify-mcp/               # MCP 工具管理与调用
├── hify-workflow/          # 工作流编排与执行
├── hify-knowledge/         # 知识库与 RAG
├── hify-common/            # 公共模块（工具类、常量、异常、DTO）
├── hify-web/               # Vue 前端（不进 Maven reactor）
└── deploy/                 # Docker Compose 配置
```

### 1.1 先澄清「模块化单体」不是什么

一句话防止后面走偏：

| 是 | 不是 |
|---|---|
| 编译期 8 个 Maven 模块 | ❌ 8 个可部署服务 |
| 运行时**一个 JVM 进程、一个可执行 jar** | ❌ 进程间调用、RPC、服务注册发现 |
| **一个 MySQL 实例、一个 DataSource** | ❌ 每模块一个库 |
| 模块间**普通方法调用**（构造器注入） | ❌ HTTP / MQ / 事件总线 |

**多模块只是把「谁能引用谁」这件事从口头约定升级成编译器规则，除此之外什么都没变。** 部署形态、容量目标、运维策略完全沿用 `03_Hify部署与运维（基准）.md`，不受影响。

### 1.2 决策总表

| 维度 | 基准决策 |
|------|----------|
| **代码组织** | Maven 多模块，按**业务能力**切分（不按技术分层） |
| **模块数量** | **8 个后端模块**（app / provider / agent / chat / mcp / workflow / knowledge / common），一期封顶，不再新增 |
| **模块内可见性** | 每模块分 `api`（对外）/ `internal`（对内）两个顶层子包 |
| **依赖方向** | 单向 DAG，分四层，只能向下依赖，同层默认禁止 |
| **边界强制力** | **pom 依赖（编译期）为主 + ArchUnit 一条规则（挡 `internal` 跨模块引用）为辅** |
| **Spring Modulith** | ❌ **不引**（其核心价值在单模块下模拟模块边界，多模块下 pom 已经做完了这件事） |
| **前端** | `hify-web` 同仓不同构建，**不进 Maven reactor** |
| **构建产物** | 只有 `hify-app` 产出可执行 jar，其余模块产出普通 jar |

---

## 二、为什么是 C 而不是过程稿倾向的 B

过程稿的结论是「先做 B（单模块业务分包）+ Spring Modulith 校验，B→C 是机械迁移」。这个推演本身没错，但它漏算了一个前提，最终决策推翻它。**四条理由，按权重排序：**

### 2.1 决策时点在代码之前 —— 现在选 C 的迁移成本是零

过程稿反复强调「B→C 是机械迁移，不用重写代码」。这句话成立，但它同时意味着另一件事：

> **迁移成本再低，也永远大于零；而此刻仓库里还没有一行 Java 代码，直接从 C 起步的成本恰好是零。**

「先 B 后 C」的价值，是在「不确定要不要 C」时省下的期权费。但本项目的模块划分早在 `CLAUDE.md` 就已经定死（六个业务模块 + 公共 + 启动），边界不存在探索期——期权没有标的，那就没必要付费买。**先建 8 个空目录 8 个 pom，比一年后搬一次目录、改一次包引用、调一次构建脚本便宜。**

### 2.2 代码的实际书写者是模型，「靠自觉」这个前提不成立

这是过程稿完全没有纳入评估的因素，也是真正的决定性理由。

方案 B 的唯一软肋是「语言层面挡不住跨模块乱引用」，过程稿给出的对策是「机器校验 + 一个人的自觉」。但本项目是 **AI 辅助开发**的示范工程（见 `../../nodes/2026-07-29_B站主题课规划_AI编程方法论与判断力.md`），大部分代码由模型生成。这把 B 的软肋放大成硬伤：

| | 人写代码 | 模型写代码 |
|---|---|---|
| 会不会读 `CLAUDE.md` 里「不跨模块直连 Mapper」这条约定 | 会（写的时候记得） | **不一定**（上下文里可能根本没带上这一条） |
| 违规时的反馈 | 自己心里过一下 | **没有任何反馈**，除非跑校验 |
| 什么信号最有效 | 代码评审 | **编译错误**——它自动可见、自动触发修复，无需人介入 |

模型不会「顺手」违规，它是**在不知道有约束的情况下**写出最自然的代码——而「直接注入对方的 Mapper」恰恰是 Spring 项目里最自然的写法。此时唯一可靠的护栏是：**它 import 不到，编译不过，然后自己改。**

pom 依赖把架构约束翻译成了模型唯一无法忽略的语言。

### 2.3 pom 就是架构图，而架构显式化本身是本项目的产出

一个人的项目，半年后回来看，「模块之间到底谁依赖谁」这个问题只有两种答案来源：翻文档（会过期），或者读 pom（不会过期，因为不对就编译不过）。

方案 B 里这层关系散在各个 import 语句中，需要工具才能还原；方案 C 里它是 8 个 `<dependency>` 块，**是可读、可 diff、且被强制与代码一致的**。对一个要拿出来讲方法论的项目，这层显式化有额外价值。

### 2.4 C 的「税」在本项目的实际形态比过程稿估计的轻

过程稿列的三项成本，逐条核算：

| 过程稿列的成本 | 本项目的实际情况 |
|---|---|
| 「改跨模块接口要 `mvn install` 上游模块」 | **不成立**。IDEA / VS Code 用 IDE 自身编译时，reactor 内模块直接走源码，无需 install。只有走命令行才需要，且 `-pl xxx -am` 已覆盖（见 9.4） |
| 「新增模块要建目录、写 pom、改父 pom」 | 模块数**一期封顶 8 个**，这是一次性成本，不是持续付的税 |
| 「配置 / Flyway / 测试的归属要额外约定」 | 真实成本，但只需约定一次——**本文档第九节已经定完** |

### 2.5 同时被接受的代价（记录在案，不粉饰）

选 C 不是没有代价，以下三条明确接受：

1. **跨模块接口改动的反馈链更长**：改 `provider.api` 的签名，要等 chat / workflow / knowledge 三个模块重编译才看到全部报错。缓解见 9.4。
2. **存在过度切分的诱惑**：多模块结构会诱导「这块逻辑要不要也独立成模块」。**对策：8 个模块封顶，新增模块的门槛写死在第八节。**
3. **`hify-common` 有变成垃圾桶的风险**：这是本决策相对过程稿最实质的偏离（合并了 `infra`），单列一节处理，见第五节。

---

## 三、模块划分与职责

### 3.1 八个模块

| 模块 | 层 | 职责 | 性质 | 内部结构 |
|------|---|------|------|------|
| `hify-app` | 启动 | Spring Boot 入口、全局配置、`@MapperScan`、集成测试 | 极薄 | 无业务代码 |
| `hify-chat` | 编排 | 对话引擎：SSE、上下文装配、RAG 注入、工具调用循环、会话与消息持久化 | **厚** | `web` `sse` `runtime` `service` `mapper` `entity` |
| `hify-workflow` | 编排 | JSON 工作流的定义解析与图执行 | **厚** | `web` `definition` `executor` `service` `mapper` `entity` |
| `hify-agent` | 配置 | Agent 定义的增删改查、启停、配置校验 | 薄 | `web` `service` `mapper` `entity` |
| `hify-knowledge` | 能力 | 文档上传、解析分块、向量化入库、检索 | **混合** | 管理面薄；`pipeline` `vector` `storage` 独立 |
| `hify-mcp` | 能力 | MCP Server 配置管理、工具清单发现与缓存、工具调用 | **混合** | 管理面薄；`client` 独立 |
| `hify-provider` | 能力底座 | 模型提供商配置、模型客户端（对话流式 + 向量化） | **混合** | 管理面薄；`client` `crypto` 独立 |
| `hify-common` | 公共 | 统一响应体、异常体系、常量、工具类、通用 DTO、加解密 | — | 见第五节 |

**与 `CLAUDE.md` 的差异**：`CLAUDE.md` 中的模块名 `tool` 在本文档定为 **`mcp`**（一期工具能力只有 MCP 一种来源，名字直接对应实现，不做无谓的抽象层命名）。`CLAUDE.md` 需同步更新。

### 3.2 依赖方向：四层，只能向下

```
┌─────────────────────────────────────────────────────┐
│  L4  hify-app            （聚合全部模块，谁都能依赖） │
└─────────────────────────────────────────────────────┘
                          ▼
┌─────────────────────────┬───────────────────────────┐
│  L3  hify-chat          │  hify-workflow            │  编排层
└─────────────────────────┴───────────────────────────┘
                          ▼
┌───────────────┬─────────────────┬───────────────────┐
│  L2 hify-agent│ hify-knowledge  │  hify-mcp         │  配置 / 能力层
└───────────────┴─────────────────┴───────────────────┘
                          ▼
┌─────────────────────────────────────────────────────┐
│  L1  hify-provider                                  │  能力底座
└─────────────────────────────────────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────┐
│  L0  hify-common                                    │  公共，不依赖任何业务模块
└─────────────────────────────────────────────────────┘
```

**三条规则**：

| 规则 | 说明 |
|------|------|
| **只能向下依赖** | L3 → L2 / L1 / L0 ✅；L1 → L3 ❌（抽象反了） |
| **同层默认禁止** | `chat ↔ workflow` ❌；`knowledge ↔ mcp` ❌。将来 workflow 要产出对话消息，走 `chat.api` 单向或 Spring 事件，不允许反向 |
| **跨层向下可跳级** | `chat → provider`（跳过 L2）✅ |

### 3.3 各模块 pom 依赖矩阵（这就是架构图）

| 模块 | 依赖的业务模块 | 说明 |
|------|------|------|
| `hify-common` | 无 | 绝对不依赖任何业务模块 |
| `hify-provider` | `common` | |
| `hify-agent` | `common`, `provider` | Agent 保存时校验「所选模型是否存在 / 是否启用」，只用 `provider.api` 的只读查询 |
| `hify-knowledge` | `common`, `provider` | 向量化要调 embedding 模型 |
| `hify-mcp` | `common` | |
| `hify-chat` | `common`, `provider`, `agent`, `knowledge`, `mcp` | 编排层，消费全部能力 |
| `hify-workflow` | `common`, `provider`, `knowledge`, `mcp` | **一期不依赖 `agent`**——工作流节点直连模型，没有「Agent 节点」。将来要加，就是往 pom 里加一行，这正是多模块的价值：**依赖的扩张是一次显式动作，不是无声发生的** |
| `hify-app` | 全部 | 只做聚合与启动 |

> 出现「需要加一条本表没有的依赖」时，先问是不是抽象反了；确认合理再改 pom **并同步更新本表**。本表与 pom 不一致时，以 pom 为准，且说明本文档失修。

---

## 四、模块内部结构：`api` 与 `internal`

### 4.1 为什么多模块了还要分 api / internal

多模块解决的是「**要不要依赖**」，没解决「**依赖之后能看到多少**」。

`hify-chat` 的 pom 一旦依赖 `hify-agent`，`hify-agent` 里所有 `public` 类对 chat 就全部可见——包括 `AgentMapper`、`AgentEntity`、内部 Service。而 MyBatis-Plus 的 Mapper、Spring 要扫描的 Bean 都必须是 `public`，Java 的包可见性帮不上忙。

所以**每个业务模块内部固定分两个顶层子包**：

```
com.hify.{module}
├── api/          # 对外契约：接口 + 入参出参对象。其他模块只能引用这里
└── internal/     # 实现：web / service / mapper / entity / 及本模块专属的一切
```

### 4.2 `api` 包按调用方需求裁剪，不是内部 Service 的复制

```java
// hify-agent/src/main/java/com/hify/agent/api/AgentQueryService.java
/**
 * Agent 对外查询服务。
 * <p>对外只暴露编排层真正需要的能力，管理面（增删改、启停、校验）不出现在这里。
 */
public interface AgentQueryService {

    /**
     * 按 id 查询 Agent 的运行时配置，供 chat / workflow 编排使用。
     *
     * @param agentId Agent 主键 id，不能为 null
     * @return Agent 运行时配置
     * @throws com.hify.common.exception.BizException Agent 不存在或已停用时抛出
     */
    AgentConfig getConfig(Long agentId);
}
```

**判据：`api` 包越薄，模块耦合越低。** 一期各模块 `api` 的预期规模：

| 模块 | `api` 内容 |
|------|------|
| `provider` | `ChatModelClient`（流式对话）、`EmbeddingClient`（向量化）、`ChatModelClientFactory`、`ProviderQueryService`（只读校验） + 相关 DTO |
| `agent` | `AgentQueryService` + `AgentConfig` |
| `knowledge` | `RetrievalService` + `RetrievedChunk` |
| `mcp` | `ToolExecutor`、`ToolSpec`、`ToolResult` |
| `chat` | **一期为空**（没有模块依赖 chat） |
| `workflow` | **一期为空** |

`api` 为空的模块**仍然建 `api` 包并放 `package-info.java`**，标明「本模块一期不对外暴露」。

### 4.3 跨模块调用形态：普通构造器注入

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntime {

    private final AgentQueryService agentQueryService;    // hify-agent.api
    private final ChatModelClientFactory clientFactory;   // hify-provider.api
    private final RetrievalService retrievalService;      // hify-knowledge.api
    private final ToolExecutor toolExecutor;              // hify-mcp.api
}
```

同步调用，不引事件、不引 MQ。

> **只有一种情况用 Spring 事件**：主流程不该被它拖慢、也不该被它失败影响的旁路（用量统计、审计日志）。事件会把调用链变成隐式的，排障要靠全局搜事件类型——**能同步调用就别发事件**。

### 4.4 用 ArchUnit 补上编译器管不了的部分

pom 挡不住「依赖了 `hify-agent` 之后引用它的 `internal`」。补一条 ArchUnit 规则，放在 `hify-app` 的测试里（`hify-app` 依赖全部模块，能一次扫全）：

```java
/**
 * 架构约束校验：跨模块只能引用对方的 api 包。
 * <p>模块间「要不要依赖」由 pom 在编译期强制；「依赖后能看到多少」由本测试强制。
 */
@AnalyzeClasses(packages = "com.hify", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 任何类都不允许引用「其他模块」的 internal 包 */
    @ArchTest
    static final ArchRule internal_not_accessed_across_modules = SlicesRuleDefinition
            .slices()
            .matching("com.hify.(*)..")
            .namingSlices("$1")
            .should()
            .notDependOnEachOther()          // 基线：切片间不互相依赖
            .ignoreDependency(               // 放行：对 api 包的依赖
                    DescribedPredicate.alwaysTrue(),
                    JavaClass.Predicates.resideInAPackage("com.hify.*.api.."))
            .ignoreDependency(
                    DescribedPredicate.alwaysTrue(),
                    JavaClass.Predicates.resideInAPackage("com.hify.common.."));

    /** web 层不许直接注入 Mapper，必须过 Service */
    @ArchTest
    static final ArchRule web_must_not_touch_mapper = noClasses()
            .that().resideInAPackage("..internal.web..")
            .should().dependOnClassesThat().resideInAPackage("..internal.mapper..");

    /** api 包不许反向依赖 internal 包（否则对外契约会漏出实现细节） */
    @ArchTest
    static final ArchRule api_must_not_depend_on_internal = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..internal..");
}
```

> 上面是规则意图与骨架，具体 API 以 ArchUnit 实际版本为准；总量控制在 30 行以内。**这三条规则跑在 `mvn test` 里，是硬门槛，不允许 `@Disabled`。**

### 4.5 明确不引 Spring Modulith

过程稿倾向引 Spring Modulith，那是**在方案 B 的前提下**——单模块里需要有人模拟出模块边界。选了 C 之后：

| Modulith 提供的 | 在 C 下 |
|---|---|
| 模块依赖校验 | ✅ pom 已经做了，且是编译期，比 Modulith 的运行时 `verify()` 更早、更硬 |
| `@NamedInterface` 封装 | ✅ 4.4 的 ArchUnit 规则已覆盖 |
| 模块依赖图生成 | ⚪ 有点用，但 8 个模块的依赖图（3.3 的表）手写一次就够，不值一个 starter |
| 模块级切片测试 | ⚪ 多模块下每个模块本来就能独立跑测试 |
| 事件外部化 | ❌ 一期不用事件（4.3） |

**结论：不引。** 这同时结清了过程稿「待定项 1」。

---

## 五、`hify-common` 的边界（本决策的最大风险点）

过程稿的方案 C 里有独立的 `hify-infra`，本决策把它并进了 `hify-common`。合并本身是对的（少一个模块少一份 pom），但**「公共模块」是所有项目里最容易腐化的地方**：任何一个「不知道该放哪」的类都会往这儿扔，最后它变成一个所有模块都依赖、谁都不敢动的泥球。

所以给它三条硬边界：

### 5.1 准入规则

**只有同时满足以下三条，才能进 `hify-common`：**

1. **无业务含义**——不出现 Agent / 会话 / 文档 / 工具 这类领域概念；
2. **不依赖任何业务模块**——`hify-common` 的 pom 里不允许出现 `hify-*`；
3. **有两个及以上的实际使用方**——**只有一个使用方的东西，放使用方模块里**。

第 3 条最容易被违反，也最关键。典型反例：把 `VectorStore` 接口提到 common——它只有 knowledge 用，提出去只会让 knowledge 的核心逻辑漏在模块外面，还给人「这是公共设施，谁都能用」的错误暗示。

### 5.2 一期 `hify-common` 的完整内容

```
com.hify.common
├── result/       # 统一响应体 R<T>、PageQuery / PageResult
├── exception/    # BizException 体系 + ErrorCode + @RestControllerAdvice 全局异常处理
├── constant/     # 全局常量、通用枚举
├── util/         # 无状态工具类（JSON、时间、字符串）
└── crypto/       # ★ API Key 加解密（provider 与 mcp 两个使用方，达标进入）
```

**就这五个包。** 出现第六个包时，先按 5.1 逐条核对——大概率是提前抽象。

### 5.3 被合并掉的 `infra` 里那些东西，各自去哪

| 原 `infra` 内容 | 归属 | 理由 |
|---|---|---|
| `crypto` API Key 加解密 | **`hify-common/crypto`** | 两个使用方（provider、mcp），满足 5.1 |
| `FileStorage` 接口 + `LocalFileStorage` | **`hify-knowledge/internal/storage`** | 一期唯一使用方是知识库文档上传。**出现第二个使用方（如 Agent 头像、工作流附件）时再上移到 common** |
| SSE 线程池、Redis、MyBatis-Plus、Web / CORS 全局配置 | **`hify-app/config`** | 这些是「应用级」而非「模块级」配置，属于启动模块 |
| 模块自己的配置类（`@ConfigurationProperties`） | **各模块 `internal/config`** | 配置归模块所有，前缀 `hify.{module}.*` |

### 5.4 通用 DTO ≠ 业务 DTO

`hify-common` 里允许放 DTO，但**只限无业务含义的**：`R<T>`、`PageQuery`、`PageResult`。

**业务 DTO 一律放各模块的 `api` 包**（`AgentConfig`、`RetrievedChunk`、`ToolSpec`…）。把业务 DTO 提到 common 是最隐蔽的耦合后门——它会让两个本不该有关系的模块通过一个共享结构体长在一起，而 pom 拦不住（谁都能依赖 common）。

---

## 六、比模块结构更重要的四条硬规矩

模块结构决定「找东西方不方便」，下面四条决定「这个架构会不会在半年后名存实亡」。**`CLAUDE.md` 已有的三条（不直连对方 Mapper、不跨模块 JOIN、状态外置）继续有效，这里是同等级别的补充。**

### 6.1 跨模块调用不共享事务

`chat` 调 `knowledge` 时若共用一个事务，数据库层就把两个模块焊死了——将来 knowledge 要独立，事务边界立刻断裂，且断裂表现为**运行期的数据不一致，不是编译错误**。

**规矩：跨模块调用，被调方自己管事务；调用方不指望对方能被自己回滚。**

具体形态：模块 `api` 的方法要么是只读查询，要么自身是完整事务单元（内部 `@Transactional`）。跨模块写操作若需要「一起成功或一起失败」，说明模块划分错了，应该合并模块，而不是靠共享事务粘起来。

### 6.2 跨模块引用只用 ID，不用外键、不用对象引用

`chat` 的 `chat_conversation` 表存 `agent_id`（`bigint`），**不建指向 `agent` 表的外键约束**，Entity 里也不放 `Agent` 对象字段。

外键是数据库层面的跨模块耦合；对象引用会诱导出跨模块 JOIN 和延迟加载。用 ID + 需要时调 `api` 查，代价是每次多一次查询——**3–5 QPS 下这个代价是零**。

### 6.3 只有一个使用方的抽象，放在使用方模块里

见 5.1 第 3 条。判据：**接口放在它唯一使用方的模块内部；出现第二个使用方时再上移。**

### 6.4 `chat` 与 `workflow` 共享能力接口，不共享编排逻辑

这是本项目最容易走偏的一处。两者都要「调模型 / 查知识库 / 调工具」，看起来该统一——甚至可以把「一轮对话」实现成一条固定的工作流。

**明确不这么做。** 共享**下沉到能力接口层**（`ChatModelClient` / `RetrievalService` / `ToolExecutor` 三个 api 接口两边都调，绝不各写一份），但**编排逻辑各有一套**：`chat.internal.runtime` 与 `workflow.internal.executor` 是两份代码。

理由：把 chat 实现成 workflow 的特例，会让 chat 这条主链路（SSE 流式、工具调用循环、上下文裁剪）背上图执行引擎的全部复杂度，而 chat 是使用频率最高、最需要保持简单的一条路。**重复两百行编排代码，换主链路可读，这笔交易在一人项目里划算。**

> 注意：3.2 的「同层禁止依赖」已经**在编译期封死**了 `chat ↔ workflow` 互相引用的可能。这是选 C 相对 B 的一处实打实的收益——这条最容易被违反的规矩，现在不需要靠记性。

---

## 七、分级设计：不是所有模块都用同一套内部结构

一刀切（比如全上六边形 / DDD 四层）是错的。八个模块性质差别很大，内部结构分级：

### 7.1 薄模块：`agent` + 各模块的管理面

**形态**：Controller 收 Request DTO → Service 直接操作 Entity → Mapper 落库 → 返回 Response DTO。
**两层对象（DTO + Entity），中间不造第三种。**

provider / agent / mcp / knowledge 的管理面加起来大概二十来个接口，全是这个形状，套任何更重的结构都是纯亏。

### 7.2 厚模块：`chat` / `workflow`，以及 `knowledge` 的处理链

**形态**：持久化那部分仍然是薄的两层；**业务逻辑跑在独立的领域对象上，外部依赖走接口**。

借用六边形架构的**思想**（独立领域对象 + 面向接口的外部依赖），但**不铺开它的目录形制**（不搞 domain / application / adapter 三层，不做 PO ↔ domain ↔ DTO 两次转换）。

以 workflow 为例：

```java
/**
 * 节点执行器。
 * <p>新增节点类型只需增加一个实现类，图执行主流程零改动。
 */
public interface NodeExecutor {

    /** 本执行器负责的节点类型，如 "llm" / "retrieval" / "tool" / "condition" */
    String nodeType();

    /**
     * 执行单个节点。
     *
     * @param node 节点定义（来自 JSON 解析）
     * @param ctx  执行上下文，含变量表与上游节点输出
     * @return 节点执行结果
     */
    NodeResult execute(Node node, NodeContext ctx);
}
```

Spring 把全部实现注入成 `Map<String, NodeExecutor>`，图执行器按 `nodeType` 分派。

### 7.3 各模块分级速查

| 模块 | 内部结构 | 独立领域对象 |
|------|------|------|
| `provider` | 薄（`client` 包除外） | 否 |
| `agent` | 薄 | 否 |
| `mcp` | 管理面薄，`client` 独立 | 部分（`ToolSpec` / `ToolResult`） |
| `knowledge` | 管理面薄，`pipeline` / `vector` / `storage` 独立 | 是（`Segment` / `Embedding`） |
| `chat` | 持久化薄，`runtime` / `sse` 独立 | 是（`ChatContext` / `Message`） |
| `workflow` | 持久化薄，`definition` / `executor` 独立 | 是（`Graph` / `Node` / `NodeContext`） |

---

## 八、模块数量封顶与新增门槛

**一期 8 个后端模块封顶。** 多模块结构最大的隐性风险不是重，是**切太碎**——每多一个模块，就多一个 pom、多一处依赖声明、多一次「这个类到底该放哪」的犹豫。

**新增第 9 个模块，必须同时满足以下三条：**

1. 它有**独立的业务能力边界**，而不只是「一堆工具类」或「一层技术设施」；
2. 至少有**两个已有模块**需要依赖它（只有一个使用方 → 直接放那个使用方里，见 6.3）；
3. 不新增模块的话，会导致**已有模块之间产生本不该有的依赖**。

不满足就放进已有模块的 `internal` 子包。**「未来可能会用」不是理由。**

反向的门槛同样存在：**如果某个模块半年内始终只有一个 Controller + 一个 Service，且没有第二个模块依赖它的 `api`，应当考虑合并回去。** 一期不预判哪个会触发。

---

## 九、工程实操约定

### 9.1 Maven 骨架

**父 POM（`hify/pom.xml`）**：继承 `spring-boot-starter-parent`，聚合全部模块，统一在 `<dependencyManagement>` 里管版本；**子模块的 `<dependency>` 一律不写 `<version>`**。

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.x.x</version>
</parent>

<groupId>com.hify</groupId>
<artifactId>hify</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>hify-common</module>
    <module>hify-provider</module>
    <module>hify-agent</module>
    <module>hify-knowledge</module>
    <module>hify-mcp</module>
    <module>hify-chat</module>
    <module>hify-workflow</module>
    <module>hify-app</module>
</modules>
```

> `<modules>` 的顺序按依赖层次从底到顶写。Maven 会自行拓扑排序，这么写只是为了让人一眼看出层次。

**`spring-boot-maven-plugin` 只在 `hify-app` 里声明**——`spring-boot-starter-parent` 不会自动绑定 `repackage`，只要不在子模块声明，其余模块自然产出普通 jar，无需 `<skip>`。

### 9.2 包名与命名

| 项 | 约定 |
|---|---|
| Java 包名 | `com.hify.{module}`，与 artifactId `hify-{module}` 一一对应 |
| 模块内顶层子包 | 只有 `api` 和 `internal` 两个（`common` 例外，见 5.2） |
| 数据库表名 | 模块前缀：`provider_*` / `agent_*` / `chat_*` / `kb_*` / `mcp_*` / `wf_*` |
| 配置项前缀 | `hify.{module}.*` |

### 9.3 配置、脚本、测试的归属

| 项 | 归属 | 说明 |
|---|------|------|
| `application.yml` 主配置 | `hify-app/src/main/resources/` | **唯一的主配置文件**，含数据源、Redis、Flyway、日志、SSE 线程池参数 |
| `@ConfigurationProperties` 类 | 各模块 `internal/config` | 配置的**声明**归模块，配置的**值**归 app |
| Flyway 脚本 | 各模块 `src/main/resources/db/migration/{module}/` | Flyway 递归扫描 classpath 下的 `db/migration`，跨 jar 也能扫到；**脚本随模块走，模块搬家脚本一起搬** |
| Flyway 版本号 | **时间戳式** `V20260811_01__create_agent_table.sql` | 版本号要求**全局唯一**，多目录下用递增序号必冲突 |
| `@MapperScan` | `hify-app` 启动类：`@MapperScan("com.hify.*.internal.mapper")` | 通配覆盖全部模块，新增模块零配置 |
| 组件扫描 | 默认（`HifyApplication` 在 `com.hify` 包下） | 自动覆盖全部模块 |
| 单元测试 | 各模块自己的 `src/test/` | 不起 Spring 上下文 |
| 集成测试 + ArchUnit | `hify-app/src/test/` | 只有这里能拿到完整上下文和全部类 |

### 9.4 日常构建（缓解 2.5.1 的反馈延迟）

| 场景 | 命令 / 做法 |
|------|------|
| IDE 内开发 | **IDEA 用自身编译器（不勾选 delegate build to Maven）**，reactor 内模块走源码，改完立即报错，无需 `mvn install` |
| 只构建某模块及其上游 | `mvn -pl hify-chat -am test` |
| 本地跑起来 | `mvn -pl hify-app -am spring-boot:run` |
| 全量构建 | `mvn -T 1C clean package`（8 模块并行） |
| Docker 构建 | 多阶段：第一阶段 `mvn package`，第二阶段只 COPY `hify-app/target/*.jar` |

### 9.5 `hify-web`（前端）与 `deploy`

**`hify-web` 不进 Maven reactor**，父 pom 的 `<modules>` 里没有它。

理由：引 `frontend-maven-plugin` 会让每次后端构建都跑一遍 npm，一个人开发时前后端改动频率完全不同步，这个代价每天付、收益为零。前端构建走独立命令（`npm run build`）或 Docker 多阶段构建。

**`deploy/`** 放 `docker-compose.yml`、`.env.example`、Nginx 配置、初始化脚本。约束沿用 `03_Hify部署与运维（基准）.md`：容器内存限 1GB / JVM `-Xmx512m`、MySQL 数据与上传文档与日志三个目录挂 volume、Nginx `proxy_buffering off`。

---

## 十、扩容时真正要改的地方

代码组织为扩容留的口子，全部内容就是下面这张表——**不是模块化本身让系统能扩，而是模块化让这几处替换不会外溢。**

| # | 要改的地方 | 改动范围 | 触发阈值 |
|---|------|------|------|
| 1 | **向量检索换实现**：`InMemoryVectorStore` → Qdrant / Chroma | `hify-knowledge/internal/vector/` **一个包**，换一个 `VectorStore` 实现类 | 分段数 > 2 万 |
| 2 | **上游 LLM 的 HTTP 客户端换非阻塞** | `hify-provider/internal/client/` **一个包** | 峰值并发 SSE > 50 |
| 3 | **JVM 堆 + SSE 线程池参数** | `hify-app/resources/application.yml` + 启动参数，零代码 | 同上 |
| 4 | **消息表归档 / 分区** | `hify-chat/internal/mapper` + 一个归档任务 | 消息表 > 500 万行 |
| 5 | **多实例 + Nginx 负载均衡** | `deploy/`，零代码（前提：状态已外置） | 单实例 CPU 持续 > 70% |

**五项里只有 1、2 涉及代码，且各自封闭在一个模块的一个包内。**

> **多实例下 SSE 不需要跨实例广播，也不需要粘性会话。** Hify 的 SSE 是「一次请求 → 流式返回这次请求的结果」，整条流的生命周期在同一个 HTTP 连接、同一个实例内，天然不跨实例。只要会话上下文外置到 Redis / MySQL，加实例就是改 Nginx upstream。
> 唯一例外是**工作流做成异步执行**（提交任务 → 另开通道订阅进度），那时才需要任务表 + 跨实例通知。见待定项 2。

---

## 十一、一句话结论

> **Maven 多模块的模块化单体：8 个后端模块按业务能力切分，每模块 `api` 对外 / `internal` 对内，依赖方向四层向下、写死在 pom 里由编译器强制，再用 30 行 ArchUnit 补上「依赖之后只能看 api」这一条。运行时仍是一个进程、一个 jar、一个数据库。**
> **选 C 而非 B 的决定性理由有两条：仓库里还没有一行代码，此刻选 C 的迁移成本为零；以及代码主要由模型生成，「靠自觉」的前提不成立，唯一可靠的护栏是编译不过。**
> **真正决定这个架构半年后是否名存实亡的，不是模块结构，是四条硬规矩：跨模块不共享事务、只用 ID 不用外键、只有一个使用方的抽象不上移、共享能力接口而不共享编排逻辑。**

---

## 十二、本决策结清与新增的待定项

### 12.1 已结清

| 原待定项 | 结论 |
|---|---|
| 过程稿待定项 1：引不引 Spring Modulith | ❌ **不引**。多模块下 pom 已在编译期完成校验，Modulith 的核心价值消失（见 4.5） |
| 过程稿待定项 3：`chat` 与 `workflow` 编排逻辑是否合并 | ❌ **不合并，且不预留合并接口**。3.2 的同层禁止依赖已在编译期封死互相引用（见 6.4） |
| `CLAUDE.md` 模块边界四条规矩中的「不跨模块直连 DAO / Mapper」 | ✅ 由 pom + ArchUnit 联合强制，不再靠自觉 |

### 12.2 仍待定

| # | 待定项 | 说明 | 倾向 |
|---|--------|------|------|
| 1 | **工作流是否异步执行** | 同步则多实例零成本；异步则需任务表 + 跨实例通知，影响第十节的结论 | 倾向一期同步执行 |
| 2 | **是否引 MapStruct** | 薄模块的 DTO ↔ Entity 转换；多模块下每个模块要各自配置注解处理器 | 待定，手写也可接受 |
| 3 | **审计日志 / 用量统计走同步还是 Spring 事件** | 4.3 里「事件」的唯一候选场景 | 倾向 `@TransactionalEventListener`，但仅限这一处 |
| 4 | **`hify-common` 是否需要拆成 `common` + `infra` 两个模块** | 若 5.2 的五个包持续膨胀（尤其 `crypto` 之外又长出技术设施），拆分门槛按第八节判 | 一期合并，观察 |

### 12.3 需同步更新的其它文档

| 文档 | 需要改什么 |
|---|---|
| `../../../hify/CLAUDE.md` | ① 模块名 `tool` → `mcp`；② 模块边界四条规矩改为引用本文档第六节；③ 补充「Maven 多模块 + `api`/`internal`」的目录约定 |
| `../../02-产品决策/03_Hify部署与运维（基准）.md` | 无需改动（本决策不影响部署形态），确认一次即可 |
