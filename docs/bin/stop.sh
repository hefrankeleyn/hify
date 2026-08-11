#!/usr/bin/env bash
#
# 优雅停止 Hify 的后端与前端。
#
# 正常情况下用不到它——`start.sh` 退出时会自己把两个进程关掉。
# 它兜的是**陷阱来不及执行**的场景：
#   · 直接关掉终端窗口 / `kill -9` 掉 start.sh
#   · 机器休眠、SSH 断线
#   · 手工起过服务但忘了怎么关
# 这些情况下 start.sh 留下的 PID 文件还在，进程也还在，用本脚本收尾。
#
# 停止策略：读 PID 文件 → 校验进程确实是我们起的 → SIGTERM → 等待 → 仍在则 SIGKILL。
#
# 🔴 为什么要校验进程身份：PID 会被操作系统复用。一个几天前的过期 PID 文件，
#    里面的号码此刻可能属于任何别的程序，闭眼 kill 会误伤。所以先比对命令行特征，
#    对不上就拒绝动手，只提示，不 kill。
#
# 用法：
#   ./docs/bin/stop.sh
#
# 退出码：0 该停的都停了（含本来就没在跑）；1 有进程拒绝停或杀不掉。
#
# ⚠️ 端口仍被占用**不算失败**，只 warn。因为占用者可能是本脚本管不到的进程
#    （IDE 里跑的后端、手工敲的 pnpm dev），那不是「停止失败」，
#    让它翻转退出码会使 `make stop` 在这个很常见的场景下看起来是坏的。
#
set -euo pipefail

# ---------------------------------------------------------------- 常量

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

readonly LOG_DIR="${REPO_ROOT}/logs"
readonly BACKEND_PID_FILE="${LOG_DIR}/hify-app.pid"
readonly FRONTEND_PID_FILE="${LOG_DIR}/hify-web.pid"

readonly BACKEND_PORT=8080
readonly FRONTEND_PORT=5173

# 优雅退出的等待上限（秒）。后端要跑 Spring 的关闭钩子，给得比前端宽
readonly BACKEND_GRACE=10
readonly FRONTEND_GRACE=5

# 进程身份特征：`ps -o command=` 的输出里必须能匹配上，否则拒绝 kill
readonly BACKEND_PATTERN='hify-app-.*\.jar'
readonly FRONTEND_PATTERN='pnpm|vite'

# 有任何一项没停掉就置 1，作为脚本退出码
exit_code=0

# ---------------------------------------------------------------- 输出

if [[ -t 1 ]]; then
  readonly C_RED=$'\033[31m'
  readonly C_GREEN=$'\033[32m'
  readonly C_YELLOW=$'\033[33m'
  readonly C_BLUE=$'\033[36m'
  readonly C_OFF=$'\033[0m'
else
  readonly C_RED="" C_GREEN="" C_YELLOW="" C_BLUE="" C_OFF=""
fi

step() { printf '\n%s==> %s%s\n' "${C_BLUE}" "$*" "${C_OFF}"; }
ok() { printf '    %s✓%s %s\n' "${C_GREEN}" "${C_OFF}" "$*"; }
info() { printf '      %s\n' "$*"; }
warn() { printf '    %s!%s %s\n' "${C_YELLOW}" "${C_OFF}" "$*"; }
err() { printf '    %s✗%s %s\n' "${C_RED}" "${C_OFF}" "$*"; }

# ---------------------------------------------------------------- 工具

# 端口上是否还有人监听。$1 端口
port_open() {
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 2 127.0.0.1 "$1" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
  fi
}

