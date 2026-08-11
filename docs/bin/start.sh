#!/usr/bin/env bash
#
# Hify 本地一键启动。
#
# 按顺序做五件事，任何一步失败立即停止并打印可执行的修复建议：
#   1. 前置检查：命令齐全、8080 / 5173 端口空闲
#   2. 依赖检查：MySQL 与 Redis 可用（不负责启动它们）
#   3. 构建后端：mvn clean install -DskipTests
#   4. 后台启动后端，轮询 /api/v1/health 直到通过
#   5. 前台启动前端开发服务器
#
# 前端在前台运行，所以 Ctrl+C 会退出本脚本；退出时 **后端会被一并关掉**
# （见 cleanup 函数）。这是刻意的——否则反复运行本脚本会在 8080 上堆积
# 孤儿 java 进程，下次启动报「端口被占用」还找不到是谁占的。
# 想让后端脱离本脚本长期驻留，就别用这个脚本，按 docs/03-本地测试/README.md 手动起。
#
# 用法：
#   ./docs/bin/start.sh
#
set -euo pipefail

# ---------------------------------------------------------------- 常量

# 仓库根目录：本脚本在 docs/bin/ 下，向上两级
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

readonly BACKEND_PORT=8080
readonly FRONTEND_PORT=5173
readonly MYSQL_PORT=3306
readonly REDIS_PORT=6379

readonly MYSQL_CONTAINER="hify-mysql"
readonly REDIS_CONTAINER="hify-redis"

readonly BACKEND_JAR="${REPO_ROOT}/hify-app/target/hify-app-1.0.0-SNAPSHOT.jar"
readonly HEALTH_URL="http://localhost:${BACKEND_PORT}/api/v1/health"
readonly LOG_DIR="${REPO_ROOT}/logs"
readonly BACKEND_LOG="${LOG_DIR}/hify-app.log"

# PID 文件供 stop.sh 使用（脚本被强杀、终端被直接关掉时，陷阱来不及收尾，靠它兜底）。
# 放在 logs/ 下是因为 .gitignore 已经忽略了这个目录，既不用加规则也不用新增顶层目录。
readonly BACKEND_PID_FILE="${LOG_DIR}/hify-app.pid"
readonly FRONTEND_PID_FILE="${LOG_DIR}/hify-web.pid"

# 健康检查最多等多久（秒）。冷启动实测约 1.5s，给足余量覆盖首次 Flyway 迁移
readonly HEALTH_TIMEOUT=90

# 依赖容器的编排文件位置，失败时提示用
readonly COMPOSE_DIR="${REPO_ROOT}/docs/03-本地测试"

# 后端进程 PID，未启动时为空。cleanup 靠它决定要不要收尾
BACKEND_PID=""
# 前端（pnpm dev）进程 PID，未启动时为空
FRONTEND_PID=""

# ---------------------------------------------------------------- 输出

# 终端支持颜色时才上色，重定向到文件时保持纯文本
if [[ -t 1 ]]; then
  readonly C_RED=$'\033[31m'
  readonly C_GREEN=$'\033[32m'
  readonly C_YELLOW=$'\033[33m'
  readonly C_BLUE=$'\033[36m'
  readonly C_OFF=$'\033[0m'
else
  readonly C_RED="" C_GREEN="" C_YELLOW="" C_BLUE="" C_OFF=""
fi

# 打印步骤标题
step() { printf '\n%s==> %s%s\n' "${C_BLUE}" "$*" "${C_OFF}"; }
# 打印成功信息
ok() { printf '    %s✓%s %s\n' "${C_GREEN}" "${C_OFF}" "$*"; }
# 打印提示信息
info() { printf '      %s\n' "$*"; }
# 打印警告
warn() { printf '    %s!%s %s\n' "${C_YELLOW}" "${C_OFF}" "$*"; }

# 打印错误并退出。
# $1 失败原因；后续参数为逐行的修复建议
fail() {
  local reason="$1"
  shift
  printf '\n%s✗ 启动失败：%s%s\n' "${C_RED}" "${reason}" "${C_OFF}" >&2
  local hint
  for hint in "$@"; do
    printf '  %s\n' "${hint}" >&2
  done
  printf '\n' >&2
  exit 1
}

