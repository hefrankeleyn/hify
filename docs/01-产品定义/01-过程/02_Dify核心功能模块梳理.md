# Dify 核心功能模块梳理

> 对象：Dify（https://dify.ai）
> 整理日期：2026-08-10

---

## 一、模型层（平台地基）

| 模块 | 说明 |
|---|---|
| **模型供应商 Providers** | 统一接入 OpenAI、Anthropic、通义、Ollama / 本地模型等数十家供应商，屏蔽各家 API 差异。 |
| **系统模型设置** | 为推理、Embedding、Rerank、语音等分别指定默认模型，全局应用共用一套配置。 |
| **多模型类型** | 除 LLM 外还支持 Embedding、Rerank、STT / TTS、内容审核（Moderation）等模型能力。 |

## 二、应用编排（你要做哪类应用）

| 模块 | 说明 |
|---|---|
| **工作流 Workflow** | 面向自动化 / 批处理任务的可视化有向流程编排，官方主推形态。 |
| **对话流 Chatflow** | 带会话记忆的多轮对话工作流，多了 Memory、Answer 流式节点、富文本与图片支持。 |
| **聊天助手 Chatbot** | 提示词 + 知识库即可搭出的单角色问答机器人，最轻量的入门形态。 |
| **智能体 Agent** | 通过 ReAct / Function Calling 自主选工具、多步完成复杂任务的对话应用。 |
| **文本生成 Text Generation** | 单轮生成任务（翻译 / 摘要 / 分类），输入变量即出结果，适合批量与 API 调用。 |

> 注：后三种现已被官方归为「基础 / 遗留形态」——它们和 Workflow 跑在同一套引擎上，只是界面更简单。Agent 能力正在被**工作流里的 Agent 节点**取代。

## 三、工作流引擎（节点体系）

官网现在把节点按五类归组，比逐个罗列更好记：

| 节点类别 | 说明 |
|---|---|
| **起点 Start** | 入口触发：用户消息、API 调用、定时任务、Webhook、插件事件、文件上传。 |
| **推理与检索** | LLM 调用、问题分类器、参数提取、知识检索——负责「思考」和「找依据」。 |
| **流程控制** | IF/ELSE 条件分支、迭代 Iteration、循环 Loop、变量聚合，负责流程走向。 |
| **动作执行** | 调用内置工具、自定义 API、**MCP 工具**、HTTP 请求、沙箱代码（Python/Node.js）。 |
| **人工介入 Human Review** | 🆕 流程中途暂停，等人审批 / 修改 / 评论 / 转交，含超时兜底——面向敏感操作。 |

## 四、知识与 RAG

| 模块 | 说明 |
|---|---|
| **知识流水线 Knowledge Pipeline** | 🆕 把「抽取 → 清洗 → 分段 → 索引 → 检索测试」做成可复用的流水线，验证通过再挂给应用。 |
| **数据接入 ETL** | 支持本地文件、网页抓取、Notion、在线文档与网盘等多种数据源。 |
| **分段与索引** | 自动 / 自定义分段规则；索引分「高质量」（向量）与「经济」（关键词）两档，权衡效果与成本。 |
| **检索策略** | 向量检索、全文检索、混合检索，可叠加 Rerank 重排提升召回质量。 |
| **元数据 Metadata** | 给文档 / 片段打标签，实现过滤检索和更精细的召回控制。 |

## 五、工具与扩展

| 模块 | 说明 |
|---|---|
| **内置工具** | 预置搜索、绘图、代码、天气等工具，Agent 与工作流可直接调用。 |
| **自定义工具** | 用 OpenAPI / Swagger 定义把任意 REST API 封装成工具。 |
| **MCP 双向支持** | 🆕 既能把任何 MCP Server 当工具用，也能把自己的工作流暴露成 MCP Server 给 Claude Desktop、Cursor 等客户端调用。 |
| **插件市场 Marketplace** | 模型、工具、数据源、MCP 集成的分发入口，可安装也可发布。 |
| **插件体系** | 分模型插件、工具插件、Agent 策略、Extension、Bundle 等类型。 |

## 六、发布与集成

| 模块 | 说明 |
|---|---|
| **Web 应用** | 一键发布成可分享的独立网页应用，带模板与访问控制。 |
| **嵌入组件 Embed** | 以浮窗 / iframe 形式嵌进任意网站当在线助手。 |
| **后端服务 API** | 每个应用自动暴露 REST API，作为「后端即服务」接进自有产品。 |
| **difyctl CLI** | 命令行运行 Dify 应用，便于终端、脚本、CI 和 AI Agent 调用。 |

## 七、运营与可观测（LLMOps）

| 模块 | 说明 |
|---|---|
| **日志与标注** | 记录每次会话完整输入输出，可人工标注并配置「标注回复」持续优化。 |
| **监控看板** | 统计调用量、Token、费用、用户活跃等运营指标。 |
| **链路追踪 Tracing** | 接入 LangSmith、Langfuse、Opik，追踪工作流内部每一步执行细节。 |

## 八、部署与治理

| 模块 | 说明 |
|---|---|
| **Dify Cloud** | 官方托管 SaaS，零基础设施投入，开箱即用。 |
| **社区版 Community Edition** | 开源 Docker 部署，完整的 Agent 运行时 + 工作流编辑器。 |
| **企业版 Enterprise** | 自托管 / VPC 部署，含 SSO/SAML、RBAC、审计日志、SOC 2 Type II 与 ISO 27001 合规。 |

---

**一句话总览**：Dify = 模型接入（地基）+ 可视化编排（工作流 / Agent）+ 私有知识（RAG）+ 外部能力（工具 / MCP）→ 一键发布成 Web / API / MCP Server，再配上日志与监控闭环。

**相比 7 月那版文档，新增了三处**：知识流水线（Knowledge Pipeline）、人工介入节点（Human Review）、MCP 双向支持。其中 MCP 双向能力对 Hify 有直接参考价值——目前 Hify 一期规划的是「接入 MCP 工具」这一个方向，Dify 已经把「反向暴露成 MCP Server」也做了，这个后置到二期比较合适。

---

**参考来源**
- [Dify 官网](https://dify.ai/)
- [Dify Workflow Studio](https://dify.ai/workflows)
- [Dify Docs — Key Concepts](https://docs.dify.ai/en/use-dify/getting-started/key-concepts)
- [Dify v1.6.0: Built-in Two-Way MCP Support](https://dify.ai/blog/v1-6-0-built-in-two-way-mcp-support)
- [Dify Agent Node Introduction](https://dify.ai/blog/dify-agent-node-introduction-when-workflows-learn-autonomous-reasoning)
