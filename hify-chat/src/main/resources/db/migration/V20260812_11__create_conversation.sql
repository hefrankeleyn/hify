-- 对话会话(hify-chat)。target_type/target_id 多态关联 agent 或 workflow。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.4
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `conversation` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `title`       VARCHAR(255)     NOT NULL DEFAULT ''  COMMENT '会话标题',
    `target_type` VARCHAR(16)      NOT NULL              COMMENT '会话目标类型:AGENT/WORKFLOW',
    `target_id`   BIGINT UNSIGNED  NOT NULL              COMMENT '会话目标 id,按 target_type 逻辑引用 agent 或 workflow,不建外键',
    `user_id`     BIGINT UNSIGNED  NOT NULL              COMMENT '所属用户 id(逻辑引用 user,不建外键;user 表未落地前先占位)',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 1    COMMENT '状态:1 正常 0 已归档',

    PRIMARY KEY (`id`),
    KEY `idx_conversation_user` (`user_id`, `deleted`, `updated_at`) COMMENT '我的会话列表,按最近更新排序',
    KEY `idx_conversation_target` (`target_type`, `target_id`, `deleted`) COMMENT '某 Agent/Workflow 下有哪些会话,停用前查影响面'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='对话会话';
