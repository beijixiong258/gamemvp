-- 将现有13表数据库的阅读结构和内置书籍开关更新到当前规则，可重复执行。
-- 阅读进度统一为100，基础阅读速度由GameRuleConstant统一维护。
-- 按实际结构删除旧字段、补齐新字段和约束；MySQL DDL会隐式提交。
-- 只补正10本内置书籍的以身入局开关，不覆盖其他定义、背包或阅读进度。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `mvp`;

DROP PROCEDURE IF EXISTS `mvp_upgrade_reading`;
DELIMITER $$
CREATE PROCEDURE `mvp_upgrade_reading`()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_schema = DATABASE() AND table_name = 'book_definition'
                 AND constraint_name = 'chk_book_definition_difficulty') THEN
        ALTER TABLE `book_definition` DROP CHECK `chk_book_definition_difficulty`;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints
               WHERE constraint_schema = DATABASE() AND table_name = 'book_definition'
                 AND constraint_name = 'chk_book_definition_progress') THEN
        ALTER TABLE `book_definition` DROP CHECK `chk_book_definition_progress`;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'book_definition' AND column_name = 'difficulty') THEN
        ALTER TABLE `book_definition` DROP COLUMN `difficulty`;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'book_definition' AND column_name = 'required_progress') THEN
        ALTER TABLE `book_definition` DROP COLUMN `required_progress`;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'book_definition' AND column_name = 'base_progress_per_turn') THEN
        ALTER TABLE `book_definition` DROP COLUMN `base_progress_per_turn`;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE() AND table_name = 'book_definition'
                     AND constraint_name = 'chk_book_definition_knowledge') THEN
        ALTER TABLE `book_definition` ADD CONSTRAINT `chk_book_definition_knowledge` CHECK (`total_knowledge` >= 0);
    END IF;

    ALTER TABLE `equipment_definition`
        MODIFY COLUMN `rarity_code` VARCHAR(32) NOT NULL
            COMMENT '装备品质：COMMON白、UNCOMMON绿、RARE蓝、EPIC紫、LEGENDARY金';
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE() AND table_name = 'equipment_definition'
                     AND constraint_name = 'chk_equipment_definition_rarity') THEN
        ALTER TABLE `equipment_definition` ADD CONSTRAINT `chk_equipment_definition_rarity`
            CHECK (`rarity_code` IN ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'book_definition'
                     AND column_name = 'player_reading_enabled') THEN
        ALTER TABLE `book_definition`
            ADD COLUMN `player_reading_enabled` TINYINT(1) NOT NULL DEFAULT 0
                COMMENT '是否允许以身入局读书，仅科举类书籍开启' AFTER `reading_requirement_json`;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE() AND table_name = 'book_definition'
                     AND constraint_name = 'chk_book_definition_player_reading') THEN
        ALTER TABLE `book_definition` ADD CONSTRAINT `chk_book_definition_player_reading`
            CHECK (`player_reading_enabled` IN (0, 1));
    END IF;
END$$
DELIMITER ;

CALL `mvp_upgrade_reading`();
DROP PROCEDURE `mvp_upgrade_reading`;

-- 结构已是当前版本时也必须补值；补内容接口只插入缺失定义，不修正已有开关。
UPDATE `book_definition` b
JOIN `equipment_definition` e ON e.`id` = b.`equipment_id`
SET b.`player_reading_enabled` = IF(e.`equipment_code` = 'BOOK_GPT1_WEIGHTS', 0, 1)
WHERE e.`equipment_code` IN (
    'BOOK_SANZIJING', 'BOOK_QIANZIWEN', 'BOOK_LUNYU', 'BOOK_MENGZI', 'BOOK_DAXUE',
    'BOOK_SHIJING', 'BOOK_MINGJIAWENCHAO', 'BOOK_JINGSHI_CELUN', 'BOOK_BAGU_POTI', 'BOOK_GPT1_WEIGHTS'
);

SELECT e.`equipment_code`, b.`player_reading_enabled`
FROM `book_definition` b JOIN `equipment_definition` e ON e.`id` = b.`equipment_id`
ORDER BY e.`equipment_code`;
