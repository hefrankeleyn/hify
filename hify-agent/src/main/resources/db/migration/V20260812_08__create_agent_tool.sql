-- Agent ↔ 工具 绑定关系(多对多中间表,归 hify-agent 持有)。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md 3.1、CLAUDE.md 5.1/5.4
-- 🔴 只存两个 id,绝不与 tool 主表 JOIN(CLAUDE.md 4.2/5.1)
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `agent_tool` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `agent_id` BIGINT UNSIGNED NOT NULL COMMENT 'agent id(逻辑引用,不建外键)',
    `tool_id`  BIGINT UNSIGNED NOT NULL COMMENT 'tool id(逻辑引用 hify-mcp 的 tool,不建外键)',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_tool` (`agent_id`, `tool_id`, `deleted`) COMMENT '同一 Agent 不重复绑定同一工具;同时服务"查某 Agent 绑了哪些工具"',
    KEY `idx_agent_tool_tool` (`tool_id`, `deleted`, `agent_id`) COMMENT '反向查"某工具被哪些 Agent 绑定",工具停用前提示影响面(CLAUDE.md 4.3)'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 与 MCP 工具的绑定关系(多对多中间表)';
