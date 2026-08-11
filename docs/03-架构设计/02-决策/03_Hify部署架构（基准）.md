# Hify 部署架构（基准）

> 📌 **本文档是 Hify 生产部署形态的唯一依据（source of truth）**，如与其它任何文档冲突，**以本文档为准**。
> 决策以下方架构图为准。图片来源：https://static001.geekbang.org/resource/image/4d/eb/4d317a56e50a08ce0c361a5ffa4350eb.jpg
> 本地副本：`../assets/hify-部署架构基准.jpg`
> 上游依据：`../../02-产品决策/03_Hify部署与运维（基准）.md`（容量目标与运维策略）、`01_Hify代码组织（基准）.md`（模块划分）
> 推演过程见 `../01-过程/06_部署架构（Docker与K8s）.md`（过程稿，本文档与其分歧处以本文档为准）
> 决策日期：2026-08-11

---

## 一、架构图

![Hify 部署架构基准](../assets/hify-部署架构基准.jpg)

```
                         浏览器
                            │ HTTPS
┌───────────────────────────▼─────────────────────────────────────┐
│ K8s cluster                                                      │
│                                                                  │
│                  ┌──────────────────────────────┐                │
│                  │ Nginx                        │                │
│                  │ 静态文件 + 反代 + SSE 透传    │                │
│                  └──────────────┬───────────────┘                │
│                                 │ /api                           │
│                  ┌──────────────▼───────────────┐    ┌─────────┐ │
│                  │ Spring Boot                  │────│ LLM API │ │
│                  │ 业务逻辑 + LLM 调用 + RAG 编排 │    │ 外部服务 │ │
│                  └───┬──────────┬──────────┬────┘    └─────────┘ │
│         ┌────────────┘          │          └────────────┐        │
│  ┌──────▼──────┐        ┌───────▼──────┐        ┌───────▼──────┐ │
│  │ MySQL       │        │ Redis        │        │ pgvector     │ │
│  │ 结构化业务数据│        │ 缓存 + 会话   │        │ 向量存储与检索│ │
│  └─────────────┘        └──────────────┘        └──────────────┘ │
│                                                                  │
│           50 人规模，单副本，K8s 只作为部署壳                      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、决策总表

| 维度 | 基准决策 |
|---|---|
| **部署平台** | **K8s**，但**只作为部署壳**——见第六节 |
| **组件数量** | **5 个工作负载**：Nginx / Spring Boot / MySQL / Redis / pgvector |
| **副本数** | **全部单副本**，包括应用 |
| **入口** | **Nginx 一个组件承担三件事**：发静态资源、反向代理 `/api`、SSE 透传 |
| **应用形态** | 模块化单体，**一个进程、一个 jar**，运行时不拆 |
| **业务数据** | MySQL 8 |
| **缓存与会话上下文** | Redis 7 |
| **向量存储与检索** | **PostgreSQL + pgvector**（替代原「向量存 MySQL JSON + 内存暴力计算」方案） |
| **LLM** | 集群外部服务，应用**出网**直连 |
| **本地开发** | Docker Compose 起依赖（MySQL / Redis / pgvector），应用在 IDE 里跑 |

**明确不做**：多副本、HPA、Service Mesh、Operator、多环境 namespace、独立的前端 Deployment、消息队列、对象存储。

---

## 三、组件职责

### 3.1 Nginx —— 唯一入口，一个组件三个职责

| 职责 | 说明 |
|---|---|
| **发静态文件** | Vue 构建产物，history 模式 fallback 到 `index.html` |
| **反向代理 `/api`** | 转发到 Spring Boot 的 Service |
| **SSE 透传** | **关闭缓冲与 gzip**，否则流式被攒批下发，失去打字机效果 |

**合并成一个组件的理由**：50 人规模下拆成「Ingress + 独立前端 Deployment」只是多一个工作负载和一条路由规则，没有换来任何东西。前后端仍然是**两个镜像**——前端发版重建 Nginx 镜像，后端发版重建应用镜像，互不影响。

```nginx
server {
  listen 80;

  # 静态资源
  location / {
    root /usr/share/nginx/html;
    try_files $uri $uri/ /index.html;
  }
  location = /index.html {
    add_header Cache-Control "no-cache";   # 入口文件不缓存,否则发版不生效
  }

  # 反代 + SSE 透传
  location /api/ {
    proxy_pass http://hify-app:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";

    proxy_buffering off;        # ★ 不关就没有打字机效果
    proxy_cache off;
    gzip off;                   # ★ gzip 会缓冲到攒够一个压缩块才下发

    proxy_read_timeout 360s;    # ★ 必须 > SseEmitter 的 300s
    proxy_send_timeout 360s;
    client_max_body_size 50m;   # 文档上传
  }
}
```

> ⚠️ **如果集群前面还有一层 ingress-nginx**，上面四条 SSE 相关配置**必须在两层都配**。只在应用 Nginx 上关缓冲，外层 ingress 照样会把流攒批。集群入口的接入方式（Ingress / LoadBalancer / NodePort）按平台既有方式办，不在本决策范围。

### 3.2 Spring Boot —— 全部业务逻辑

**职责**：`hify-app` 打出的单个可执行 jar，聚合七个 Maven 模块（provider / agent / chat / mcp / workflow / knowledge / common）。图上标注的三件事对应到模块：

| 图上标注 | 对应模块 |
|---|---|
| 业务逻辑 | `agent` / `mcp` / `workflow` 及各模块管理面 |
| LLM 调用 | `provider`（含出网调用、超时、重试、熔断） |
| RAG 编排 | `chat`（编排）+ `knowledge`（分块、向量化、检索） |

**模块化只在编译期**，运行时是一个进程。这与 `01_Hify代码组织（基准）.md` 1.1 的定义一致。

### 3.3 MySQL —— 结构化业务数据

`provider_*` / `agent_*` / `chat_*` / `mcp_*` / `wf_*` / `kb_*`（知识库**元数据**：知识库、文档、分块记录）。

单实例 + PVC，**不做主从**。`utf8mb4`，时区 `Asia/Shanghai`。schema 变更走 Flyway，应用启动时自动执行。

### 3.4 Redis —— 缓存与会话

配置信息 Cache-Aside、会话上下文、MCP 工具清单兜底缓存。

**数据可丢**（全部能从 MySQL 重建），但开 AOF `everysec`——不为持久化，为的是避免重启后「所有缓存同时失效瞬间打穿 MySQL」的冷启动抖动。`maxmemory 256mb` + `allkeys-lru`。

### 3.5 pgvector —— 向量存储与检索

PostgreSQL + `vector` 扩展。**职责边界划死：只存分段文本与向量，不放任何业务数据。**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_segment_vector (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT       NOT NULL,   -- 逻辑引用 MySQL 的 kb_knowledge_base.id
    document_id  BIGINT       NOT NULL,   -- 跨库,不可能建外键
    segment_idx  INT          NOT NULL,
    content      TEXT         NOT NULL,
    embedding    vector(1536) NOT NULL,
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ON kb_segment_vector (kb_id, document_id);
```

