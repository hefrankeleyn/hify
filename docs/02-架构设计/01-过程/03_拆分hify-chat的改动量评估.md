# 拆分 hify-chat 为独立服务：改动量评估（过程稿）

> 🔧 **过程稿**，用于验证既有规范是否真的「留了口子」，不是拆分计划。
> 依据：`../02-决策/01_Hify代码组织（基准）.md`、`../02-决策/02_Hify模块内部分层（基准）.md`
> 整理日期：2026-08-11

---

## 一、结论

> **约 8–12 人天，其中一半以上耗在「流式响应跨进程」这一个问题上。业务代码几乎零重写。**

| 项 | 判断 |
|---|---|
| 代码搬迁 | **零重写**，`hify-chat/` 整个目录搬走，包名不变 |
| 数据库 | **零重写**，`chat_` 前缀两张表按前缀筛出即可；且**不必拆库** |
| 事务 | **零改动**，规范早已禁止跨模块共享事务 |
| 状态 | **零改动**，SSE 生命周期本就在单实例内 |
| 跨模块调用 | **4 个接口要改成远程调用**——3 个简单，1 个难 |
| 真正的难点 | `provider` 的**流式**接口跨进程，且 `provider` 会被迫一分为二 |

---

## 二、改动清单

`hify-chat` 现有依赖 4 个模块（`agent` / `provider` / `knowledge` / `mcp`），它自己的 `@ModuleApi` 为空——**没有任何模块依赖 chat**。这是最好的拆分位置：**只出不进，改动全在出边**。

| # | 要做的事 | 具体动作 | 人天 |
|---|---|---|---|
| 1 | 模块搬迁 | `hify-chat/` 移出 reactor，加 `spring-boot-maven-plugin`、启动类、`application.yml`；`hify-common` 发成 jar 供两边依赖 | 0.5 |
| 2 | `AgentQueryService` 远程化 | 1 个方法，`GET /internal/agents/{id}/config`。原接口不变，换一个 HTTP 实现类注入 | 0.5 |
| 3 | `RetrievalService` 远程化 | 1 个方法，`POST /internal/retrieval` | 0.5 |
| 4 | `ToolExecutor` 远程化 | 2 个方法，`POST /internal/tools/execute` + `GET /internal/tools` | 0.5 |
| 5 | **`ChatModelClient` 远程化（流式）** | 见第三节 | **3–5** |
| 6 | 服务间认证与配置 | 内部调用共享密钥 + 服务地址配置项 + 超时/重试 | 1 |
| 7 | 部署 | 多一个容器、Nginx 路由拆分、健康检查 | 1 |
| 8 | 联调与失败注入测试 | 四条链路各断一次，验证降级行为仍符合规范 5.5 | 2 |
| | **合计** | | **9–11** |

第 2/3/4 项的形态完全一样，都是「接口不动，换一个实现类」：

```java
// 拆分前:hify-agent 模块内的实现,Spring 直接注入
// 拆分后:hify-chat 里的一个 HTTP 实现,接口签名一个字都不改
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteAgentQueryService implements AgentQueryService {

    private final RestClient agentServiceClient;

    @Override
    public AgentConfig getConfig(Long agentId) {
        log.debug("远程查询 Agent 配置, agentId={}", agentId);
        // 失败处理策略与拆分前一致,规范 5.5 已定
        return agentServiceClient.get().uri("/internal/agents/{id}/config", agentId)
                .retrieve().body(AgentConfig.class);
    }
}
```

**调用方 `ChatRuntime` 一行都不用改**——它注入的一直是接口。

---

## 三、唯一真正的难点：`provider` 的流式接口

### 3.1 问题

`ChatModelClient.stream()` 返回的是 token 流，生命周期以分钟计。跨进程后变成「LLM → provider 服务 → chat 服务 → 浏览器」**三跳流式转发**，每一跳都要处理背压、超时、断连取消。

`provider` 挂了或网络抖动，用户看到的是句子说到一半断掉——比一次性接口失败难处理得多。

