# Hify 部署架构：Docker + K8s（过程稿）

> 🔧 **过程稿**。给出当前阶段的生产部署架构：组件清单、请求流转、各组件职责。
> ⚠️ **本文档推翻了两条既有基准决策**，见第〇节。拍板后需同步修订基准文档。
> 关联：`04_LLM调用的线程超时重试与容错.md`（线程池与超时值）、`05_SSE在SpringMVC下的实现与WebFlux取舍.md`（SSE 的代理配置）
> 整理日期：2026-08-11

---

## 〇、先声明两处与基准的冲突

| 基准文档原文 | 本文档 | 影响 |
|---|---|---|
| `02-决策/../03_Hify部署与运维（基准）.md`：**「明确不做：K8s、Helm、CI/CD 流水线编排。20–50 人的内部平台上这些是纯负债。」** | 生产环境用 **Docker + K8s** | 需修订基准第 1 节 |
| `02-产品决策/01_Hify产品决策（基准）.md` 待定项 1：**「一期不引入向量库：向量存 MySQL JSON，检索时内存暴力计算」** | 引入 **PostgreSQL + pgvector** | 需修订该待定项；并且 `03-架构设计/02-决策/01` 第十节第 1 项「向量检索换实现」的触发条件（分段数 > 2 万）**已提前兑现** |

**关于 K8s 这条，成本取决于集群是谁的**——这是判断它是不是「纯负债」的唯一分水岭：

| 情形 | 判断 |
|---|---|
| **公司已有 K8s 平台，有人运维** | ✅ 合理。你只交付镜像 + 一份 manifest，集群本身的成本不由你承担，反而白拿滚动更新、探针自愈、配置与密钥管理 |
| **要为这个项目自建集群** | ❌ 对一个人是净负债。建议改用**单节点 k3s**（一个二进制、内置 traefik 与 local-path storage），API 与完整 K8s 一致，manifest 不用改 |

后文按「集群已存在」写。如果是自建，除第七节资源配额外其余全部适用，只是把 K8s 换成 k3s。

**关于 pgvector**：引入它意味着**多一个 PostgreSQL 实例**（pgvector 是 Postgres 扩展，不是独立服务）。代价是两套数据库要各自备份、各自升级；收益不止是检索——见 2.3，它顺带解决了应用多副本的一个硬阻塞。

---

## 一、组件全景

```
                          ┌─────────────────────────────────────────┐
   浏览器 ────HTTPS───────▶│  Ingress (nginx-ingress)                │
                          │  TLS 终止 / 路由 / SSE 缓冲必须关闭      │
                          └───────┬─────────────────────┬───────────┘
                                  │ /                   │ /api/*
                                  ▼                     ▼
                   ┌──────────────────────┐   ┌──────────────────────────┐
                   │ hify-web             │   │ hify-app                 │
                   │ Deployment ×2        │   │ Deployment ×2            │
                   │ nginx:alpine         │   │ Spring Boot(模块化单体)  │
                   │ 只发 Vue 静态资源    │   │ 全部业务逻辑 + SSE       │
                   └──────────────────────┘   └───┬────┬────┬────┬──────┘
                                                  │    │    │    │
                          ┌───────────────────────┘    │    │    └──── 出网 ──▶ OpenAI /
                          │              ┌─────────────┘    │              Claude / Gemini
                          ▼              ▼                  ▼                (LLM + Embedding)
                 ┌────────────────┐ ┌──────────┐ ┌────────────────────┐
                 │ MySQL 8        │ │ Redis 7  │ │ PostgreSQL+pgvector│
                 │ StatefulSet ×1 │ │ ×1       │ │ StatefulSet ×1     │
                 │ 业务数据       │ │ 缓存/上下文│ │ 向量与分段         │
                 │ PVC 20Gi       │ │ PVC 2Gi  │ │ PVC 20Gi           │
                 └────────────────┘ └──────────┘ └────────────────────┘

  横切：ConfigMap(应用配置) · Secret(库密码 + 加密主密钥) · CronJob(备份)
```