**一期不建向量索引。** 几千条分段下 PostgreSQL 顺序扫描 + 距离计算就是毫秒级，建 HNSW 反而付出构建成本还损失召回率。**分段数超过约 1 万条再建**：

```sql
CREATE INDEX ON kb_segment_vector USING hnsw (embedding vector_cosine_ops);
```

**跨库一致性不做分布式事务**：MySQL 删文档走软删除（`status = 0`），检索时过滤；一个每日 CronJob 清理已软删文档的向量行。这与既有的「删除一律做停用」规则一致，不是为跨库额外发明的机制。

### 3.6 LLM API —— 外部服务

OpenAI / Claude / Gemini 在集群外，应用**出网**直连。Ollama 若使用，视部署位置为集群内 Service 或外部地址。

**两条部署要求**：

1. 若集群有 egress 限制，需为提供商域名开白名单；
2. **提供商的 API Key 不进 K8s Secret**——它们由用户在管理控制台配置、加密后存 MySQL（既有规矩：API Key 不明文进库、不进日志）。进 Secret 的只有**加密主密钥**。这个区分的实际意义：换一个提供商的 Key 是页面上点一下，不是改 Secret 重启 Pod。

---

## 四、请求流转

### 链路 1：管理面 CRUD

```
浏览器 → Nginx(/api) → Spring Boot → MySQL / Redis → R<T> JSON
```