# ---------------------------------------------------------------- 收尾

# 关掉前端。
#
# ⚠️ 必须连子进程一起杀：`pnpm dev` 自己会再 fork 一个 `node vite.js`，
# 只 kill pnpm 的话 vite 会活下来继续占着 5173，下次启动直接报端口冲突。
stop_frontend() {
  if [[ -n "${FRONTEND_PID}" ]] && kill -0 "${FRONTEND_PID}" 2>/dev/null; then
    printf '\n%s==> 正在关闭前端 (PID %s)%s\n' "${C_BLUE}" "${FRONTEND_PID}" "${C_OFF}"
    pkill -P "${FRONTEND_PID}" 2>/dev/null || true
    kill "${FRONTEND_PID}" 2>/dev/null || true

    local waited=0
    while kill -0 "${FRONTEND_PID}" 2>/dev/null && [[ ${waited} -lt 5 ]]; do
      sleep 1
      waited=$((waited + 1))
    done
    kill -9 "${FRONTEND_PID}" 2>/dev/null || true
    ok "前端已关闭"
  fi
  # 进程本来就没起来 / 已经死了，也要把 PID 文件清掉，别给 stop.sh 留下过期记录
  rm -f "${FRONTEND_PID_FILE}"
}

# 关掉后端，避免留下占用 8080 的孤儿进程
stop_backend() {
  if [[ -n "${BACKEND_PID}" ]] && kill -0 "${BACKEND_PID}" 2>/dev/null; then
    printf '%s==> 正在关闭后端 (PID %s)%s\n' "${C_BLUE}" "${BACKEND_PID}" "${C_OFF}"
    kill "${BACKEND_PID}" 2>/dev/null || true

    # 给 Spring Boot 10 秒优雅退出，超时再强杀
    local waited=0
    while kill -0 "${BACKEND_PID}" 2>/dev/null && [[ ${waited} -lt 10 ]]; do
      sleep 1
      waited=$((waited + 1))
    done
    if kill -0 "${BACKEND_PID}" 2>/dev/null; then
      warn "优雅退出超时，强制结束"
      kill -9 "${BACKEND_PID}" 2>/dev/null || true
    fi
    ok "后端已关闭"
  fi
  rm -f "${BACKEND_PID_FILE}"
}

# 脚本退出时统一收尾。正常退出、Ctrl+C、kill、set -e 触发的退出都会走到这里
cleanup() {
  local exit_code=$?
  stop_frontend
  stop_backend
  exit "${exit_code}"
}

# 🔴 INT / TERM 不直接调 cleanup，而是转成 exit，让 EXIT 陷阱统一收尾——
# 否则信号来时 cleanup 会跑一遍、随后 EXIT 再跑一遍，出现重复的关闭日志。
trap 'exit 130' INT
trap 'exit 143' TERM
trap cleanup EXIT

# set -e 触发退出时，先报出是哪一行挂的，便于定位
trap 'printf "\n%s✗ 第 %s 行执行失败：%s%s\n" "${C_RED}" "${LINENO}" "${BASH_COMMAND}" "${C_OFF}" >&2' ERR

# ---------------------------------------------------------------- 工具

# 探测 TCP 端口是否有人监听。
# $1 主机  $2 端口；通返回 0，不通返回非 0
port_open() {
  local host="$1" port="$2"
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 2 "${host}" "${port}" >/dev/null 2>&1
  else
    # 没有 nc 时退回 bash 内建的 /dev/tcp
    (exec 3<>"/dev/tcp/${host}/${port}") >/dev/null 2>&1
  fi
}

# 判断某个 docker 容器是否正在运行。
# $1 容器名
container_running() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$1"
}

# 打印后端日志的最后若干行，失败排查用
tail_backend_log() {
  if [[ -f "${BACKEND_LOG}" ]]; then
    printf '\n  ——— %s 最后 30 行 ———\n' "${BACKEND_LOG}" >&2
    tail -n 30 "${BACKEND_LOG}" >&2
    printf '  ————————————————————————\n' >&2
  fi
}

# ---------------------------------------------------------------- 1. 前置检查

step "1/5 前置检查"