### 3.2 三个方案

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 两跳 SSE 转发 | provider 暴露 SSE 端点，chat 收到后转发给浏览器 | 能work，但多一跳的断连取消链路要自己接通，是那 3–5 天的主要来源 |
| B. chat 直连 LLM | chat 自己发 HTTP 到 OpenAI 兼容端点 | 最简单，但 provider 的统一管理、API Key 加密全部落空——**否决** |
| C. **`provider/client` 抽成共享 SDK jar** | provider 服务只管配置 CRUD 并下发「baseUrl + 解密后的 key + model」；chat 依赖这个 jar，自己发起流式请求 | **推荐**。流式只剩一跳，Key 仍由 provider 集中管理与加解密 |

### 3.3 这暴露了 `provider` 的一个结构问题

`hify-provider` 实际上混了两种性质的东西：

- **配置管理**（`controller` / `service` / `mapper` / `entity`）—— 该留在中心，是管理面；
- **模型客户端**（`client` 包）—— 是运行时，该跟着调用方走。

拆 chat 的时候，`provider` 会被迫一分为二。这不是缺陷，是**发现得早**——现在的代价是往规范里加一句话，等到拆的时候再发现，代价是 2–3 天重构。

---

## 四、规范省下了什么（反向验证）

拆分时最贵的从来不是搬代码，是**解开那些不该长在一起的东西**。逐条对照现有规范：

| 规范条款 | 若没有它，拆分时要额外做 | 省下 |
|---|---|---|
| **不共享事务 + 长流程拆短事务** | 重划事务边界，引入 Saga 或补偿逻辑 | **5–10 天**，且最容易埋数据不一致 |
| **不跨模块 JOIN** | 把每个跨模块 JOIN 改写成 API 调用 + 内存拼装 | 3–5 天 |
| **只存 id、不建外键、不放对象引用** | 先删外键，再补一致性校验，再拆对象图 | 2–3 天 |
| **跨模块失败必须显式处理（三种降级策略）** | 逐个调用点补超时、重试、降级 | 2–3 天 |
| **状态外置** | 做会话粘连或跨实例共享 | 2 天 |
| **Maven 多模块** | 从单体里刨代码、理 import、拆 pom | 2–3 天 |
| **`@ModuleApi` 契约裁剪** | 先搞清楚「对方到底用了我哪些方法」再设计远程接口 | 1–2 天 |
| | **合计** | **约 17–28 天** |

> **这套规范把拆分成本从 ~30 人天压到 ~10 人天。** 更关键的是压掉的那部分恰好是**最容易出错**的部分（事务与一致性），剩下的 10 天全是机械劳动加一个明确的技术难题。

---

## 五、今天该做的唯一一件事

上面的评估里，只有第三节的 `provider` 结构问题是**现在不做、将来会变贵**的。补一条规范即可，零成本：

> **`provider/client` 包只允许依赖方法入参（`baseUrl` / `apiKey` / `modelName` / 消息列表），禁止注入 `ProviderMapper`、禁止读 `ProviderEntity`、禁止读 Spring 配置。**
>
> 换言之：`client` 包必须是一个**无状态、可被原样抽成独立 jar** 的东西。需要配置就从参数里拿，由 `service.impl` 负责查库、解密后传进来。

这条应当加入 `02_Hify模块内部分层（基准）.md` 的 4.3 表格（`provider/client` 行的硬规则）。

**除此之外，为「将来可能拆 chat」不做任何其它准备**——不预留 RPC 层、不提前抽 Feign 接口、不引服务发现。

---

## 六、什么时候真该拆（以及大概率不用拆）

按 `01_Hify代码组织（基准）.md` 第十节的容量推演，**到几千人量级，`chat` 也不需要独立部署**——它的瓶颈是 LLM 长连接数，解法是加实例 + 调线程池，不是拆服务。

真正可能先触发拆分的是 `knowledge`（文档解析与向量化是 CPU 密集，需要独立扩容），不是 `chat`。

| 触发信号 | 说明 |
|---|---|
| `chat` 需要与其它模块**不同的伸缩节奏或部署周期** | 目前没有 |
| `chat` 的故障需要与管理面**隔离** | 20–50 人内部平台上不成立 |
| 有独立团队维护 `chat` | 一个人开发，不成立 |

> **结论：这套规范的价值不在「方便拆」，在于它让每天写代码时少想一件事——而「万一要拆，10 天而不是 30 天」只是顺带的保险。** 拆分演练的意义是验证边界没长歪，不是准备拆。