毫秒级，占用 Tomcat 工作线程。无特殊处理。

### 链路 2：SSE 对话（关键链路）

```
① 浏览器  fetch POST /api/chat/completions
             （不能用 EventSource:不支持 POST、不能带 Authorization、断线会自动重连导致重复生成）
② Nginx    proxy_buffering off / read_timeout 360s
③ Spring Boot  Tomcat 线程进 Controller,返回 SseEmitter 后立即释放
④ llm-chat 线程池取一个线程开始编排:
      ├─ 取 Agent 配置        → MySQL / Redis
      ├─ RAG 检索             → 向量化(出网 LLM API) → pgvector 相似度查询
      ├─ 调模型(流式)         → 出网 LLM API
      │     每个 token: emitter.send(event:message) → ② → ① 浏览器
      ├─ 工具调用循环         → MCP Server
      └─ 落库助手消息         → MySQL(短事务)
⑤ emitter.complete()
   全程每 15 秒一个注释帧心跳,兼作客户端断连探测
```

**逐跳超时预算必须满足这个不等式：**

```
Nginx(360s)  >  SseEmitter(300s)  ≥  上游整体超时(300s)
```

顺序反了会出现「后端还在正常生成，代理先把连接掐了」，日志里干干净净什么都查不到。

| 跳 | 超时 | 配在哪 |
|---|---|---|
| Nginx → 应用 | 360s | `proxy_read_timeout` |
| SseEmitter 生命周期 | 300s + 15s 心跳 | 应用 |
| 应用 → LLM 提供商 | 连接 3s / 首字节 30s / **流间隔 30s** / 整体 300s | 应用 |
| 应用 → pgvector | 5s | 数据源 |

> **流间隔超时**是标准 HTTP 客户端不提供的：read timeout 只覆盖第一个字节，流开始后上游卡死不会触发。必须在读流循环里自己计时。

### 链路 3：文档上传与向量化

```
① 浏览器 POST 文档(multipart)
② Nginx  client_max_body_size 50m
③ 应用   落 MySQL 元数据 → 立即返回「处理中」
④ kb-index 线程池异步: 解析 → 分块 → 批量向量化(出网) → 写 pgvector
⑤ 前端轮询文档状态
```

**上传接口不能同步等向量化完成**——一个 1MB 的 TXT 分成几百段，逐段调 embedding 要几十秒，同步必然 504。

**`kb-index` 与 `llm-chat` 必须是两个独立线程池**，否则一次批量导入会吃光对话线程，所有人卡死。

---

## 五、单副本的三个后果（必须接受）

这是本架构最重要的一处取舍，逐条说清楚代价。

### 5.1 ✅ 好处：应用可以有本地状态

多副本下的两个硬阻塞在单副本下自然消失：

| 多副本会坏掉的东西 | 单副本 |
|---|---|
| `LocalFileStorage` 写容器本地磁盘 → 用户在 A 上传、B 读不到 | ✅ 不存在 |
| 进程内缓存（若有）在副本间不一致 | ✅ 不存在 |

> 顺带说明：本架构用 pgvector 之后，知识库向量本来就不在进程内了。所以**即使将来要加副本，剩下的唯一障碍就是文件存储**——见 5.3。