# 必需命令。docker 不是必需的——MySQL/Redis 也可能装在宿主机上
for cmd in java mvn node pnpm curl; do
  command -v "${cmd}" >/dev/null 2>&1 ||
    fail "找不到命令 ${cmd}" "请先安装 ${cmd} 并确保它在 PATH 中。"
done
ok "java / mvn / node / pnpm / curl 均可用"

# 端口占用要在做任何耗时操作之前查，别等构建完几分钟才发现起不来
if port_open 127.0.0.1 "${BACKEND_PORT}"; then
  fail "端口 ${BACKEND_PORT} 已被占用（后端起不来）" \
    "查看占用者： lsof -nP -iTCP:${BACKEND_PORT} -sTCP:LISTEN" \
    "多半是上一次启动的后端没关干净，确认后 kill 掉再重试。"
fi
if port_open 127.0.0.1 "${FRONTEND_PORT}"; then
  fail "端口 ${FRONTEND_PORT} 已被占用（前端起不来）" \
    "查看占用者： lsof -nP -iTCP:${FRONTEND_PORT} -sTCP:LISTEN" \
    "vite 配了 strictPort，端口被占不会自动漂移，必须先腾出来。"
fi
ok "端口 ${BACKEND_PORT} / ${FRONTEND_PORT} 空闲"

# ---------------------------------------------------------------- 2. 依赖检查

step "2/5 检查 MySQL 与 Redis"

# 容器在跑就用业务账号做真实查询（能同时验证账号与库存在），
# 否则退回端口探测——只能证明有人在监听，证明不了账号密码对
if container_running "${MYSQL_CONTAINER}"; then
  docker exec "${MYSQL_CONTAINER}" \
    mysql -uhify -phify123456 -D hify -e 'SELECT 1' >/dev/null 2>&1 ||
    fail "MySQL 容器在运行，但用业务账号查 hify 库失败" \
      "看日志： docker logs ${MYSQL_CONTAINER} --tail 50" \
      "容器可能还在初始化，等几秒重试；或密码与 docker-compose.yml 不一致。"
  ok "MySQL 可用（容器 ${MYSQL_CONTAINER}，业务账号查询通过）"
elif port_open 127.0.0.1 "${MYSQL_PORT}"; then
  warn "MySQL 端口 ${MYSQL_PORT} 有响应，但不是 ${MYSQL_CONTAINER} 容器"
  info "只做了端口探测，没验证账号与库是否正确。"
else
  fail "MySQL 不可用（${MYSQL_PORT} 无人监听）" \
    "启动依赖容器： cd ${COMPOSE_DIR} && docker compose up -d" \
    "等两个服务都显示 healthy（约 6 秒）后重试： docker compose ps"
fi

if container_running "${REDIS_CONTAINER}"; then
  docker exec "${REDIS_CONTAINER}" redis-cli -a hify123456 ping 2>/dev/null | grep -q PONG ||
    fail "Redis 容器在运行，但 PING 未返回 PONG" \
      "看日志： docker logs ${REDIS_CONTAINER} --tail 50" \
      "多半是密码与 docker-compose.yml 不一致。"
  ok "Redis 可用（容器 ${REDIS_CONTAINER}，PING 通过）"
elif port_open 127.0.0.1 "${REDIS_PORT}"; then
  warn "Redis 端口 ${REDIS_PORT} 有响应，但不是 ${REDIS_CONTAINER} 容器"
  info "只做了端口探测，没验证密码是否正确。"
else
  fail "Redis 不可用（${REDIS_PORT} 无人监听）" \
    "启动依赖容器： cd ${COMPOSE_DIR} && docker compose up -d"
fi

# ---------------------------------------------------------------- 3. 构建后端

step "3/5 构建后端（mvn clean install -DskipTests）"
info "八个模块全量构建，首次或依赖有变动时会比较慢。"

cd "${REPO_ROOT}"
mvn -q clean install -DskipTests ||
  fail "Maven 构建失败" \
    "上面就是 Maven 的报错，先修复编译错误。" \
    "只是想跳过测试之外的问题，可单独跑： mvn clean install -DskipTests"

# -q 模式下 Maven 不打成功日志，这里自己确认产物真的生成了
[[ -f "${BACKEND_JAR}" ]] ||
  fail "构建结束但没找到可执行 jar：${BACKEND_JAR}" \
    "检查 hify-app 的打包插件配置是否正常。"
