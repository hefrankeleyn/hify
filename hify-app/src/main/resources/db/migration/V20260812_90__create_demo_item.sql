-- ⚠️ DEMO 演示表,用来验证 BaseEntity/PageHelper/Result·PageResult/GlobalExceptionHandler/
--    Jackson 时间序列化/Spring Cache 这条全链路是否打通,不是真实业务表。
--    真实业务模块的 CRUD 落地后,应删除本表以及对应的 Entity/Mapper/Service/Controller/DTO。
-- 版本号刻意跳出 01~15 的正式建表批次(见 docs/04-后端组件/02-决策/01_...md),
-- 避免和后续 8 张正式表(03/04/07/09/10/13/14/15)的编号混淆。
-- 🔴 本脚本发布后不可修改,后续变更另开新脚本(CLAUDE.md 5.8)

CREATE TABLE `demo_item` (
    `id`         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT               COMMENT '主键 id',
    `created_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updated_at` DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    `deleted`    BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '逻辑删除:0 未删,已删存删除时刻毫秒时间戳',
    `creator_id` BIGINT UNSIGNED  NOT NULL DEFAULT 0                    COMMENT '创建人 id',

    `name`   VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '名称',
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 1  COMMENT '状态:1 启用 0 停用',

    PRIMARY KEY (`id`),
    KEY `idx_demo_item_status` (`status`, `deleted`, `id`) COMMENT '按状态过滤的列表查询'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='DEMO 演示表,验证全链路用,非真实业务表';