### 5.2 ⚠️ 代价：发版与故障期间服务不可用

| 场景 | 影响 | 缓解 |
|---|---|---|
| **发版** | 应用重启期间（约 40–60 秒）全部请求失败 | 安排在低峰；前端对连接失败给明确提示而不是无限转圈 |
| **在途 SSE 对话** | 一律中断 | 前端收到连接异常断开且未收到 `done` 事件时，提示「服务已更新，请重新发送」 |
| **Pod 崩溃 / 节点故障** | 不可用直到重新调度完成 | K8s 自动重启与重新调度仍然生效——这正是「部署壳」提供的价值 |

**对 50 人内部工具，这些代价可以接受。** 但要写进发版流程，不能等用户来问。

### 5.3 ⚠️ 陷阱：单副本 + PVC 会让滚动更新卡死

这条最容易踩。如果应用挂了 `ReadWriteOnce` 的 PVC（存上传文档），而部署策略是默认的 `RollingUpdate`：

```
K8s 先起新 Pod(maxSurge=1) → 新 Pod 要挂同一个 RWO PVC
   → 旧 Pod 还没释放 → 新 Pod 卡在 ContainerCreating
   → 旧 Pod 等新 Pod Ready 才退出 → 死锁,更新永远不完成
```

**两条出路，二选一：**

| | 做法 | 结果 |
|---|---|---|
| **A（推荐）** | **TXT 内容直接存数据库**，应用不挂任何 PVC | 应用完全无状态，可用 `RollingUpdate`，将来加副本零障碍 |
| B | 保留本地磁盘 + PVC，部署策略改成 `strategy: { type: Recreate }` | 先停旧再起新，中断窗口变长，且将来加副本要重做 |

**推荐 A**，理由是它与一期功能边界完全吻合：只支持 TXT、单文档通常 < 1MB、原文只用于「重新分块」这一个场景。等支持 PDF / Word（大体积二进制）时再上对象存储——那时换的是 `FileStorage` 接口的一个实现类，调用方一行不动。

> 这验证了既有规矩「文件存储走抽象接口」的价值。

**日志同理**：K8s 环境下应用日志走 **stdout**，由集群日志收集，**不写文件、不挂 volume**。这是相对 `03_Hify部署与运维（基准）.md`「日志目录挂 volume」的调整——那条针对 Compose 单机，K8s 下不适用。

---

## 六、「K8s 只作为部署壳」的准确含义

这不是「用了 K8s」，而是**只用它最基础的那一层**，明确放弃其余能力。

### 6.1 用什么

| 能力 | 用途 |
|---|---|
| Deployment / StatefulSet | 保证进程活着，挂了自动拉起 |
| Service | 组件间用稳定名字互相找（`hify-app` / `hify-mysql`） |
| PVC | 数据持久化（MySQL / Redis / pgvector 各一个） |
| ConfigMap / Secret | 配置与密钥管理，改配置不用重建镜像 |
| 探针 | 启动检测与自愈 |
| `kubectl rollout undo` | 秒级回滚 |

### 6.2 不用什么

| 能力 | 为什么不用 |
|---|---|
| **多副本 / HPA** | 50 人峰值并发 3–5，单副本绰绰有余；自动扩缩只会引入抖动 |
| **Service Mesh** | 5 个组件、全内网调用，没有治理需求 |
| **Operator / Helm** | 一个人、一套环境，模板化抽象收益为零。用 **Kustomize**（`kubectl` 内置），`kubectl apply -k` 即可 |
| **多环境 namespace** | 一个 `hify-prod` namespace；本地开发用 Compose，不用 K8s |
| **网络策略 / Pod 安全策略** | 内部平台，按集群既有默认 |

### 6.3 本地开发不要用 K8s

