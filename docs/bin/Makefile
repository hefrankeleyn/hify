# Hify 项目常用命令入口。
#
# 启停逻辑本身在同目录的 start.sh 与 stop.sh 里，本文件只做转发，
# 不重复实现——两处各写一份迟早会不一致。
#
# 🔴 本文件不在仓库根目录，所以**不能依赖当前工作目录**。
# 所有路径都从 ROOT 展开（由本文件自身位置反推），因此下面两种调用方式等价：
#
#   make -C docs/bin start          # 在仓库根目录敲
#   cd docs/bin && make start       # 进到本目录敲
#
# 常用：
#   make -C docs/bin                查看全部 target
#   make -C docs/bin start          起全栈（前台运行，Ctrl+C 停止）
#   make -C docs/bin package        构建并打出可分发的 tar.gz

SHELL := /bin/bash

# 本文件所在目录与仓库根目录。$(lastword $(MAKEFILE_LIST)) 取到的就是本文件，
# 无论 make 是用 -C、-f 还是直接在本目录调用，都能算对。
MAKEFILE_PATH := $(abspath $(lastword $(MAKEFILE_LIST)))
BIN_DIR       := $(patsubst %/,%,$(dir $(MAKEFILE_PATH)))
ROOT          := $(abspath $(BIN_DIR)/../..)

# 版本号取自父 pom 的第一个 <version>。父 pom 没有 <parent> 块，
# 所以第一个 <version> 就是项目版本，不会误取到 Spring Boot 的版本。
VERSION := $(shell sed -n 's|.*<version>\(.*\)</version>.*|\1|p' $(ROOT)/pom.xml | head -1)

BACKEND_JAR := $(ROOT)/hify-app/target/hify-app-$(VERSION).jar
WEB_DIR     := $(ROOT)/hify-web
WEB_DIST    := $(WEB_DIR)/dist

# 打包输出目录。dist/ 已被 .gitignore 忽略，不会误入库
PKG_DIR   := $(ROOT)/dist
PKG_NAME  := hify-$(VERSION)
PKG_STAGE := $(PKG_DIR)/$(PKG_NAME)
PKG_FILE  := $(PKG_DIR)/$(PKG_NAME).tar.gz

.DEFAULT_GOAL := help
.PHONY: help start stop restart build build-backend build-frontend clean package

help: ## 显示本帮助
	@echo "Hify $(VERSION)   （仓库根：$(ROOT)）"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""

start: ## 启动全栈：检查依赖 → 构建 → 起后端 → 起前端（前台，Ctrl+C 停止）
	@"$(BIN_DIR)/start.sh"

stop: ## 停止后端与前端（按 PID 文件，先 SIGTERM 再 SIGKILL）
	@"$(BIN_DIR)/stop.sh"

restart: ## 先 stop 再 start。stop 失败会中止，不会在残留进程上硬起
	@"$(BIN_DIR)/stop.sh"
	@"$(BIN_DIR)/start.sh"

build: build-backend build-frontend ## 构建后端 + 前端

build-backend: ## 只构建后端（mvn clean install -DskipTests）
	@echo "==> 构建后端"
	cd "$(ROOT)" && mvn clean install -DskipTests

build-frontend: ## 只构建前端（pnpm install + pnpm build，含类型检查）
	@echo "==> 构建前端"
	cd "$(WEB_DIR)" && pnpm install && pnpm build

clean: ## 清理构建产物（target/、hify-web/dist、dist/）
	@echo "==> 清理构建产物"
	cd "$(ROOT)" && mvn -q clean
	rm -rf "$(WEB_DIST)"
	rm -rf "$(PKG_DIR)"
	@echo "    已清理 target/ · hify-web/dist · dist/"
	@echo "    注意：node_modules 与 logs/ 不在清理范围内，需要时手工删"

package: build ## 构建并打包成可分发的 tar.gz（输出到仓库根的 dist/）
	@echo "==> 打包 $(PKG_NAME)"
	@test -f "$(BACKEND_JAR)" \
		|| { echo "找不到后端 jar：$(BACKEND_JAR)"; echo "确认 pom.xml 里的版本号与 VERSION=$(VERSION) 一致"; exit 1; }
	@test -d "$(WEB_DIST)" \
		|| { echo "找不到前端产物：$(WEB_DIST)"; exit 1; }
	@rm -rf "$(PKG_STAGE)"
	@mkdir -p "$(PKG_STAGE)/web"
	@cp "$(BACKEND_JAR)" "$(PKG_STAGE)/hify-app.jar"
	@cp -R "$(WEB_DIST)/." "$(PKG_STAGE)/web/"
	@echo "Hify $(VERSION)"                                             > "$(PKG_STAGE)/README.txt"
	@echo ""                                                           >> "$(PKG_STAGE)/README.txt"
	@echo "内容："                                                     >> "$(PKG_STAGE)/README.txt"
	@echo "  hify-app.jar  后端可执行 jar（需 JDK 21）"                >> "$(PKG_STAGE)/README.txt"
	@echo "  web/          前端静态产物，交给 Nginx 托管"              >> "$(PKG_STAGE)/README.txt"
	@echo ""                                                           >> "$(PKG_STAGE)/README.txt"
	@echo "运行前需先备好 MySQL 8.x 与 Redis 7.x。连接信息全部有本地默认值，"  >> "$(PKG_STAGE)/README.txt"
	@echo "用下列环境变量覆盖（名称取自 application.yml，勿臆造）："          >> "$(PKG_STAGE)/README.txt"
	@echo "  MYSQL_HOST  MYSQL_PORT  MYSQL_DATABASE  MYSQL_USERNAME  MYSQL_PASSWORD" >> "$(PKG_STAGE)/README.txt"
	@echo "  REDIS_HOST  REDIS_PORT  REDIS_DATABASE  REDIS_PASSWORD"        >> "$(PKG_STAGE)/README.txt"
	@echo "  （PGVECTOR_* 一组也已声明，但 hify-knowledge 尚未落地，当前不生效）" >> "$(PKG_STAGE)/README.txt"
	@echo ""                                                           >> "$(PKG_STAGE)/README.txt"
	@echo "启动后端："                                                 >> "$(PKG_STAGE)/README.txt"
	@echo "  java -XX:MaxRAMPercentage=50 -jar hify-app.jar"           >> "$(PKG_STAGE)/README.txt"
	@echo ""                                                           >> "$(PKG_STAGE)/README.txt"
	@echo "Nginx 托管 web/ 时必须注意（对话接口是 SSE 长连接）："      >> "$(PKG_STAGE)/README.txt"
	@echo "  location /api/ 下务必 gzip off; proxy_buffering off;"     >> "$(PKG_STAGE)/README.txt"
	@echo "  proxy_read_timeout 需大于后端 SseEmitter 的 300s，建议 360s" >> "$(PKG_STAGE)/README.txt"
	@echo "  history 路由需 try_files \$$uri \$$uri/ /index.html;"      >> "$(PKG_STAGE)/README.txt"
	@tar -czf "$(PKG_FILE)" -C "$(PKG_DIR)" "$(PKG_NAME)"
	@rm -rf "$(PKG_STAGE)"
	@echo "    $(PKG_FILE)  ($$(du -h "$(PKG_FILE)" | cut -f1))"
