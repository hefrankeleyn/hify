# Hify 数据模型（基准）

> 📌 **本文档是 Hify 核心数据表与表间关系的唯一依据（source of truth）**，如与其它任何文档冲突，**以本文档为准**。
> 本文档只定义**表与关系**，不定义字段（仅在字段选择直接决定关系形态时说明）。
> 图片来源：[关系汇总](https://static001.geekbang.org/resource/image/39/6a/39e2ed65a60ec6708b62caa271db726a.png) · [功能划分](https://static001.geekbang.org/resource/image/2d/1d/2d69d6919860b321a022ba0a84cb371d.jpg)
> 本地副本：`../assets/hify-数据模型关系汇总.png`、`../assets/hify-数据模型功能划分.jpg`
> 上游依据：`02_Hify模块内部分层（基准）.md`（不跨模块 JOIN、只存 ID 不建外键、软删除）、`03_Hify部署架构（基准）.md`（MySQL + pgvector 双库）
> 推演过程：`../01-过程/09_核心数据表与关系.md`（过程稿，分歧处以本文档为准）
> 决策日期：2026-08-11

---

## 一、决策：16 张表，按功能域划分

![按功能划分](../assets/hify-数据模型功能划分.jpg)

| 功能域 | 表 | 所属模块 | 库 |
|---|---|---|---|
| **模型管理** | `model_provider` | `hify-provider` | MySQL |
| | `model` | `hify-provider` | MySQL |
| **知识库** | `knowledge_base` | `hify-knowledge` | MySQL |
| | `document` | `hify-knowledge` | MySQL |
| | **`document_chunk`** | `hify-knowledge` | ⚠️ **pgvector** |
| **工具** | `tool` | `hify-mcp` | MySQL |
| **Agent** | `agent` | `hify-agent` | MySQL |
| | `agent_knowledge_base` | `hify-agent`（见 3.1） | MySQL |
| | `agent_tool` | `hify-agent`（见 3.1） | MySQL |
| **工作流** | `workflow` | `hify-workflow` | MySQL |
| | `workflow_node` | `hify-workflow` | MySQL |
| **对话** | `conversation` | `hify-chat` | MySQL |
| | `message` | `hify-chat` | MySQL |
| | `message_reference` | `hify-chat`（见 3.2） | MySQL |
| **用户** | `user` | `hify-app` | MySQL |
| | `api_key` | `hify-app` | MySQL |

**合计：MySQL 15 张 + pgvector 1 张。** 其中 3 张是中间表（`agent_knowledge_base`、`agent_tool`、`message_reference`）。

**每张表都有的五个字段**（`id` / `create_time` / `update_time` / `deleted` / `creator_id`）不在下文重复。

---

## 二、关系汇总

![关系汇总](../assets/hify-数据模型关系汇总.png)

| # | 关系 | 类型 | 跨模块 | 实现方式 |
|:--:|---|---|:---:|---|
| 1 | `model_provider` → `model` | 一对多 | 否 | `model.provider_id` 逻辑引用 |
| 2 | `knowledge_base` → `document` → `document_chunk` | 一对多链 | 否 | 后一跳**跨库**，见 2.1 |
| 3 | `agent` ↔ `knowledge_base` | 多对多 | **是** | 中间表 `agent_knowledge_base`，见 3.1 |
| 4 | `agent` ↔ `tool` | 多对多 | **是** | 中间表 `agent_tool`，见 3.1 |
| 5 | `agent` → `model` | 多对一 | **是** | `agent.model_id` 逻辑引用 |
| 6 | `workflow` → `workflow_node` | 一对多 | 否 | `workflow_node.workflow_id` |
| 7 | `workflow_node` → `model` | 多对一 | **是** | `workflow_node.model_id`（仅 LLM 节点，见 5.3） |
| 8 | `conversation` → `agent` / `workflow` | 多对一（**二选一**） | **是** | 多态关联，见 3.3 |
| 9 | `conversation` → `message` | 一对多 | 否 | `message.conversation_id` |
| 10 | `message` ↔ `document_chunk` | 多对多（**RAG 溯源**） | **是 + 跨库** | 中间表 `message_reference`，见 3.2 |
| 11 | `user` → `conversation` | 一对多 | **是** | `conversation.user_id` |
| 12 | `user` → `api_key` | 一对多 | 否 | `api_key.user_id` |

### 2.1 `document` → `document_chunk` 是跨库关系

`document` 在 MySQL，`document_chunk` 在 pgvector（存分段文本 + 向量）。

**分段的文本内容放 pgvector，MySQL 里不再建一份。** 检索命中后一次查询就拿到可直接注入提示词的文本，不用回 MySQL；跨库一致性也只需维护到 `document` 级别，不用维护到分段级别。

**一致性维护**（不做分布式事务）：

1. 删文档 → `document` 软删除，检索时按 `document_id` 过滤；
2. 每日 CronJob 清理已软删文档对应的 `document_chunk` 行；
3. 重新分块 → 先删旧 chunk 再写新的；失败则文档状态置「处理失败」，可重试。

---

## 三、三条必须讲清的实现约定

图上表达不了，但不定下来就会写错的三处。

### 3.1 跨模块中间表归「持有绑定关系」的那一方

`agent_knowledge_base` 连接 `hify-agent` 与 `hify-knowledge`，`agent_tool` 连接 `hify-agent` 与 `hify-mcp`。**两张中间表都归 `hify-agent` 模块所有**——绑定关系是 Agent 的配置，不是知识库或工具的属性。

**关键约定：中间表只存两个 ID，绝不与对方主表 JOIN。**

```sql
-- ✅ 允许:只查本模块的中间表
SELECT knowledge_base_id FROM agent_knowledge_base WHERE agent_id = ?;

-- ❌ 禁止:跨模块 JOIN
SELECT kb.name FROM agent_knowledge_base akb
  JOIN knowledge_base kb ON kb.id = akb.knowledge_base_id   -- 违反硬规矩
  WHERE akb.agent_id = ?;
```

需要知识库名称时，拿到 ID 列表后调 `hify-knowledge` 的 `service` 接口批量查。**多一次查询，但保住了模块边界**——二期分库、三期拆服务时这两张表跟着 `agent` 走，不需要任何改动。

> **这是「多对多用中间表」与「不跨模块 JOIN」能同时成立的原因**：中间表本身在一个模块内，跨模块的只是它存的那个 ID 值。

### 3.2 `message_reference` 存**引用快照**，不只存 chunk_id

这是本文档最需要注意的一处。

`message` 在 MySQL，`document_chunk` 在 pgvector——中间表 `message_reference` 只能落在 MySQL（跟 `hify-chat` 走）。如果它只存 `chunk_id`，展示引用时每次都要跨库回查 pgvector。

**两个问题：**

| 问题 | 说明 |
|---|---|
| **① 溯源会失真**（更严重） | chunk 会因重新分块、删文档而消失或变化。只存 id 的话，用户回看三个月前的对话，引用要么是空白，要么指向**已经变了内容的分段**——而溯源的全部意义就是「当时引用的到底是哪段话」 |
| ② 展示要跨库 | 消息列表是高频操作，每次都跨库查 pgvector |

**决策：`message_reference` 存引用时的快照。**

| 存什么 | 作用 |
|---|---|
| `message_id` | 关联消息 |
| `chunk_id` | 指向 pgvector 的逻辑引用（用于「跳转到原文」） |
| `document_id` | 冗余，用于展示来源文档名（不用回查 chunk） |
| **`content_snapshot`** | **引用时的分段文本**——溯源的真实载体 |
| `score` | 相似度得分，便于调检索参数 |

**快照让溯源有了审计属性**：它记录的是「这次回答当时确实基于这段文本」，与知识库后续如何变化无关。

**⚠️ 体积要纳入归档考虑。** `message_reference` 的行数与 `message` 同量级（每条带 RAG 的助手消息约 3 条引用）：

| 规模 | 年增行数 | 快照体积（约 1.5KB/行） |
|---|---:|---:|
| 一期 50 人 | 约 55 万 | 约 800MB / 年 |
| 二期 2000 人 | 约 1100 万 | 约 16GB / 年 |

**一期存完整快照，量完全可控。** 归档时 `message_reference` 与 `message` 按同一个 `conversation` 一起走。若二期体积成为问题，改为「快照截断到 200 字做预览 + 点击时按 `chunk_id` 回查全文」。

### 3.3 `conversation` 绑定执行对象用多态字段

`conversation` 关联 `agent` **或** `workflow`，二选一。两种实现：

| 方案 | 形态 | 评价 |
|---|---|---|
| 两个可空列 | `agent_id` / `workflow_id`，加 CHECK 保证恰有一个非空 | 语义直白，但每加一种执行对象就要加一列 |
| **多态字段（选这个）** | `target_type` (`agent` / `workflow`) + `target_id` | 表结构不随执行对象类型增加而变宽；查询形态统一 |

**决策：`target_type` + `target_id`**，并建 `(target_type, target_id)` 索引以支持「这个 Agent 有多少会话」的反查。

代价是数据库层面无法约束 `target_id` 的指向——但本项目本来就不建任何外键，这一点不构成新增损失。

---

## 四、与既有基准的两处差异

| # | 既有基准 | 本文档 | 处理 |
|:--:|---|---|---|
| 1 | `02_Hify模块内部分层（基准）` 9.2：**表名带模块前缀**（`kb_document` / `chat_message` …） | **不带前缀**（`document` / `message` …） | ✅ 采纳新命名，**但要补一件事**，见 4.1 |
| 2 | 同基准 4.1 Entity 示例：Agent 的绑定关系用 **JSON 数组**（`knowledgeBaseIds` / `mcpToolRefs`） | **中间表**（`agent_knowledge_base` / `agent_tool`） | ✅ 采纳中间表，见 4.2 |

### 4.1 去掉前缀之后，「表的模块归属」要靠本文档维护

前缀原本承担两个作用，去掉后各自的替代方式：

| 前缀的作用 | 替代 |
|---|---|
| 一眼看出表属于哪个模块 | **第一节的分域清单**——16 张表可枚举，把它当基准维护，新增表必须登记 |
| 二期分库时按前缀筛（`SHOW TABLES LIKE 'kb_%'`） | 按第一节清单手工划分。16 张表，一次性工作 |

**新增表时必须同时更新第一节清单**，否则归属信息就丢了——这是采纳新命名后唯一新增的维护成本。

> **命名提醒**：`user` 在 MySQL 8.0 是非保留关键字，可直接作表名；但在 PostgreSQL 中是保留字。本项目 `user` 表在 MySQL，不受影响——只是若将来有表要迁到 pgvector 一侧，需要重新检查。

### 4.2 中间表取代 JSON：多付一次查询，换到反查能力

| | JSON 数组（原） | 中间表（本文档） |
|---|---|---|
| 读 Agent 完整配置 | 一次查询 | **三次查询**（agent + 两张中间表） |
| 反查「哪些 Agent 用了这个知识库」 | `JSON_CONTAINS` 走不了索引 | ✅ **走索引** |
| 表数量 | 1 | 3 |

**反查能力是有用的**——`02_Hify模块内部分层（基准）` 5.3 定的「删除知识库前由前端调 `GET /api/agents?knowledgeBaseId=x` 给影响面提示」，在中间表方案下是一次索引查询，在 JSON 方案下是全表扫。

多出的两次查询在 3–5 QPS 下代价为零，且组装 Agent 配置本来就有 Redis 缓存。**采纳中间表。**

需要同步修订 `02_Hify模块内部分层（基准）` 4.1 的 Entity 示例。

---

## 五、补充建议（图上没有，可逐条驳回）

### 5.1 建议补 `workflow_execution` + `workflow_node_execution`

**当前模型只有工作流的「定义」，没有「执行记录」。**

一期的工作流是 **JSON 配置、没有可视化编辑器**。出错时如果不能定位到「卡在哪个节点、那个节点收到了什么、输出了什么」，调试基本无法进行——只能靠日志翻找。

| 建议新增 | 职责 |
|---|---|
| `workflow_execution` | 一次执行：`workflow_id`、`workflow_version`、状态、入参、最终结果、耗时 |
| `workflow_node_execution` | 节点级：`execution_id`、`node_id`、节点类型、输入、输出、耗时、状态 |

关系：`workflow` → `workflow_execution` → `workflow_node_execution`，均模块内一对多。

**这不是可选的观测增强，是 JSON 工作流方案能落地的前提。** 建议一期就有。

> 顺带：`workflow_execution` 要记录执行时的 `workflow_version`——定义改了以后，历史执行记录仍要能对应到当时的定义，不做级联更新。

### 5.2 建议补 `document_content`（1:1 从表）

`05_Hify扩展路径（基准）` 待定项 1 倾向「TXT 原文存数据库」（避免多副本下的本地文件问题）。原文是 `LONGTEXT` 级别的大字段：

- 放进 `document` 主表 → 每次查文档列表都把全部正文捞出来，几百篇文档就是几十 MB 的无谓传输；
- 拆成 `document` **1:1** `document_content` → 列表查询完全不碰它。

### 5.3 建议 `workflow_node.model_id` 提升为独立列

`workflow_node` 里不同节点类型配置不同，主体应放 `config` JSON。但**模型引用建议单独提一列**（可空，仅 LLM 节点有值）：

- 与 `agent.model_id` 形态一致；
- **模型停用时能一次查全影响面**——`agent` 和 `workflow_node` 两张表各一次索引查询，而不是去 JSON 里扫。

---

## 六、量级与索引

| 量级 | 表 | 必须的索引 / 处置 |
|---|---|---|
| ⚠️ **千万级/年** | `message` | 建表即建 `(conversation_id, id)`；500 万行触发归档 |
| ⚠️ **千万级/年** | `message_reference` | `(message_id)`；**与 `message` 同批归档**（见 3.2） |
| **万～百万级** | `document_chunk`（pgvector） | `(knowledge_base_id, document_id)`；1 万条触发建 HNSW + 加内存 |
| 万级 | `conversation` | `(user_id, update_time)`、`(target_type, target_id)` |
| 万～十万级 | `workflow_node_execution`（若采纳 5.1） | `(execution_id)`；保留 90 天 |
| 千级以下 | 其余全部 | **无需任何优化** |

**只有前三张表需要在设计阶段考虑性能，其余十几张在可预见的未来都是几百行的配置表。** 不要给它们做分区、加缓存或提前优化。

---

## 七、关系分类与一致性

| 类型 | 实例 | 建外键 | 一致性靠什么 |
|---|---|:---:|---|
| **模块内父子** | `provider→model`、`kb→document`、`workflow→node`、`conversation→message`、`user→api_key` | ❌ | 应用层保证；父软删除，子随查询过滤 |
| **跨模块逻辑引用** | `agent→model`、`conversation→agent/workflow`、`workflow_node→model`、`conversation→user` | ❌ | 运行时校验：装配时发现引用失效 → 明确报错 |
| **跨模块中间表** | `agent_knowledge_base`、`agent_tool` | ❌ | 只存 ID，不 JOIN 对方主表（3.1） |
| **跨库引用** | `document→document_chunk`、`message_reference→document_chunk` | ❌（跨库不可能） | 软删除 + 每日对账；引用侧存快照（3.2） |

### 7.1 全库不建任何外键

既有基准只禁止跨模块外键，本文档进一步定：**模块内也不建**。三个理由——一致性（一半有一半没有最容易出错）、软删除下外键约束意义有限、二期分库时零改动。

### 7.2 跨模块引用失效在三处兜住

按 `02_Hify模块内部分层（基准）` 5.3，**管理面保存 Agent 时不校验绑定对象是否存在**（校验会引出 `agent → knowledge`、`agent → mcp` 两条同层依赖，违规）：

| 时机 | 处理 |
|---|---|
| 配置时 | 前端下拉只列启用中的对象，用户选不到不存在的 |
| **运行时**（`chat` 装配配置） | 引用失效 → 明确报错「所选模型已停用，请检查 Agent 配置」 |
| 删除时 | 只停用不物理删；影响面提示由前端调 `GET /api/agents?...` 组合 |

---

## 八、一句话结论

> **16 张表：模型 2、知识库 3、工具 1、Agent 3（含 2 张中间表）、工作流 2、对话 3（含 1 张中间表）、用户 2。其中 `document_chunk` 在 pgvector，其余在 MySQL。**
> **`agent` 是关联最多的表——多对一连 `model`，多对多连 `knowledge_base` 和 `tool`；`message ↔ document_chunk` 的多对多是 RAG 溯源，也是全库唯一一条跨库的多对多。**
> **三条必须记住的实现约定：跨模块中间表归「持有绑定关系」的模块，只存 ID 绝不 JOIN 对方主表；`message_reference` 存引用快照而非只存 chunk_id，否则重新分块后历史溯源会失真；`conversation` 用 `target_type` + `target_id` 绑定执行对象。全库不建任何外键。**

---

## 九、待定项

| # | 待定项 | 说明 | 倾向 |
|---|---|---|---|
| 1 | **`api_key` 是否一期就建** | 它对应「API 发布调用」能力，而 `01_Hify产品决策（基准）` 的一期范围里没有这一项（明确不做 WebApp 发布 / 嵌入组件） | **需确认**。若一期不做 API 发布，表暂不建，模型保留 |
| 2 | **`user` 是否一期就建** | 依赖 `01_Hify产品决策（基准）` 待定项 2（登录与角色是否做） | 做最简登录，表放 `hify-app` |
| 3 | **是否采纳 5.1 的执行记录表** | 不做则 JSON 工作流基本无法调试 | **建议采纳** |
| 4 | **是否采纳 5.2 的 `document_content`** | 依赖扩展路径待定项 1（TXT 存数据库） | 建议采纳 |
| 5 | **是否采纳 5.3 的 `model_id` 提列** | 影响「模型停用影响面」的查询方式 | 建议采纳 |
| 6 | **`message_reference` 快照的截断策略** | 二期体积可能到 16GB/年 | 一期存全文；超 10GB 改为 200 字预览 + 回查 |

---

## 十、与既有文档的关系

| 文档 | 关系 |
|---|---|
| `../01-过程/09_核心数据表与关系.md` | ⚠️ 过程稿。**分歧处以本文档为准**——主要是表名（有前缀 → 无前缀）与 Agent 绑定关系（JSON → 中间表）。该文档新增的 `message_reference`（RAG 溯源）此前没有，以本文档为准 |
| `02_Hify模块内部分层（基准）.md` **9.2 表命名** | ❌ **被本文档第一节取代**（不再带模块前缀，归属改由分域清单维护） |
| `02_Hify模块内部分层（基准）.md` **4.1 Entity 示例** | ⚠️ 其 `knowledgeBaseIds` / `mcpToolRefs` 的 JSON 字段写法**被中间表取代**，示例需修订 |
| `02_Hify模块内部分层（基准）.md` **6.1–6.4 硬规矩** | ✅ 全部继续有效，本文档 3.1 / 7.1 / 7.2 是它们在数据层的落实 |
| `03_Hify部署架构（基准）.md` **3.5 pgvector** | ⚠️ 其表名 `kb_segment_vector` → **`document_chunk`**；结构不变 |
| `04_Hify一期性能处置（基准）.md` **5.1 建表规范** | ⚠️ 其 `chat_message (conversation_id, id)` → **`message (conversation_id, id)`**；规则不变 |
