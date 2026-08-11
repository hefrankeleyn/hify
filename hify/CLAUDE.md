## 项目概述

Hify 是一个简版的 AI Agent 开发平台（参考 Dify），可本地部署，
面向团队内部小规模使用（20-50 人同时在线）。

### 做什么
- 多模型提供商管理（OpenAI、Claude、Gemini、Ollama）
- Agent 创建与配置（选模型、绑工具、设系统提示词）
- 对话引擎（流式响应、多轮对话、上下文管理）
- 知识库 + RAG（一期只支持 TXT 文档，固定长度分块）
- 简版工作流（JSON 配置，线性 + 条件分支，不做可视化拖拽）
- MCP 工具接入（Agent 可通过 MCP 协议调用外部工具）
- 管理控制台（模型管理、Agent 配置、对话界面）

### 不做什么
- 不做可视化工作流拖拽编排
- 不做多租户 / 权限体系
- 不做插件市场、计费系统
- 不做文本生成应用、WebApp 发布、嵌入组件
- 不做标注与微调

### 技术栈
后端：Spring Boot 3.x + MyBatis-Plus + MySQL 8.x + Redis 7.x
前端：Vue 3 + TypeScript + Vite + Element Plus
容器化：Docker + Docker Compose

### 部署与运维预期
- Docker Compose 本地一键部署，JVM 内存设上限（-Xmx512m）
- 目标：20-50 人同时在线，峰值 3-5 QPS，瓶颈在 LLM 长连接
- 缓存：Redis Cache-Aside（配置信息 + 会话上下文）
- 监控：起步 Actuator + 日志，后期 Prometheus + Grafana

## 架构设计

### 应用架构
模块化单体。一个 Spring Boot 应用，Maven 多模块组织。

模块划分：
- hify-provider：模型提供商管理
- hify-agent：Agent 管理与配置
- hify-chat：对话引擎
- hify-mcp：MCP 工具管理与调用
- hify-workflow：工作流编排与执行
- hify-knowledge：知识库与 RAG
- hify-common：公共模块

依赖原则：单向依赖，不循环。共用逻辑下沉 hify-common。

### 代码组织
每个业务模块统一结构：controller / service / mapper / entity / dto / config

分层规则：
- Controller 只做参数校验和调用 Service，不写业务逻辑
- Service 处理所有业务逻辑，包括事务管理
- 跨模块调用走 Service 接口，不直接引用其他模块的 Mapper 或 Entity
- Entity 不直接返回给前端，用 DTO 做转换

### 外部调用处理
- LLM 调用使用独立线程池，和业务请求隔离
- Resilience4j 熔断，每个提供商独立熔断器
- 同步调用 60s 超时，SSE 流式 120s 超时，连通性测试 10s
- 按异常类型区分重试：网络抖动重试、认证失败不重试、限流退避重试
- 流式响应使用 SseEmitter + 独立线程池，不引入 WebFlux
