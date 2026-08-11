-- Agent 定义(hify-agent)。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.3/5.4
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `agent` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `name`          VARCHAR(128)     NOT NULL DEFAULT ''    COMMENT 'Agent 名称',
    `system_prompt` TEXT             NOT NULL DEFAULT ('')  COMMENT '系统提示词',
    `model_id`      BIGINT UNSIGNED  NOT NULL               COMMENT '所选模型 id(逻辑引用 model,不建外键)',
    `model_params`  JSON             NOT NULL DEFAULT ('{}') COMMENT '模型调用参数,如 temperature/max_tokens',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态:1 启用 0 停用',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_name` (`name`, `deleted`) COMMENT 'Agent 名称唯一,配合 deleted 支持删除后重建同名(CLAUDE.md 5.3)',
    KEY `idx_agent_creator` (`creator_id`, `deleted`, `updated_at`) COMMENT '我的 Agent 列表,按最近更新排序',
    KEY `idx_agent_model` (`model_id`, `deleted`) COMMENT '模型停用/删除前查影响面'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 定义';