**工作负载清单（namespace `hify-prod`）：**

| # | 组件 | 类型 | 副本 | 职责 | 有状态 |
|---|---|---|:---:|---|:---:|
| 1 | `ingress` | Ingress 资源 | — | TLS 终止、按路径分流、**SSE 透传** | 否 |
| 2 | `hify-web` | Deployment | 2 | 发 Vue 构建产物（纯静态），history 模式 fallback | 否 |
| 3 | `hify-app` | Deployment | **2** | 全部后端业务：七个模块、SSE 对话、文档处理 | **否**（见 2.3） |
| 4 | `hify-mysql` | StatefulSet | 1 | 业务数据（provider/agent/chat/mcp/workflow + 知识库元数据） | 是 |
| 5 | `hify-postgres` | StatefulSet | 1 | **仅向量与分段文本**（pgvector） | 是 |
| 6 | `hify-redis` | StatefulSet | 1 | 配置缓存、会话上下文、MCP 工具清单兜底缓存 | 是（可重建） |
| 7 | `hify-backup` | CronJob | — | 每日 `mysqldump` + `pg_dump` | — |

> **一期不做**：HPA（50 人固定 2 副本足够，自动扩缩只会引入抖动）、ServiceMesh、多环境 namespace、Prometheus Operator（先 Actuator + 日志，沿用既有基准）。

---

## 二、逐组件职责

### 2.1 Ingress —— 唯一入口，SSE 成败在这里

**职责**：TLS 终止、按路径分流、限流兜底。**不做**鉴权（在应用层）、不做业务路由。

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hify
  namespace: hify-prod
  annotations:
    # ★ 关闭缓冲——不关就没有打字机效果,token 会被攒批下发
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    # ★ 超时必须 > SseEmitter 的 300s(见 04 文档 2.3)
    nginx.ingress.kubernetes.io/proxy-read-timeout: "360"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "360"
    # ★ 文档上传:默认 1m 会挡住稍大的 TXT
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
    # SSE 响应不要被压缩
    nginx.ingress.kubernetes.io/configuration-snippet: |
      gzip off;
      proxy_set_header Connection "";
spec:
  ingressClassName: nginx
  tls: [{ hosts: [hify.internal], secretName: hify-tls }]
  rules:
    - host: hify.internal
      http:
        paths:
          - { path: /api, pathType: Prefix, backend: { service: { name: hify-app, port: { number: 8080 } } } }
          - { path: /,    pathType: Prefix, backend: { service: { name: hify-web, port: { number: 80 } } } }