`deploy/compose/` 保留一份只起 MySQL + Redis + pgvector 的 compose 文件，应用在 IDE 里直接跑。**不要把本地开发也搬到 K8s**——那会让「改一行代码看效果」从 3 秒变成 3 分钟，是这个决策里最容易犯的错。

---

## 七、必须做对的配置

以下四组不是可选优化，配错会直接导致事故。

### 7.1 探针：两条红线

```yaml
# ★ 必须配 startupProbe。Spring Boot 冷启动 30-60 秒(还要跑 Flyway),
#   不配它 livenessProbe 会在应用起来之前反复杀掉容器 ——
#   表现为 CrashLoopBackOff,而日志里看不到任何异常。
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 30
  periodSeconds: 5              # 最多容忍 150 秒启动

# ★ livenessProbe 绝不能用 /actuator/health ——
#   它聚合了 db / redis 等所有 indicator,MySQL 抖动 3 秒就会让 Pod 被重启,
#   数据库恢复了应用还在 CrashLoopBackOff。只能用 /health/liveness。
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3
```

```yaml
management:
  endpoint.health.probes.enabled: true
  health.livenessState.enabled: true
  health.readinessState.enabled: true
  endpoints.web.exposure.include: health,info,metrics
```

### 7.2 优雅停机

```yaml
terminationGracePeriodSeconds: 60
lifecycle:
  preStop:
    exec: { command: ["sh","-c","sleep 5"] }   # 等 Endpoints 摘除传播完
```

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 45s
```

60 秒兜不住 5 分钟的长对话，这是接受的取舍（见 5.2）。

### 7.3 JVM 与资源

```dockerfile
# ★ 用百分比而非写死 -Xmx:改容器配额时 JVM 自动跟随,不会漏改
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
```

| 组件 | requests (cpu/mem) | limits (cpu/mem) |
|---|---|---|
| `hify-app` | 500m / 1Gi | **不设 CPU limit** / 1Gi |
| `hify-nginx` | 10m / 32Mi | 100m / 64Mi |
| `hify-mysql` | 500m / 1Gi | 2 / 2Gi |
| `hify-postgres` | 250m / 512Mi | 1 / 1Gi |
| `hify-redis` | 100m / 128Mi | 500m / 512Mi |

**`hify-app` 不设 CPU limit（内存必须设）**：Spring Boot 启动期 CPU 需求是稳态的数倍（类加载、JIT、Flyway），limit 设低会被 CFS 严重 throttle，启动时间从 40 秒拉到 3 分钟，然后被 `startupProbe` 判死。

memory limit 1Gi 配 `MaxRAMPercentage=50` → 堆 512Mi，堆外留 512Mi 给 Metaspace、线程栈与 JVM 自身，与既有基准的核算一致。

### 7.4 镜像与回滚

- **镜像 tag 用 `hify-app:<git-short-sha>`，禁止 `latest`**——`latest` 会让回滚变成不可能（不知道回到哪个镜像）。
- 回滚用 `kubectl rollout undo deployment/hify-app`。
- 这一条结清 `CLAUDE.md` 待定项 4「镜像 tag 策略」。

---

## 八、部署清单结构

```
deploy/
├── docker/
│   ├── Dockerfile.app          # 后端多阶段构建
│   └── Dockerfile.nginx        # 前端产物 + nginx.conf
├── compose/                    # 本地开发:只起 mysql + redis + pgvector
│   ├── docker-compose.yml
│   └── .env.example
└── k8s/
    ├── namespace.yaml
    ├── configmap.yaml          # application-prod.yml
    ├── secret.example.yaml     # ★ 只提交示例,真值不进仓库
    ├── mysql/                  # StatefulSet + Service + PVC
    ├── postgres/               # StatefulSet + Service + PVC + init.sql
    ├── redis/                  # StatefulSet + Service + PVC
    ├── app/                    # Deployment + Service
    ├── nginx/                  # Deployment + Service + ConfigMap(nginx.conf)
    ├── backup-cronjob.yaml     # 每日 mysqldump + pg_dump
    └── kustomization.yaml
