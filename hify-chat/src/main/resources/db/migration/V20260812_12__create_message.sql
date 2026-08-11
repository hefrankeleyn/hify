-- 对话消息(hify-chat)。
-- 依据: docs/02-架构设计/02-决策/06_Hify数据模型（基准）.md、CLAUDE.md 5.1/5.2/5.4/5.6
--
-- ⚠️ 全库唯一的 L2 大表(千万级/年),建表时就要把索引建对、主键用 BIGINT、
--    归档维度字段就位(CLAUDE.md 5.6 第三条)——本表以 conversation_id 作为归档维度,
--    与 message_reference 按同一个 conversation 一起归档(该表随后续批次补齐)。
-- 查询形态全项目唯一一种,必须游标分页,禁止 COUNT(*)/OFFSET 深分页(CLAUDE.md 5.5):
--   SELECT * FROM message WHERE conversation_id=? AND deleted=0 AND id<? ORDER BY id DESC LIMIT 20
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `message` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `conversation_id` BIGINT UNSIGNED NOT NULL               COMMENT '所属会话 id(逻辑引用 conversation,不建外键)',
    `role`            VARCHAR(16)     NOT NULL               COMMENT '角色:USER/ASSISTANT/SYSTEM/TOOL',
    `content`         TEXT            NOT NULL               COMMENT '消息内容',
    `token_count`     INT UNSIGNED    NOT NULL DEFAULT 0     COMMENT '本条消息的 token 用量',
    `tool_calls`      JSON            NOT NULL DEFAULT ('[]') COMMENT '工具调用记录,一期不单独建表(见 09 号过程稿 2.3)',

    PRIMARY KEY (`id`),
    KEY `idx_message_conversation` (`conversation_id`, `deleted`, `id`) COMMENT '按会话游标分页,全表唯一查询形态'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='对话消息(L2 大表,归档维度为 conversation_id)';
