-- 已有 mvp 数据库接入主要行动与 AI 成长额度。重复执行安全，不清除存档。
-- 新建数据库直接使用 schema.sql；已有数据库在启动新版后端前执行本文件。
SET NAMES utf8mb4;
USE `mvp`;

SET @action_column_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'game_character' AND column_name = 'major_action_turn'),
    'SELECT 1',
    'ALTER TABLE game_character ADD COLUMN major_action_turn BIGINT NULL COMMENT ''最近使用主要行动额度的累计回合'' AFTER character_pilao');
PREPARE action_column_statement FROM @action_column_sql;
EXECUTE action_column_statement;
DEALLOCATE PREPARE action_column_statement;

SET @growth_column_sql = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'game_character' AND column_name = 'ai_growth_turn'),
    'SELECT 1',
    'ALTER TABLE game_character ADD COLUMN ai_growth_turn BIGINT NULL COMMENT ''最近领取AI正向成长的累计回合'' AFTER major_action_turn');
PREPARE growth_column_statement FROM @growth_column_sql;
EXECUTE growth_column_statement;
DEALLOCATE PREPARE growth_column_statement;

ALTER TABLE `game_character`
    MODIFY COLUMN `character_pilao` INT NOT NULL COMMENT '已消耗体力，当前体力为动态上限减去此值';