```

**四条注解缺一不可**，前两条尤其：`proxy-buffering` 不关，用户看到的是「转圈几十秒 → 整段文字一次蹦出来」；超时不改，对话到 60 秒（ingress-nginx 默认）就被掐断。

> **不需要粘性会话（session affinity）。** Hify 的 SSE 是「一次请求 → 流式返回这次的结果」，整条流的生命周期在同一个 HTTP 连接、同一个 Pod 内，天然不跨副本。会话上下文已外置到 MySQL/Redis。

### 2.2 hify-web —— 静态资源，与后端解耦

**职责**：只发 `npm run build` 的产物。不代理、不转发——`/api` 由 Ingress 直接分流到 `hify-app`，不经过 web pod。

```nginx
server {
  listen 80;
  root /usr/share/nginx/html;
  location / {
    try_files $uri $uri/ /index.html;   # Vue history 模式
  }
  location /assets/ {
    expires 1y;                          # 带 hash 的产物,长缓存
    add_header Cache-Control "public, immutable";
  }
  location = /index.html {
    add_header Cache-Control "no-cache";  # 入口文件不缓存,否则发版不生效
  }
}
```

**为什么单独一个 Deployment 而不是把静态资源打进 Spring Boot jar**：前后端构建节奏完全不同（`03-架构设计/02-决策/01` 9.5 已定 `hify-web` 不进 Maven reactor）。分开后改一行 CSS 不用重启后端、不用中断在途的 SSE 对话。

### 2.3 hify-app —— 全部业务逻辑

**职责**：七个 Maven 模块打成的**一个可执行 jar、一个进程**。模块化只在编译期，运行时不拆。

**副本数定 2**，理由是滚动更新时不中断服务——不是为了性能（50 人峰值并发 3–5，单副本绰绰有余）。

**多副本的三个前置条件，现在全部满足：**

| 条件 | 状态 |
|---|---|
| SSE 不跨实例 | ✅ 天然满足（2.1） |
| 会话上下文外置 | ✅ 已定「状态外置」 |
| **知识库向量不在进程内** | ✅ **改 pgvector 后成立** ← 见下 |

> **这是引入 pgvector 最被低估的收益。** 原方案的 `InMemoryVectorStore` 是**进程内状态**：2 个副本各自持有一份全量向量（内存翻倍），且任一副本重启都要从库重建。这会把 `hify-app` 变成事实上的有状态服务，多副本要么内存爆要么行为不一致。换到 pgvector 后 `knowledge` 模块彻底无状态，副本数才真正可以随便调。
>
> 代价也要说清楚：检索从「内存计算，微秒级」变成「一次跨 Pod 的 SQL 查询，毫秒级」。3–5 QPS 下这个差别是零。

**镜像与 JVM：**

```dockerfile
# 多阶段构建:构建产物不进运行镜像
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY hify-*/pom.xml ./                      # 先拷 pom 缓存依赖层
RUN mvn -B -pl hify-app -am dependency:go-offline
COPY . .
RUN mvn -B -pl hify-app -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/hify-app/target/*.jar app.jar
# ★ 用百分比而不是写死 -Xmx:改容器配额时 JVM 自动跟随,不会漏改
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
```

> **`-XX:MaxRAMPercentage=50` 优于写死 `-Xmx512m`。** 既有基准强调「只限容器不设 `-Xmx` 会被 OOM Kill」——这条依然对，但在 K8s 里写死数值的新风险是：改了 `resources.limits.memory` 忘了改 `-Xmx`。百分比让两者永远一致。容器 limit 1Gi → 堆 512Mi，与基准等价。

### 2.4 MySQL —— 业务数据

**职责**：`provider_*` / `agent_*` / `chat_*` / `mcp_*` / `wf_*` / `kb_*`（知识库的**元数据**：文档、分块记录，不含向量）。

StatefulSet 单实例 + PVC。**不做主从**——50 人规模下读写分离是纯负债，且备份 + 每日快照已覆盖数据安全需求。

`utf8mb4` + `Asia/Shanghai`（沿用基准）。schema 变更走 Flyway，应用启动时自动执行。

> **两副本同时启动会不会并发跑 Flyway？** 不会出问题——Flyway 用 `flyway_schema_history` 表的行锁互斥，后启动的副本会等待。但如果迁移脚本很慢（大表加索引），第二个副本可能启动超时，这也是 `startupProbe` 必须配足的原因之一（见第三节）。

### 2.5 PostgreSQL + pgvector —— 只放向量

**职责边界要划死：只存分段文本 + 向量，不放任何业务数据。**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_segment_vector (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT       NOT NULL,   -- 逻辑外键,指向 MySQL 的 kb_knowledge_base.id
    document_id  BIGINT       NOT NULL,   -- 同上,不建外键(跨库不可能建)
    segment_idx  INT          NOT NULL,
    content      TEXT         NOT NULL,
    embedding    vector(1536) NOT NULL,   -- text-embedding-3-small 的维度
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ON kb_segment_vector (kb_id, document_id);
```

**索引策略：一期先不建向量索引。**

pgvector 的 HNSW / IVFFlat 都有建立成本，而在几千条量级下 PostgreSQL 顺序扫描 + 距离计算就是毫秒级——建索引反而可能因为召回率损失得不偿失。**超过约 1 万条分段再建 HNSW**：

```sql
-- 触发阈值:分段数 > 10000
CREATE INDEX ON kb_segment_vector USING hnsw (embedding vector_cosine_ops);
```

**跨库一致性怎么办**：MySQL 里删了一个文档，Postgres 里的向量成了孤儿。**不做分布式事务**——按既有规矩（跨模块不共享事务），处理方式是：

1. 删除走**软删除**（`kb_document.status = 0`），检索时按 `document_id` 过滤；
2. 一个每日 CronJob 清理已软删文档对应的向量行。

这与「删除一律做停用、不做物理删除」的既定规则一致，不是为跨库额外发明的机制。

### 2.6 Redis —— 缓存与上下文

**职责**：配置信息 Cache-Aside、会话上下文、MCP 工具清单兜底缓存。

**数据可丢**（全部能从 MySQL 重建），但开 AOF `everysec`——不为持久化，为的是重启后不出现「所有缓存同时失效 → 瞬间打穿 MySQL」的冷启动抖动。

`maxmemory 256mb` + `maxmemory-policy allkeys-lru`。

### 2.7 Secret 与 ConfigMap —— 一个必须讲清的区分

| 放哪 | 内容 |
|---|---|
| **ConfigMap** | `application-prod.yml` 的非敏感部分：线程池参数、超时值、日志级别、各服务地址 |
| **Secret** | MySQL / Postgres / Redis 密码；**API Key 加密主密钥** |
| **数据库（MySQL，密文）** | **各 LLM 提供商的 API Key** |

**第三行容易搞混**：OpenAI / Claude 的 API Key **不进 Secret**。它们由用户在管理控制台配置、加密后存 `provider_config` 表（既有规矩：「API Key 不明文进库、不进日志」）。进 Secret 的只有那把**加密主密钥**——它是解开库里所有密文的钥匙。

这个区分的实际意义：换一个模型提供商的 Key 是**页面上点一下**，不是改 Secret + 重启 Pod。

---

## 三、探针配置：K8s + Spring Boot 最容易翻车的地方

```yaml
# ★ 启动探针:Spring Boot 冷启动 30-60 秒(还要跑 Flyway)
#   不配它,liveness 会在应用起来之前就把 Pod 反复杀掉 —— 表现为 CrashLoopBackOff,
#   而日志里看不到任何异常。这是 K8s 上部署 Spring Boot 最常见的事故。
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 30
  periodSeconds: 5          # 最多容忍 150 秒启动

# ★ 存活探针:只看进程是否还活着,绝不能包含任何外部依赖
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3

# 就绪探针:可以包含依赖,不健康时摘出负载均衡但不重启
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3
```

**两条红线：**

1. **`livenessProbe` 绝不能用 `/actuator/health`。** 默认的 `/actuator/health` 聚合了 `db` / `redis` 等所有 indicator。MySQL 抖动 3 秒 → **所有副本同时 liveness 失败 → 全部被重启 → 雪崩**。数据库恢复了，你的应用还在 CrashLoopBackOff。必须用 `/health/liveness`（只反映进程自身）。
2. **必须配 `startupProbe`。** 否则 `livenessProbe` 从容器启动那一刻就开始计时。

配套开启 Spring Boot 的探针端点：

```yaml
management:
  endpoint.health.probes.enabled: true
  health.livenessState.enabled: true
  health.readinessState.enabled: true
  endpoints.web.exposure.include: health,info,metrics
```

### 3.1 优雅停机与 SSE 的冲突（没有完美解）

一次对话可能 5 分钟，而 `terminationGracePeriodSeconds` 不可能设 5 分钟（滚动更新会慢到不可用）。

```yaml
terminationGracePeriodSeconds: 60
lifecycle:
  preStop:
    exec:
      command: ["sh","-c","sleep 5"]   # 等 Endpoints 摘除传播完,避免新请求打进正在关闭的 Pod
```

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 45s
```

**60 秒兜不住 5 分钟的长对话，这是接受的取舍。** 剩下的靠前端：连接异常断开且未收到 `done` 事件时提示「服务已更新，请重新发送」（`05` 文档 3.7 已定）。

**降低影响的实际做法**：滚动更新配 `maxSurge: 1, maxUnavailable: 0`（先起新的再停旧的），并把发版安排在低峰。

---

## 四、请求流转：三条链路

### 链路 1：管理面 CRUD（短请求，毫秒级）

```
浏览器 ──▶ Ingress ──▶ Service(hify-app) ──▶ Pod ──▶ MySQL
   │                                          └──▶ Redis(Cache-Aside)
   ◀───────────────── R<T> JSON ──────────────┘
```

无特殊处理。占用 Tomcat 工作线程（200 个，用不到）。

### 链路 2：SSE 对话（长连接，分钟级）—— 关键链路

```
① 浏览器  fetch POST /api/chat/completions   (不能用 EventSource,见 05 文档 3.6)
      │
② Ingress  proxy-buffering off / read-timeout 360s
      │
③ Service → Pod  Tomcat 线程进入 Controller,返回 SseEmitter 后立即释放
      │
④ llm-chat 线程池(core32/max64/queue16)取一个线程,开始编排:
      │
      ├─ agent.getConfig()          ──▶ MySQL / Redis
      ├─ retrieval.retrieve()       ──▶ ⑤ embedding ──出网──▶ 提供商 API
      │                             ──▶ ⑥ pgvector 相似度查询
      ├─ chatModel.stream()         ──出网──▶ ⑦ LLM 提供商(流式)
      │        每个 token: emitter.send(event:message) ──▶ ②─▶① 浏览器
      ├─ 工具调用循环               ──▶ ⑧ MCP Server(集群内或外网)
      └─ 落库助手消息               ──▶ MySQL(短事务)
      │
⑨ emitter.complete()  →  连接关闭
   每 15 秒穿插一个注释帧心跳,兼作客户端断连探测
```

**逐跳的超时预算**（任一跳配错，整条链路就断在那里）：

| 跳 | 超时 | 配在哪 |
|---|---|---|
| 浏览器 → Ingress | 无（fetch 不设） | 前端 `AbortController` 由用户「停止」触发 |
| Ingress → Pod | **360s** | Ingress 注解 |
| SseEmitter 生命周期 | **300s** + 15s 心跳 | 应用 |
| Pod → LLM 提供商 | 连接 3s / 首字节 30s / **流间隔 30s** / 整体 300s | 应用（`04` 文档第二节） |
| Pod → pgvector | 5s | 数据源 |

**必须满足 `Ingress(360) > SseEmitter(300) > 上游整体(300)`**——顺序反了就会出现「后端还在正常生成，代理先把连接掐了」，日志里干干净净什么都查不到。

### 链路 3：文档上传与向量化（长任务）

```
① 浏览器 POST /api/knowledge-bases/{id}/documents  (multipart)
② Ingress   proxy-body-size 50m
③ Pod       接收文件 → 落 MySQL(元数据) → 立即返回「处理中」
④ kb-index 线程池(core2/max4/queue64)异步处理:
      解析 → 固定长度分块 → 批量 embedding(出网) → 写 pgvector
⑤ 前端轮询文档状态,或在列表页看进度
```

**三点必须注意：**

1. **上传接口不能同步等向量化完成**——一个 1MB 的 TXT 分成几百段，逐段调 embedding 要几十秒，同步会 504。
2. **`kb-index` 与 `llm-chat` 必须是两个线程池**（`04` 文档 1.3）——批量导入不能吃光对话线程。
3. **上传的原始文件怎么存**，见下节——这是本次设计里唯一一个必须现在拍板的新问题。

---

## 五、⚠️ 文件存储与多副本冲突（本设计的唯一硬问题）

既有设计里 `FileStorage` 的实现是 `LocalFileStorage`（写容器本地磁盘）。**这在 2 副本下直接坏掉**：

```
用户上传 → 打到 Pod A → 文件落在 Pod A 的磁盘
用户点「重新解析」→ 打到 Pod B → Pod B 上没有这个文件 → 报错
```

四个选项：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. **PVC `ReadWriteMany`** | 两个 Pod 挂同一个卷 | 需要 NFS / CephFS 后端支持。**很多集群只有 `ReadWriteOnce`**，落地前必须先确认 StorageClass |
| B. **单副本** | 副本数改回 1 | 放弃滚动更新无损，发版必然中断 |
| C. **MinIO / 对象存储** | 加一个组件或用公司现成的 S3 | 最标准，但多一个要运维的东西 |
| D. **文件内容进数据库** ⭐ | TXT 内容直接存 MySQL `LONGTEXT`（或 Postgres `TEXT`） | 一期只支持 TXT，单文档通常 < 1MB；省掉整个存储层 |

**推荐 D**，理由是它跟一期的功能边界完全吻合：只支持 TXT、量小、且**解析完之后原文其实只用于「重新分块」这一个场景**。等到支持 PDF / Word（体积大、二进制）时再上 C，那时 `FileStorage` 接口一换实现就行。

> **这恰好验证了既有规矩「文件存储走抽象接口」的价值**——今天从 `LocalFileStorage` 换成 `DbFileStorage`，改动是 `knowledge/storage` 包里的一个实现类，调用方一行不动。

**如果选 A**，先跑这条确认集群支持：

```bash
kubectl get storageclass -o custom-columns=NAME:.metadata.name,PROVISIONER:.provisioner
```

---

## 六、目录与部署清单

```
deploy/
├── docker/
│   ├── Dockerfile.app          # 后端多阶段构建
│   └── Dockerfile.web          # 前端 npm build → nginx
├── compose/                    # ★ 保留:本地开发一键起全套依赖
│   ├── docker-compose.yml      #   mysql + postgres + redis,不含 app
│   └── .env.example
└── k8s/
    ├── base/
    │   ├── namespace.yaml
    │   ├── configmap.yaml          # application-prod.yml
    │   ├── secret.example.yaml     # ★ 只提交示例,真值不进仓库
    │   ├── mysql/                  # StatefulSet + Service + PVC
    │   ├── postgres/               # StatefulSet + Service + PVC + init.sql(CREATE EXTENSION)
    │   ├── redis/
    │   ├── app/                    # Deployment + Service
    │   ├── web/                    # Deployment + Service
    │   ├── ingress.yaml
    │   └── backup-cronjob.yaml
    └── kustomization.yaml
```

**用 Kustomize，不用 Helm。** 一个人、一套环境，Helm 的模板化与 values 抽象收益为零，反而多一层调试难度（`helm template` 之后才能看到真实 manifest）。Kustomize 是 `kubectl` 内置的，`kubectl apply -k deploy/k8s/base` 就行。

**本地开发保留 Compose**：只起 mysql + postgres + redis 三个依赖，应用在 IDE 里跑。**不要**把本地开发也换成 K8s——那会让「改一行代码看效果」从 3 秒变成 3 分钟。

**镜像 tag 策略**（结清 `CLAUDE.md` 待定项 4 的一部分）：用 `hify-app:<git-short-sha>`，**禁止用 `latest`**——`latest` 配合 `imagePullPolicy: Always` 会让「回滚」变成不可能（不知道回到哪个镜像）。回滚用 `kubectl rollout undo deployment/hify-app`。

---

## 七、资源配额

| 组件 | requests (cpu/mem) | limits (cpu/mem) | 说明 |
|---|---|---|---|
| `hify-app` | 500m / 1Gi | **不设 CPU limit** / 1Gi | 见下 |
| `hify-web` | 10m / 32Mi | 100m / 64Mi | 纯静态，几乎不吃资源 |
| `hify-mysql` | 500m / 1Gi | 2 / 2Gi | |
| `hify-postgres` | 250m / 512Mi | 1 / 1Gi | 向量检索是 CPU 密集，量大后调高 |
| `hify-redis` | 100m / 128Mi | 500m / 512Mi | `maxmemory 256mb` |

**两点非直觉的：**

1. **`hify-app` 不设 CPU limit（或设得很高）。** Spring Boot 启动期 CPU 需求是稳态的好几倍（类加载、JIT、Flyway）。CPU limit 设成 1 会让启动被 CFS 严重 throttle，启动时间从 40 秒拉到 3 分钟，然后被 `startupProbe` 判死。**内存必须设 limit**（防止单 Pod 拖垮节点），CPU 靠 requests 保底就够。
2. **memory limit 1Gi 配 `MaxRAMPercentage=50`** → 堆 512Mi，堆外留 512Mi 给 Metaspace（~100Mi）、线程栈（64 线程 × 1Mi = 64Mi，见 `04` 文档 1.2）和 JVM 自身。与既有基准的核算一致。

---

## 八、这套架构相比 Docker Compose 多了什么

诚实列一遍，供判断值不值：

| 维度 | Compose | K8s |
|---|---|---|
| 组件数 | 4 个容器，1 个 yml | 7 个工作负载 + Ingress + 若干 PVC / ConfigMap / Secret |
| 需要掌握 | `docker compose up` | Deployment / StatefulSet / Service / Ingress / PVC / 三种探针 / 资源配额 / Kustomize |
| **新增故障模式** | — | **探针误杀、PVC 绑定失败、镜像拉取失败、Service DNS、CPU throttle 导致启动超时** |
| 白拿的能力 | — | 滚动更新、探针自愈、配置与密钥管理、副本编排、`rollout undo` 秒级回滚 |
| 发版中断 | 有（重启即中断） | **无**（`maxUnavailable: 0`，除去 SSE 那 60 秒） |

**「新增故障模式」那一行是真实成本**——本文档第三节整节都在处理它。但如果集群是公司现成的，这些是一次性学习成本，之后每天都在白拿滚动更新和自愈。

---

## 九、需要拍板的

| # | 事项 | 倾向 |
|---|---|---|
| 1 | **文件存储方案**（第五节，多副本硬阻塞） | **D：TXT 内容存数据库**。选 A 前必须先确认集群有 `ReadWriteMany` 的 StorageClass |
| 2 | **副本数 2** | 采纳。pgvector 消除了进程内状态，前置条件已满足 |
| 3 | **MySQL + Postgres 双库并存** | 采纳。另一条路是全量迁 Postgres 省掉一个容器，但那会推翻 MyBatis-Plus + MySQL 的技术选型，不划算 |
| 4 | **pgvector 一期不建向量索引**，> 1 万条分段再建 HNSW | 采纳 |
| 5 | **跨库一致性靠软删除 + 每日清理 CronJob**，不做分布式事务 | 采纳，与既有「删除做停用」规则一致 |
| 6 | **Kustomize 而非 Helm；镜像 tag 用 git sha，禁用 latest** | 采纳 |
| 7 | **`livenessProbe` 用 `/health/liveness`；必须配 `startupProbe`；`hify-app` 不设 CPU limit** | 采纳，三条都是事故防范 |
| 8 | **集群是现成的还是要自建** | 若自建，改用单节点 k3s，本文档其余不变 |

### 需同步修订的基准文档

| 文档 | 改什么 |
|---|---|
| `02-产品决策/03_Hify部署与运维（基准）.md` | 第 1 节「明确不做 K8s」→ 改为「生产 K8s，本地开发 Compose」；补入探针、资源配额、镜像 tag 约定 |
| `02-产品决策/01_Hify产品决策（基准）.md` | 待定项 1（向量存储）→ 结清为 **pgvector** |
| `03-架构设计/02-决策/01_Hify代码组织（基准）.md` | 第十节第 1 项「向量检索换实现」→ 标记为**已提前完成**；`knowledge/vector` 的实现从 `InMemoryVectorStore` 改为 `PgVectorStore` |
| `03-架构设计/02-决策/02_Hify模块内部分层（基准）.md` | 4.3 表：`knowledge/storage` 的实现由 `LocalFileStorage` 改为待定项 1 的结论 |
| `hify/CLAUDE.md` | 技术栈补 PostgreSQL + pgvector；部署预期从「Compose 单机」改为「生产 K8s」 |