# 停止一个服务。
#
# $1 展示名称（后端 / 前端）
# $2 PID 文件路径
# $3 命令行特征正则，用于确认这个 PID 真是我们起的进程
# $4 优雅退出等待秒数
# $5 是否连子进程一起杀（true / false）
#
# 返回 0 表示已停止（含本来就没在跑）；返回 1 表示没停掉或拒绝停。
stop_service() {
  local name="$1" pid_file="$2" pattern="$3" grace="$4" kill_children="$5"

  # ① PID 文件不存在 —— 认为没在跑
  if [[ ! -f "${pid_file}" ]]; then
    info "${name}：没有 PID 文件，视为未运行"
    return 0
  fi

  local pid
  pid="$(tr -d '[:space:]' <"${pid_file}")"

  # ② PID 文件内容不是纯数字 —— 文件坏了，清掉
  if [[ ! "${pid}" =~ ^[0-9]+$ ]]; then
    warn "${name}：PID 文件内容不是合法进程号（'${pid}'），已清理该文件"
    rm -f "${pid_file}"
    return 0
  fi

  # ③ 进程已经不在了 —— 过期 PID 文件，清掉
  if ! kill -0 "${pid}" 2>/dev/null; then
    info "${name}：PID ${pid} 已不存在（过期记录），已清理 PID 文件"
    rm -f "${pid_file}"
    return 0
  fi

  # ④ 🔴 身份校验：PID 会被系统复用，对不上特征就绝不动手
  local cmdline
  cmdline="$(ps -o command= -p "${pid}" 2>/dev/null || true)"
  if ! printf '%s' "${cmdline}" | grep -Eq "${pattern}"; then
    err "${name}：PID ${pid} 不像是 Hify 的进程，已跳过（未 kill）"
    info "该进程实际命令行： ${cmdline:-<读不到>}"
    info "多半是过期 PID 文件撞上了被系统复用的进程号。"
    info "确认无误后手工清理： rm ${pid_file}"
    return 1
  fi

  step "停止${name} (PID ${pid})"

  # ⑤ 先处理子进程：pnpm 会 fork 出 vite，只杀父进程会留下 vite 继续占端口
  if [[ "${kill_children}" == "true" ]]; then
    local children
    children="$(pgrep -P "${pid}" 2>/dev/null | tr '\n' ' ' || true)"
    if [[ -n "${children// /}" ]]; then
      info "同时结束子进程： ${children}"
      pkill -P "${pid}" 2>/dev/null || true
    fi
  fi

  # ⑥ SIGTERM，然后逐秒等
  kill -TERM "${pid}" 2>/dev/null || true
  local waited=0
  while kill -0 "${pid}" 2>/dev/null && [[ ${waited} -lt ${grace} ]]; do
    sleep 1
    waited=$((waited + 1))
  done

  # ⑦ 超时仍在 —— SIGKILL
  if kill -0 "${pid}" 2>/dev/null; then
    warn "${grace} 秒内未退出，改用 SIGKILL"
    kill -9 "${pid}" 2>/dev/null || true
    sleep 1
    if kill -0 "${pid}" 2>/dev/null; then
      err "${name}：SIGKILL 之后进程仍然存在，PID ${pid}"
      info "可能是不可中断的系统调用卡住了，稍后重试或手工排查： ps -p ${pid}"
      return 1
    fi
    ok "${name}已强制结束（SIGKILL）"
  else
    ok "${name}已优雅退出（SIGTERM，耗时 ${waited} 秒）"
  fi

  rm -f "${pid_file}"
  return 0
}

# ---------------------------------------------------------------- 主流程

printf '%s停止 Hify 本地服务%s\n' "${C_BLUE}" "${C_OFF}"

# 先停前端再停后端：顺序与启动相反，避免前端在后端消失后刷一屏代理报错
stop_service "前端" "${FRONTEND_PID_FILE}" "${FRONTEND_PATTERN}" "${FRONTEND_GRACE}" true || exit_code=1
stop_service "后端" "${BACKEND_PID_FILE}" "${BACKEND_PATTERN}" "${BACKEND_GRACE}" false || exit_code=1

# ---------------------------------------------------------------- 收尾核对

step "核对端口"

# PID 文件只认得本脚本体系起的进程。端口仍被占，说明有它管不到的残留
# （比如 IDE 里跑的后端、手工 pnpm dev、或父进程死后被 init 收养的孤儿 vite）
for entry in "${BACKEND_PORT}:后端" "${FRONTEND_PORT}:前端"; do
  port="${entry%%:*}"
  label="${entry##*:}"
  if port_open "${port}"; then
    # 只提示不改退出码，理由见文件头
    warn "端口 ${port}（${label}）仍被占用"
    info "PID 文件管不到它，查一下是谁： lsof -nP -iTCP:${port} -sTCP:LISTEN"
  else
    ok "端口 ${port}（${label}）已释放"
  fi
done

printf '\n'
if [[ ${exit_code} -eq 0 ]]; then
  printf '%s已停止%s\n\n' "${C_GREEN}" "${C_OFF}"
else
  printf '%s有进程没能停掉，见上面的提示%s\n\n' "${C_RED}" "${C_OFF}"
fi
exit "${exit_code}"