ok "构建完成：$(basename "${BACKEND_JAR}")"

# ---------------------------------------------------------------- 4. 启动后端并等待健康检查

step "4/5 后台启动后端并等待健康检查"

mkdir -p "${LOG_DIR}"
# 日志目录已被 .gitignore 忽略（logs/），不会误入库
nohup java -jar "${BACKEND_JAR}" >"${BACKEND_LOG}" 2>&1 &
BACKEND_PID=$!
# 落 PID 文件，供 stop.sh 在本脚本没能正常收尾时兜底
echo "${BACKEND_PID}" >"${BACKEND_PID_FILE}"
ok "后端已启动，PID ${BACKEND_PID}，日志 ${BACKEND_LOG}"

info "轮询 ${HEALTH_URL}（最多 ${HEALTH_TIMEOUT} 秒）…"
health_ok=false
for ((elapsed = 0; elapsed < HEALTH_TIMEOUT; elapsed++)); do
  # 进程提前退出就别再等了，直接把日志尾巴甩出来
  if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    tail_backend_log
    BACKEND_PID=""
    fail "后端进程在健康检查通过前就退出了" \
      "原因见上面的日志尾部，常见是数据库连不上或端口冲突。" \
      "完整日志： ${BACKEND_LOG}"
  fi

  if curl -fsS --max-time 3 "${HEALTH_URL}" 2>/dev/null | grep -q '"code":200'; then
    health_ok=true
    ok "健康检查通过（耗时约 ${elapsed} 秒）"
    break
  fi
  sleep 1
done

if [[ "${health_ok}" != true ]]; then
  tail_backend_log
  fail "等待 ${HEALTH_TIMEOUT} 秒后健康检查仍未通过" \
    "进程还活着但 ${HEALTH_URL} 不通，日志尾部见上。" \
    "完整日志： ${BACKEND_LOG}"
fi

# ---------------------------------------------------------------- 5. 启动前端

step "5/5 启动前端开发服务器"

cd "${REPO_ROOT}/hify-web"

# 首次运行或依赖有更新时装一次
if [[ ! -d node_modules ]]; then
  info "node_modules 不存在，先执行 pnpm install…"
  pnpm install ||
    fail "pnpm install 失败" \
      "多半是 npm 源不通，换源后重试：" \
      "  pnpm config set registry https://registry.npmmirror.com"
  ok "依赖安装完成"
fi

printf '\n%s全部就绪%s\n' "${C_GREEN}" "${C_OFF}"
info "后端  http://localhost:${BACKEND_PORT}/api/v1/health  （PID ${BACKEND_PID}）"
info "前端  http://localhost:${FRONTEND_PORT}/"
info "按 Ctrl+C 停止：前端与后端会一起关闭。"
printf '\n'

# 🔴 前端必须放到后台再 wait，不能直接前台跑。
# 非交互 bash 在等待前台子进程期间**不处理信号**——直接 `pnpm dev` 的话，
# 用 `kill <脚本PID>` 发来的 INT / TERM 会一直挂着，陷阱不执行，后端就成了孤儿。
# （终端里按 Ctrl+C 是发给整个前台进程组，两种写法都能退出，但 kill 单个 PID 只有这种写法才行。）
# 也不能用 exec——exec 会替换掉当前进程，陷阱同样不会执行。
pnpm dev &
FRONTEND_PID=$!
echo "${FRONTEND_PID}" >"${FRONTEND_PID_FILE}"

# wait 可被陷阱打断；被打断时返回值 > 128，正常退出时是前端自己的退出码。
# 🔴 必须用 `|| frontend_exit=$?` 这种形式承接非零返回值：
# 单纯 `set +e` 只关掉 errexit，关不掉 ERR 陷阱——前端被 Ctrl+C 正常结束时，
# 屏幕上会多出一条「第 N 行执行失败：wait ...」的红字，看着像脚本自己崩了。
frontend_exit=0
wait "${FRONTEND_PID}" || frontend_exit=$?

# 走到这里说明前端是自己退的，清空 PID 避免 cleanup 重复处理
FRONTEND_PID=""
exit "${frontend_exit}"
