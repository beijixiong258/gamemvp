-- 古代穿越人生模拟游戏 MVP 数据库结构
-- 适用版本：MySQL 8.0+
-- 本脚本只负责建库、建表和约束，不写入业务初始数据。

CREATE DATABASE IF NOT EXISTS `mvp`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `mvp`;

CREATE TABLE IF NOT EXISTS `game_save`
(
    `id`                CHAR(32)    NOT NULL COMMENT '存档ID',
    `status`            VARCHAR(32) NOT NULL COMMENT '存档当前流程状态',
    `birth_year`        INT         NOT NULL COMMENT '角色出生年份',
    `current_year`      INT         NOT NULL COMMENT '当前游戏年份',
    `current_month`     TINYINT     NOT NULL COMMENT '当前游戏月份',
    `turn_in_month`     TINYINT     NOT NULL COMMENT '当前月份内的回合序号',
    `age`               INT         NOT NULL COMMENT '角色当前年龄',
    `total_turn_number` BIGINT      NOT NULL COMMENT '正常游戏阶段累计总回合编号',
    `growth_stage`      VARCHAR(32) NOT NULL COMMENT '角色当前成长阶段',
    `random_seed`       BIGINT      NOT NULL COMMENT '存档固定随机种子',
    PRIMARY KEY (`id`),
    CONSTRAINT `chk_game_save_month`
        CHECK (`current_month` BETWEEN 1 AND 12),
    CONSTRAINT `chk_game_save_turn_in_month`
        CHECK (`turn_in_month` BETWEEN 0 AND 4),
    CONSTRAINT `chk_game_save_age`
        CHECK (`age` >= 0),
    CONSTRAINT `chk_game_save_total_turn`
        CHECK (`total_turn_number` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '游戏存档';

CREATE TABLE IF NOT EXISTS `game_character`
(
    `id`                        CHAR(32)     NOT NULL COMMENT '人物ID',
    `save_id`                   CHAR(32)     NOT NULL COMMENT '所属存档ID',
    `name`                      VARCHAR(64)  NOT NULL COMMENT '人物姓名',
    `type`                      TINYINT      NOT NULL COMMENT '控制类型：1为人类玩家，0为NPC',
    `character_zhili`           INT          NOT NULL COMMENT '智力',
    `character_daode`           INT          NOT NULL COMMENT '道德',
    `character_zhengzhi`        INT          NOT NULL COMMENT '政治',
    `character_jiaoji`          INT          NOT NULL COMMENT '交际',
    `character_tineng`          INT          NOT NULL COMMENT '体能',
    `character_jiankang`        INT          NOT NULL COMMENT '当前健康值',
    `character_pilao`           INT          NOT NULL COMMENT '当前疲劳值',
    `current_main_career_code`  VARCHAR(64)  NOT NULL COMMENT '当前主职业编码',
    `birthday`                  VARCHAR(32)  NOT NULL COMMENT '游戏纪年中的生日',
    `personality_summary`       TEXT         NULL COMMENT '性格与行为倾向摘要',
    `current_state`             TEXT         NULL COMMENT '当前状态摘要',
    `available_stage_code_json` JSON         NOT NULL COMMENT '可参与的成长阶段编码JSON数组',
    `enabled`                   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_game_character_id_save` (`id`, `save_id`),
    KEY `idx_game_character_save_type` (`save_id`, `type`),
    KEY `idx_game_character_save_enabled` (`save_id`, `enabled`),
    CONSTRAINT `fk_game_character_save`
        FOREIGN KEY (`save_id`) REFERENCES `game_save` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_game_character_type`
        CHECK (`type` IN (0, 1)),
    CONSTRAINT `chk_game_character_health`
        CHECK (`character_jiankang` >= 0),
    CONSTRAINT `chk_game_character_fatigue`
        CHECK (`character_pilao` >= 0),
    CONSTRAINT `chk_game_character_enabled`
        CHECK (`enabled` IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '游戏人物';

CREATE TABLE IF NOT EXISTS `character_career`
(
    `id`                      CHAR(32)    NOT NULL COMMENT '职业档案ID',
    `character_id`            CHAR(32)    NOT NULL COMMENT '所属人物ID',
    `career_code`             VARCHAR(64) NOT NULL COMMENT '职业编码',
    `unlock_turn_number`      BIGINT      NOT NULL COMMENT '进入职业线时的总回合编号',
    `last_active_turn_number` BIGINT      NULL COMMENT '最后一次以该职业活动时的总回合编号',
    `status`                  VARCHAR(32) NOT NULL COMMENT '职业档案状态',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_character_career_character_code` (`character_id`, `career_code`),
    KEY `idx_character_career_character_status` (`character_id`, `status`),
    CONSTRAINT `fk_character_career_character`
        FOREIGN KEY (`character_id`) REFERENCES `game_character` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_character_career_unlock_turn`
        CHECK (`unlock_turn_number` >= 0),
    CONSTRAINT `chk_character_career_last_active_turn`
        CHECK (`last_active_turn_number` IS NULL OR `last_active_turn_number` >= `unlock_turn_number`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人物职业记录';

CREATE TABLE IF NOT EXISTS `career_shusheng_profile`
(
    `career_profile_id` CHAR(32) NOT NULL COMMENT '父职业档案ID，同时作为主键',
    `ability_shizi`     INT      NOT NULL DEFAULT 0 COMMENT '识字能力',
    `ability_jingyi`    INT      NOT NULL DEFAULT 0 COMMENT '经义能力',
    `ability_wenzhang`  INT      NOT NULL DEFAULT 0 COMMENT '文章能力',
    `ability_celun`     INT      NOT NULL DEFAULT 0 COMMENT '策论能力',
    `ability_wenxue`    INT      NOT NULL DEFAULT 0 COMMENT '文学能力',
    PRIMARY KEY (`career_profile_id`),
    CONSTRAINT `fk_career_shusheng_profile_career`
        FOREIGN KEY (`career_profile_id`) REFERENCES `character_career` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_career_shusheng_profile_abilities`
        CHECK (`ability_shizi` >= 0
            AND `ability_jingyi` >= 0
            AND `ability_wenzhang` >= 0
            AND `ability_celun` >= 0
            AND `ability_wenxue` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '书生职业线能力';

CREATE TABLE IF NOT EXISTS `family_state`
(
    `id`                 CHAR(32) NOT NULL COMMENT '家庭状态记录ID',
    `save_id`            CHAR(32) NOT NULL COMMENT '所属存档ID',
    `wealth`             INT      NOT NULL COMMENT '家庭可用财富，单位为文',
    `background_summary` TEXT     NOT NULL COMMENT '家庭背景摘要',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_family_state_save` (`save_id`),
    CONSTRAINT `fk_family_state_save`
        FOREIGN KEY (`save_id`) REFERENCES `game_save` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_family_state_wealth`
        CHECK (`wealth` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '家庭状态';

CREATE TABLE IF NOT EXISTS `equipment_definition`
(
    `id`             CHAR(32)     NOT NULL COMMENT '装备定义ID',
    `equipment_code` VARCHAR(64)  NOT NULL COMMENT '稳定装备编码',
    `equipment_name` VARCHAR(128) NOT NULL COMMENT '装备显示名称',
    `equipment_type` VARCHAR(32)  NOT NULL COMMENT '装备类型编码',
    `rarity_code`    VARCHAR(32)  NOT NULL COMMENT '稀有度编码',
    `price`          INT          NOT NULL COMMENT '价格，单位为文',
    `description`    TEXT         NOT NULL COMMENT '基础介绍',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_equipment_definition_code` (`equipment_code`),
    KEY `idx_equipment_definition_type_rarity` (`equipment_type`, `rarity_code`),
    CONSTRAINT `chk_equipment_definition_price`
        CHECK (`price` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '通用装备定义';

CREATE TABLE IF NOT EXISTS `book_definition`
(
    `equipment_id`                  CHAR(32)       NOT NULL COMMENT '父装备定义ID，同时作为主键',
    `applicable_career_code`        VARCHAR(64)    NOT NULL COMMENT '适用职业编码',
    `reading_requirement_json`      JSON           NOT NULL COMMENT '固定结构的阅读条件',
    `difficulty`                    INT            NOT NULL COMMENT '阅读难度',
    `required_progress`             DECIMAL(10, 4) NOT NULL COMMENT '完成阅读所需总进度',
    `base_progress_per_turn`        DECIMAL(10, 4) NOT NULL COMMENT '单回合基础阅读进度',
    `ability_shizi_weight`          TINYINT        NOT NULL COMMENT '识字收益权重百分比',
    `ability_jingyi_weight`         TINYINT        NOT NULL COMMENT '经义收益权重百分比',
    `ability_wenzhang_weight`       TINYINT        NOT NULL COMMENT '文章收益权重百分比',
    `ability_celun_weight`          TINYINT        NOT NULL COMMENT '策论收益权重百分比',
    `ability_wenxue_weight`         TINYINT        NOT NULL COMMENT '文学收益权重百分比',
    `fatigue_cost`                  INT            NOT NULL COMMENT '阅读一回合的基础疲劳值',
    `knowledge_chushi_summary`      TEXT           NOT NULL COMMENT '初识阶段知识摘要',
    `knowledge_keyong_summary`      TEXT           NOT NULL COMMENT '可用阶段知识摘要',
    `knowledge_zhangwo_summary`     TEXT           NOT NULL COMMENT '掌握阶段知识摘要',
    PRIMARY KEY (`equipment_id`),
    CONSTRAINT `fk_book_definition_equipment`
        FOREIGN KEY (`equipment_id`) REFERENCES `equipment_definition` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_book_definition_difficulty`
        CHECK (`difficulty` >= 0),
    CONSTRAINT `chk_book_definition_progress`
        CHECK (`required_progress` > 0 AND `base_progress_per_turn` > 0),
    CONSTRAINT `chk_book_definition_weights`
        CHECK (`ability_shizi_weight` BETWEEN 0 AND 100
            AND `ability_jingyi_weight` BETWEEN 0 AND 100
            AND `ability_wenzhang_weight` BETWEEN 0 AND 100
            AND `ability_celun_weight` BETWEEN 0 AND 100
            AND `ability_wenxue_weight` BETWEEN 0 AND 100
            AND `ability_shizi_weight`
              + `ability_jingyi_weight`
              + `ability_wenzhang_weight`
              + `ability_celun_weight`
              + `ability_wenxue_weight` = 100),
    CONSTRAINT `chk_book_definition_fatigue`
        CHECK (`fatigue_cost` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '书籍定义扩展';

CREATE TABLE IF NOT EXISTS `character_equipment`
(
    `id`                     CHAR(32)    NOT NULL COMMENT '人物装备记录ID',
    `save_id`                CHAR(32)    NOT NULL COMMENT '所属存档ID',
    `character_id`           CHAR(32)    NOT NULL COMMENT '持有或获准使用装备的人物ID',
    `equipment_id`           CHAR(32)    NOT NULL COMMENT '装备定义ID',
    `ai_text`                TEXT        NULL COMMENT 'AI生成的装备来源文字',
    `acquired_turn_number`   BIGINT      NOT NULL COMMENT '取得装备或使用权时的总回合编号',
    `expiration_turn_number` BIGINT      NULL COMMENT '临时使用权到期回合，永久持有时为空',
    `status`                 VARCHAR(32) NOT NULL COMMENT '装备记录当前状态',
    PRIMARY KEY (`id`),
    KEY `idx_character_equipment_save` (`save_id`),
    KEY `idx_character_equipment_character_save` (`character_id`, `save_id`),
    KEY `idx_character_equipment_effective`
        (`character_id`, `equipment_id`, `status`, `expiration_turn_number`),
    KEY `idx_character_equipment_definition` (`equipment_id`),
    CONSTRAINT `fk_character_equipment_character_save`
        FOREIGN KEY (`character_id`, `save_id`) REFERENCES `game_character` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_character_equipment_definition`
        FOREIGN KEY (`equipment_id`) REFERENCES `equipment_definition` (`id`)
            ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_character_equipment_acquired_turn`
        CHECK (`acquired_turn_number` >= 0),
    CONSTRAINT `chk_character_equipment_expiration_turn`
        CHECK (`expiration_turn_number` IS NULL OR `expiration_turn_number` >= `acquired_turn_number`)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人物装备持有与使用权记录';

CREATE TABLE IF NOT EXISTS `character_book_progress`
(
    `id`                     CHAR(32)       NOT NULL COMMENT '读书进度记录ID',
    `character_id`           CHAR(32)       NOT NULL COMMENT '人物ID',
    `equipment_id`           CHAR(32)       NOT NULL COMMENT '书籍装备定义ID',
    `current_progress`       DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '当前阅读进度',
    `total_read_turn_number` INT            NOT NULL DEFAULT 0 COMMENT '累计阅读回合数',
    `completed`              TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否读完',
    `last_read_turn_number`  BIGINT         NULL COMMENT '最后阅读时的总回合编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_character_book_progress_character_book` (`character_id`, `equipment_id`),
    KEY `idx_character_book_progress_book` (`equipment_id`),
    CONSTRAINT `fk_character_book_progress_character`
        FOREIGN KEY (`character_id`) REFERENCES `game_character` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_character_book_progress_book`
        FOREIGN KEY (`equipment_id`) REFERENCES `book_definition` (`equipment_id`)
            ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT `chk_character_book_progress_value`
        CHECK (`current_progress` >= 0),
    CONSTRAINT `chk_character_book_progress_turns`
        CHECK (`total_read_turn_number` >= 0
            AND (`last_read_turn_number` IS NULL OR `last_read_turn_number` >= 0)),
    CONSTRAINT `chk_character_book_progress_completed`
        CHECK (`completed` IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人物逐本阅读进度';

CREATE TABLE IF NOT EXISTS `event_record`
(
    `id`                        CHAR(32)   NOT NULL COMMENT '事件记录ID',
    `save_id`                   CHAR(32)   NOT NULL COMMENT '所属存档ID',
    `event_code`                VARCHAR(64) NOT NULL COMMENT '事件编码',
    `event_summary`             TEXT       NOT NULL COMMENT '人生节点或世界事件摘要',
    `related_character_id_json` JSON       NOT NULL COMMENT '相关人物ID组成的JSON数组',
    `occurred_turn_number`      BIGINT     NOT NULL COMMENT '事件发生时的总回合编号',
    `settlement_result_json`    JSON       NOT NULL COMMENT '已校验并执行的结算结果',
    `life_milestone`            TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为人生节点',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_record_id_save` (`id`, `save_id`),
    KEY `idx_event_record_save_turn` (`save_id`, `occurred_turn_number`),
    KEY `idx_event_record_save_code` (`save_id`, `event_code`),
    CONSTRAINT `fk_event_record_save`
        FOREIGN KEY (`save_id`) REFERENCES `game_save` (`id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_event_record_turn`
        CHECK (`occurred_turn_number` >= 0),
    CONSTRAINT `chk_event_record_life_milestone`
        CHECK (`life_milestone` IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '规则确认成立的重大事件';

CREATE TABLE IF NOT EXISTS `memory_record`
(
    `id`                        CHAR(32) NOT NULL COMMENT '记忆记录ID',
    `save_id`                   CHAR(32) NOT NULL COMMENT '所属存档ID',
    `owner_character_id`        CHAR(32) NOT NULL COMMENT '记忆拥有者人物ID',
    `source_event_id`           CHAR(32) NOT NULL COMMENT '来源事件ID',
    `ai_memory_summary`         TEXT     NOT NULL COMMENT 'AI生成的记忆摘要',
    `related_character_id_json` JSON     NOT NULL COMMENT '相关人物ID组成的JSON数组',
    `occurred_turn_number`      BIGINT   NOT NULL COMMENT '对应事件发生时的总回合编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_record_owner_event` (`owner_character_id`, `source_event_id`),
    KEY `idx_memory_record_save_turn` (`save_id`, `occurred_turn_number`),
    KEY `idx_memory_record_owner_save` (`owner_character_id`, `save_id`),
    KEY `idx_memory_record_event_save` (`source_event_id`, `save_id`),
    CONSTRAINT `fk_memory_record_owner_save`
        FOREIGN KEY (`owner_character_id`, `save_id`) REFERENCES `game_character` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fk_memory_record_event_save`
        FOREIGN KEY (`source_event_id`, `save_id`) REFERENCES `event_record` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_memory_record_turn`
        CHECK (`occurred_turn_number` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人物长期记忆';

CREATE TABLE IF NOT EXISTS `exam_record`
(
    `id`                         CHAR(32)       NOT NULL COMMENT '考试记录ID',
    `save_id`                    CHAR(32)       NOT NULL COMMENT '所属存档ID',
    `character_id`               CHAR(32)       NOT NULL COMMENT '参加考试的人物ID',
    `request_id`                 VARCHAR(64)    NULL COMMENT '最终结算请求唯一ID',
    `exam_type`                  VARCHAR(64)    NOT NULL COMMENT '考试类型编码',
    `question_text`              TEXT           NOT NULL COMMENT '本次考试题目原文',
    `pass_threshold`             DECIMAL(10, 4) NOT NULL COMMENT '通过分数线',
    `base_ability_score`         DECIMAL(10, 4) NOT NULL COMMENT '基础能力分B',
    `random_offset`              DECIMAL(10, 4) NOT NULL COMMENT '固定综合随机偏移R',
    `ai_thought_bubble`          TEXT           NOT NULL COMMENT 'AI生成的思维泡泡',
    `player_choice`              VARCHAR(32)    NULL COMMENT '系统代行或以身入局',
    `player_input`               TEXT           NULL COMMENT '玩家亲自输入的答案原文',
    `ai_player_content_modifier` DECIMAL(10, 4) NULL COMMENT 'AI评价玩家答案产生的内容修正M',
    `final_score`                DECIMAL(10, 4) NULL COMMENT '最终分数',
    `status`                     VARCHAR(32)    NOT NULL COMMENT '考试当前状态或最终结果',
    `ai_answer_text`             TEXT           NULL COMMENT '系统代行生成的展示答卷',
    `ai_content`                 TEXT           NULL COMMENT 'AI生成的评价与结果叙事',
    `turn_number`                BIGINT         NOT NULL COMMENT '考试发生时的总回合编号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_record_request` (`request_id`),
    UNIQUE KEY `uk_exam_record_character_type` (`character_id`, `exam_type`),
    KEY `idx_exam_record_save` (`save_id`),
    KEY `idx_exam_record_character_save` (`character_id`, `save_id`),
    KEY `idx_exam_record_save_status` (`save_id`, `status`),
    CONSTRAINT `fk_exam_record_character_save`
        FOREIGN KEY (`character_id`, `save_id`) REFERENCES `game_character` (`id`, `save_id`)
            ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `chk_exam_record_turn`
        CHECK (`turn_number` >= 0)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '考试创建与结算记录';
