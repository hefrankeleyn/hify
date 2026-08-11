-- MCP 工具接入(hify-mcp)。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.4
--
-- ⚠️ 与「16 张表」决策核对过:MCP 域只有这一张表,因此 Server 连接信息(url/传输方式/鉴权)
--    与工具信息(名称/入参 schema)同表存放,且按「工具」粒度(而不是「Server」粒度)存一行——
--    因为 agent_tool 需要绑定到具体某个工具,而不是整个 Server。
--    代价:同一 Server 下的多个工具会重复 server_url/transport_type/auth_config,
--    在当前"几十行"量级下可接受(CLAUDE.md 5.6),量级变化时再拆成 server/tool 两张表。
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `tool` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `server_name`      VARCHAR(128)     NOT NULL                            COMMENT 'MCP Server 展示名称',
    `server_url`       VARCHAR(255)     NOT NULL                            COMMENT 'MCP Server 地址',
    `transport_type`   VARCHAR(32)      NOT NULL                            COMMENT '传输方式:SSE/STDIO/STREAMABLE_HTTP',
    `auth_config`      VARCHAR(512)     NOT NULL DEFAULT ''                 COMMENT '鉴权信息密文,禁止明文落库',
    `tool_name`        VARCHAR(128)     NOT NULL                            COMMENT '工具名称,Server 侧原始标识符',
    `tool_description` VARCHAR(512)     NOT NULL DEFAULT ''                 COMMENT '工具描述',
    `input_schema`     JSON             NOT NULL DEFAULT ('{}')             COMMENT '工具入参 JSON Schema',
    `last_sync_time`   DATETIME(3)      NOT NULL DEFAULT '1970-01-01 00:00:00.000' COMMENT '最后一次从 Server 同步的时间,从未同步过为纪元时间',
    `status`           TINYINT UNSIGNED NOT NULL DEFAULT 1                 COMMENT '状态:1 启用 0 停用',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_server_tool` (`server_url`, `tool_name`, `deleted`) COMMENT '同一 Server 下工具名唯一,配合 deleted 支持删除后重建同名',
    KEY `idx_tool_status` (`status`, `deleted`, `id`) COMMENT '前端下拉只列启用中的工具'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='MCP 工具接入:Server 连接信息与已发现工具清单(按工具粒度存一行)';
