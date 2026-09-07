-- 从旧版12表结构升级到背包、学识、对话版本。仅对尚未升级的旧库执行一次。
-- 前提：书籍已有growth_domain_code，书生档案已使用id/character_id，不含旧character_career依赖。
-- 不适用于混有character_career、career_shusheng_profile或family_state的更早结构。
-- 不自动执行。MySQL DDL会隐式提交；请先确认当前字段与旧版schema一致。
-- 不删除存档、阅读进度、旧摘要或旧借阅记录；旧BORROWED记录不进入新背包。
SET NAMES utf8mb4;
USE `mvp`;

ALTER TABLE `game_save`
    ADD COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '旧存档使用升级时刻';

ALTER TABLE `game_character`
    ADD COLUMN `npc_code` VARCHAR(64) NULL,
    ADD COLUMN `wallet` INT NOT NULL DEFAULT 2000,
    ADD COLUMN `sick_turns_remaining` INT NOT NULL DEFAULT 0,
    ADD COLUMN `official_position` VARCHAR(128) NULL,
    ADD COLUMN `official_rank` VARCHAR(64) NULL,
    ADD COLUMN `degree` VARCHAR(64) NULL,
    ADD COLUMN `titles_json` JSON NULL,
    ADD UNIQUE KEY `uk_game_character_save_npc` (`save_id`, `npc_code`);
UPDATE `game_character` SET `titles_json` = JSON_ARRAY() WHERE `titles_json` IS NULL;
ALTER TABLE `game_character` MODIFY `titles_json` JSON NOT NULL;

ALTER TABLE `equipment_definition` ADD COLUMN `supplier_npc_code` VARCHAR(64) NULL;
UPDATE `equipment_definition`
SET `price` = 0, `supplier_npc_code` = 'NPC_XIANSHENG'
WHERE `equipment_code` IN ('BOOK_SANZIJING', 'BOOK_QIANZIWEN', 'BOOK_LUNYU',
    'BOOK_MENGZI', 'BOOK_DAXUE', 'BOOK_SHIJING', 'BOOK_MINGJIAWENCHAO');

ALTER TABLE `book_definition`
    ADD COLUMN `total_knowledge` INT NOT NULL DEFAULT 0,
    ADD COLUMN `knowledge_summary` TEXT NULL,
    MODIFY `knowledge_chushi_summary` TEXT NULL,
    MODIFY `knowledge_keyong_summary` TEXT NULL,
    MODIFY `knowledge_zhangwo_summary` TEXT NULL;
UPDATE `book_definition` b JOIN `equipment_definition` e ON e.id = b.equipment_id
SET b.total_knowledge = CASE e.equipment_code
    WHEN 'BOOK_SANZIJING' THEN 20 WHEN 'BOOK_QIANZIWEN' THEN 20 WHEN 'BOOK_LUNYU' THEN 40
    WHEN 'BOOK_MENGZI' THEN 60 WHEN 'BOOK_DAXUE' THEN 50 WHEN 'BOOK_SHIJING' THEN 45
    WHEN 'BOOK_MINGJIAWENCHAO' THEN 70 ELSE 0 END,
    b.knowledge_summary = COALESCE(b.knowledge_zhangwo_summary, '');
ALTER TABLE `book_definition` MODIFY `knowledge_summary` TEXT NOT NULL;

ALTER TABLE `character_equipment` ADD COLUMN `quantity` INT NOT NULL DEFAULT 1;
ALTER TABLE `event_record`
    ADD COLUMN `request_id` VARCHAR(128) NULL,
    ADD COLUMN `request_payload_json` JSON NULL,
    ADD UNIQUE KEY `uk_event_record_save_request` (`save_id`, `request_id`);
ALTER TABLE `exam_record`
    ADD COLUMN `dice_roll` INT NOT NULL DEFAULT 50,
    ADD COLUMN `luck_offset` INT NOT NULL DEFAULT 0,
    ADD COLUMN `knowledge_total` DECIMAL(16,4) NOT NULL DEFAULT 0;

-- 保留旧考试分数，旧READY考试采用中性骰点50，不重算B/R。
-- 表升级后对旧存档调用 POST /api/save/{saveId}/content 补齐NPC及新增教材定义。
CREATE TABLE IF NOT EXISTS `dialogue_record`
(
    `id`                  CHAR(32)     NOT NULL COMMENT '整场对话ID',
    `save_id`             CHAR(32)     NOT NULL,
    `actor_id`            CHAR(32)     NOT NULL COMMENT '发起者，玩家或NPC',
    `counterpart_id`      CHAR(32)     NOT NULL COMMENT '对话对象',
    `scene_code`          VARCHAR(64)  NOT NULL,
    `started_turn_number` BIGINT       NOT NULL,
    `version`             INT          NOT NULL DEFAULT 0 COMMENT '对话往返版本',
    `ended`               TINYINT(1)   NOT NULL DEFAULT 0,
    `messages_json`       JSON         NOT NULL COMMENT '对话原文与实际交易结果',
    PRIMARY KEY (`id`),
    KEY `idx_dialogue_save_actor` (`save_id`, `actor_id`),
    KEY `idx_dialogue_counterpart_save` (`counterpart_id`, `save_id`),
    KEY `idx_dialogue_actor_save` (`actor_id`, `save_id`),
    CONSTRAINT `fk_dialogue_actor_save`
        FOREIGN KEY (`actor_id`, `save_id`) REFERENCES `game_character` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_dialogue_counterpart_save`
        FOREIGN KEY (`counterpart_id`, `save_id`) REFERENCES `game_character` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '人物对话及一次性结束结算状态';

-- 地区树简化：保留原ID、父子关系、名称和启用状态，移除编码、等级和排序。
-- 已完成前述业务表升级、但仍保留旧region_definition的库，只执行以下段落一次。
-- region已经是四字段结构时不要重复执行；重命名会同步更新人物地区外键的目标表。
RENAME TABLE `mvp`.`region_definition` TO `mvp`.`region`;

ALTER TABLE `mvp`.`region`
    DROP FOREIGN KEY `fk_region_definition_parent`,
    DROP CHECK `chk_region_definition_level`,
    DROP CHECK `chk_region_definition_enabled`,
    DROP INDEX `uk_region_definition_code`,
    DROP INDEX `idx_region_definition_parent_sort`,
    DROP COLUMN `region_code`,
    DROP COLUMN `region_level`,
    DROP COLUMN `sort_order`,
    ADD KEY `idx_region_parent` (`parent_id`),
    ADD CONSTRAINT `fk_region_parent`
        FOREIGN KEY (`parent_id`) REFERENCES `mvp`.`region` (`id`)
            ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT `chk_region_enabled` CHECK (`enabled` IN (0, 1));