```

---

## 九、一句话结论

> **五个组件、全部单副本：Nginx（静态 + 反代 + SSE 透传）→ Spring Boot（模块化单体，一个进程）→ MySQL（业务数据）/ Redis（缓存与会话）/ pgvector（向量），LLM 走出网。**
> **K8s 只作为部署壳——用 Deployment 保活、PVC 持久化、ConfigMap/Secret 管配置、`rollout undo` 回滚；不用多副本、HPA、Mesh、Helm。本地开发仍用 Compose。**
> **单副本换来「应用可以有本地状态」，代价是发版与故障期间服务不可用；但仍推荐把上传文档存进数据库，让应用彻底无状态——否则 RWO PVC 会让滚动更新死锁，且将来加副本要重做。**

---

## 十、待定项

| # | 待定项 | 说明 | 倾向 |
|---|---|---|---|
| 1 | **上传文档的存放位置** | 数据库 vs 本地磁盘 + PVC。直接决定部署策略能否用 `RollingUpdate`（见 5.3） | **存数据库**（应用无状态） |
| 2 | **集群入口方式** | Ingress / LoadBalancer / NodePort，按平台既有方式定。若前面还有 ingress-nginx，SSE 配置要在两层都做 | 按平台 |
| 3 | **集群是现成的还是自建** | 若为本项目自建，改用单节点 **k3s**（API 一致，manifest 不用改），完整 K8s 对一个人是净负债 | 优先用现成集群 |
| 4 | **Ollama 的部署位置** | 集群内（需 GPU node）/ 外部机器 / 不用 | 待定 |
| 5 | **备份的落点** | CronJob 产物存 PVC 还是推到对象存储 | 一期存 PVC，配合宿主机快照 |

---

## 十一、与既有文档的关系

### 11.1 本文档取代的部分

| 文档 | 状态 |
|---|---|
| `../../02-产品决策/03_Hify部署与运维（基准）.md` **第 1 节「部署方式」**（Docker Compose 一键启动、明确不做 K8s） | ❌ **被本文档取代**。生产用 K8s，Compose 降级为本地开发工具 |
| 同文档 **「MySQL 数据 / 上传文档 / 日志三个目录必须挂 volume」** | ⚠️ **部分调整**：数据库数据仍挂 PVC；**日志改走 stdout 不挂 volume**；上传文档见待定项 1 |
| 同文档 **其余部分**（JVM 内存、容量目标、监控、失败降级） | ✅ 继续有效 |
| `../../02-产品决策/01_Hify产品决策（基准）.md` **待定项 1（向量存储）** | ✅ **结清为 pgvector**，不再是「MySQL JSON + 内存暴力计算」 |
| `01_Hify代码组织（基准）.md` **第十节第 1 项**（向量检索换实现，触发阈值 2 万分段） | ✅ **已提前完成**。`knowledge/vector` 的实现直接是 `PgVectorStore`，不再有 `InMemoryVectorStore` 阶段 |
| `02_Hify模块内部分层（基准）.md` **4.3 表** `knowledge/vector` 行 | ⚠️ 实现类名改为 `PgVectorStore`；`knowledge/storage` 行的实现待待定项 1 拍板 |
| `../01-过程/06_部署架构（Docker与K8s）.md` | ⚠️ 过程稿。**分歧处以本文档为准**——主要是副本数（2 → 1）与前端形态（独立 Deployment + Ingress → 合并进 Nginx） |

### 11.2 需要同步修改的其它文件

| 文件 | 改什么 |
|---|---|
| `../../../hify/CLAUDE.md` | 技术栈补 **PostgreSQL + pgvector**；部署预期从「Compose 本地一键部署」改为「生产 K8s 单副本，本地开发 Compose」 |
| `01_Hify代码组织（基准）.md` 3.1 表 | `knowledge` 模块的数据源说明补上 pgvector |
