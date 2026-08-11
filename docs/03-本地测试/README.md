# Hify 本地启动说明

本目录放本地开发环境的编排与说明。`docker-compose.yml` 起依赖，应用在 IDE 或命令行跑（CLAUDE.md 2.4）。

---

## 一、前置要求

| 组件 | 要求 | 本机已验证版本 |
|---|---|---|
| JDK | 21（LTS） | `openjdk 21.0.8` |
| Maven | 3.9+ | `3.9.11` |
| Docker | 带 Compose v2 | `29.6.2` / Compose `v5.3.1` |

端口 **8080**（应用）、**3306**（MySQL）、**6379**（Redis）需空闲。

---

## 二、三步启动

```bash
# 1. 起依赖（在本目录执行）
cd docs/03-本地测试 && docker compose up -d

# 2. 构建（在仓库根目录执行）
cd ../.. && mvn clean install -DskipTests

# 3. 运行
java -jar hify-app/target/hify-app-1.0.0-SNAPSHOT.jar
```

在 IDE 里跑就直接运行 `com.hify.HifyApplication`，不需要配任何启动参数——`application.yml` 里所有连接信息都带本地默认值。

---

## 三、依赖容器

### 启动与状态

```bash
docker compose up -d                # 启动
docker compose ps                   # 等两个服务都显示 healthy，约 6 秒
docker compose logs -f mysql        # 看日志
docker compose down                 # 停止（保留数据）
```

> MySQL 的健康检查是**用业务账号查业务库**（`mysql -uhify -phify123456 -D hify -e "SELECT 1"`），
> 不是 `mysqladmin ping`。后者只要服务端有应答就算成功，会在业务库和账号还没建好时就报 healthy。
> 所以这里的 healthy 是可信的，可以直接拿来做 `depends_on: service_healthy`。

### 连接信息

| | 地址 | 库 / DB | 账号 | 密码 |
|---|---|---|---|---|
| MySQL | `localhost:3306` | `hify` | `hify` | `hify123456` |
| MySQL（管理员） | `localhost:3306` | — | `root` | `root123456` |
| Redis | `localhost:6379` | `0` | — | `hify123456` |

```bash
# 命令行连接
docker exec -it hify-mysql mysql -uhify -phify123456 hify
docker exec -it hify-redis redis-cli -a hify123456
```

**密码是明文的，仅供本地开发。** 生产走 K8s Secret 注入环境变量覆盖（CLAUDE.md 2.4）。

### 数据落盘位置

```
/Users/lifei/Documents/data/hify/
├── mysql/    MySQL 数据目录
└── redis/    Redis AOF
```

换路径设环境变量即可，不必改 `docker-compose.yml`：

```bash
HIFY_DATA_DIR=/your/path docker compose up -d
```

**重置数据库要手工删目录**，`down -v` 对宿主机挂载无效：

```bash
docker compose down && rm -rf /Users/lifei/Documents/data/hify/mysql
```

> ⚠️ macOS 上 MySQL 数据目录走宿主机挂载（VirtioFS）比 Docker 命名卷慢——
> MySQL 是大量小块随机 IO，正是文件共享层最吃亏的场景。本地数据量小可以接受，
> 换来的是能在 Finder 里直接看到、备份、拷走数据文件。

---

## 四、确认启动成功

日志里出现这两行即为成功，全程约 1.5 秒：

```
INFO  o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
INFO  com.hify.HifyApplication - [STARTUP] Hify 启动完成, port=8080, contextPath=, profiles=[]
```

数据库侧可以核对 Flyway 已接管：

```bash
docker exec hify-mysql mysql -uhify -phify123456 -D hify -e "SHOW TABLES"
# 目前只有 flyway_schema_history —— 还没有任何业务表
```

### 三条 WARN 是正常的，不用管

```
No MyBatis mapper was found in '[com.hify.*.mapper]' package.
```
还没有任何 Mapper 接口。第一个 Mapper 落地后自动消失。

