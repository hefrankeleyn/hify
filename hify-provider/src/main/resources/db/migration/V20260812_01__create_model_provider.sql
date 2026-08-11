-- 模型提供商配置(hify-provider)。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.3/5.4
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `model_provider` (
    -- ── 必备五字段(CLAUDE.md 5.2,勿改类型) ──
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    -- ── 业务字段 ──
    `name`     VARCHAR(64)      NOT NULL              COMMENT '提供商展示名称,用户自定义',
    `type`     VARCHAR(32)      NOT NULL              COMMENT '提供商类型:OPENAI/CLAUDE/GEMINI/OLLAMA',
    `base_url` VARCHAR(255)     NOT NULL              COMMENT 'API 基础地址',
    `api_key`  VARCHAR(512)     NOT NULL DEFAULT ''   COMMENT 'API Key 密文,禁止明文落库',
    `status`   TINYINT UNSIGNED NOT NULL DEFAULT 1    COMMENT '状态:1 启用 0 停用',

    -- ── 索引 ──
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_provider_name` (`name`, `deleted`) COMMENT '展示名称唯一,配合 deleted 支持删除后重建同名(CLAUDE.md 5.3)',
    KEY `idx_model_provider_status` (`status`, `deleted`, `id`) COMMENT '前端下拉只列启用中的提供商'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='模型提供商配置:OpenAI/Claude/Gemini/Ollama 等接入信息';
