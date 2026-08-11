-- 提供商下的可用模型清单(hify-provider)。model_provider 1:N model。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.4
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `model` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `provider_id`         BIGINT UNSIGNED  NOT NULL           COMMENT '所属 model_provider id(逻辑引用,不建外键)',
    `name`                VARCHAR(128)     NOT NULL           COMMENT '模型标识,如 gpt-4o(提供商侧原始名称)',
    `type`                VARCHAR(16)      NOT NULL           COMMENT '模型类型:CHAT/EMBEDDING',
    `context_length`      INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '上下文长度(token 数),CHAT 模型使用',
    `embedding_dimension` INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '向量维度,仅 EMBEDDING 模型使用;需与知识库 pgvector 的 document_chunk.embedding 列维度(固定 1536,CLAUDE.md 5.7)一致才可用于知识库',
    `status`              TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态:1 启用 0 停用',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_provider_name` (`provider_id`, `name`, `deleted`) COMMENT '同一提供商下模型名唯一,配合 deleted 支持删除后重建同名',
    KEY `idx_model_type` (`type`, `deleted`, `status`) COMMENT 'Agent 配置页按类型(CHAT/EMBEDDING)拉下拉列表'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='提供商下的可用模型清单';