```
No migrations found. Are your locations set up correctly?
```
还没有任何 Flyway 脚本。第一个 `V*.sql` 落地后自动消失。

```
Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway...
```
Flyway 11.7.2 官方支持到 MySQL 8.1，实测 8.4 迁移功能正常。想消掉就把 `docker-compose.yml` 里的镜像换成 `mysql:8.0`，其余配置无需改动。

---

## 五、当前可以做什么

**基本什么都做不了——目前没有任何业务接口。** 已就位的只有骨架：

- 八个 Maven 模块与包结构
- `Result` / `PageResult` / `ErrorCode` / `BizException` / `GlobalExceptionHandler`
- MyBatis-Plus 配置（分页、自动填充、逻辑删除）、Redis 配置与 `RedisUtil`

随便访问一个路径可以看到统一响应体在工作：

```bash
curl -i http://localhost:8080/
```
```
HTTP/1.1 200
{"code":1000,"message":"系统内部错误","data":null}
```

> ⚠️ **注意这里返回的是 200 而不是 404**，这是一个**已知缺口**，不是设计如此。
> `GlobalExceptionHandler` 目前只处理 `BizException`、`MethodArgumentNotValidException`
> 和兜底的 `Exception`。404（`NoResourceFoundException`）、405、查询参数绑定失败
> （`BindException`）、JSON 格式错（`HttpMessageNotReadableException`）
> 全都落进兜底分支，被报成「系统内部错误」并打一条 ERROR 全栈日志。
> `ErrorCode` 里 `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `PARAM_INVALID` 都已备好，补 handler 即可。

---

## 六、尚未就绪的部分

| 项 | 状态 |
|---|---|
| pgvector | **未启动**。CLAUDE.md 2.4 要求本地依赖含 pgvector，但 `hify-knowledge` 尚未落地，容器和第二个 DataSource Bean 都还没建。连接参数已按 `hify.knowledge.vector.*` 记在 `application.yml` 里 |
| Redis 连通性 | **未端到端验证**。Lettuce 是懒连接，启动阶段不建连，所以「启动成功」不能证明密码配对了。容器侧已用 `redis-cli` 验过 |
| 业务表 | 无。Flyway 脚本要放在各模块的 `src/main/resources/db/migration/` 下 |

---

## 七、排错

**应用起不来，报 `Communications link failure` / `Connection refused`**
容器没起或还没 ready。`docker compose ps` 确认两个服务都是 `healthy` 再启动应用。

**端口被占用**
```bash
lsof -nP -iTCP:3306 -sTCP:LISTEN     # 换 6379 / 8080
```
本机已有 MySQL 时，改 `docker-compose.yml` 的端口映射（如 `"3307:3306"`），
并同步设 `MYSQL_PORT=3307` 再启动应用。

**`docker compose up` 拉镜像卡住**
镜像源不通。用本地已有镜像跳过拉取：
```bash
docker images | grep -E "mysql|redis"
docker compose up -d --pull never
```

**`mvn install` 拉依赖卡住**
同理，Maven 镜像源不通。确认 `~/.m2/settings.xml` 与 `$MAVEN_HOME/conf/settings.xml`
里的 mirror 可达——注意**两个文件都要看**，全局配置里的 mirror 同样生效。

**改了数据库密码但应用还连旧的**
`application.yml` 用的是 `${MYSQL_PASSWORD:hify123456}` 形式，
环境变量优先级高于默认值。检查 shell 里有没有残留的 `MYSQL_PASSWORD`。

**MySQL 客户端连不上，报认证插件错误**
MySQL 8.4 已彻底移除 `mysql_native_password`，只支持 `caching_sha2_password`。
`mysql-connector-j` 9.x 原生支持，但部分老版本 GUI 客户端会失败——
用 root 建个兼容账号，或改用较新的客户端。
