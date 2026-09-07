-- 将首版地区的32位补零ID迁移为字符串1至8；也可校正已使用短ID的地区名称。
-- 适用MySQL 8.0+及当前MVP表结构。执行期间暂停游戏写入，不与其他事务混用。
-- 保留存档和业务记录；不关闭外键，不保留旧ID别名。重复执行不会重复创建地区。
-- 创建/删除下列任务专用例程属于DDL；地区、人物及事件回执的修改在一个DML事务内。
-- 使用支持DELIMITER的SQL客户端执行。若客户端因错误中止，仍需执行末尾两条DROP清理例程。

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `mvp`;

DROP PROCEDURE IF EXISTS `mvp_upgrade_region_v1`;
DROP FUNCTION IF EXISTS `mvp_upgrade_region_json_v1`;

DELIMITER $$

CREATE FUNCTION `mvp_upgrade_region_json_v1`(source_document JSON)
RETURNS JSON
DETERMINISTIC
NO SQL
BEGIN
    DECLARE document_value JSON;
    DECLARE pending_paths JSON;
    DECLARE serialized_values JSON;
    DECLARE node_value JSON;
    DECLARE node_keys JSON;
    DECLARE child_value JSON;
    DECLARE node_path LONGTEXT;
    DECLARE child_path LONGTEXT;
    DECLARE member_name LONGTEXT;
    DECLARE string_value LONGTEXT;
    DECLARE mapped_id VARCHAR(32);
    DECLARE pending_index INT;
    DECLARE member_index INT;
    DECLARE member_count INT;
    DECLARE document_changed BOOLEAN DEFAULT FALSE;

    IF source_document IS NULL THEN
        RETURN NULL;
    END IF;
    SET document_value = source_document;
    SET pending_paths = JSON_ARRAY('$');
    SET serialized_values = JSON_ARRAY();

    -- 显式栈遍历对象和数组；普通字符串（包括玩家原文）不解析、不替换。
    WHILE JSON_LENGTH(pending_paths) > 0 DO
        SET pending_index = JSON_LENGTH(pending_paths) - 1;
        SET node_path = JSON_UNQUOTE(JSON_EXTRACT(pending_paths, CONCAT('$[', pending_index, ']')));
        SET pending_paths = JSON_REMOVE(pending_paths, CONCAT('$[', pending_index, ']'));
        SET node_value = JSON_EXTRACT(document_value, node_path);

        IF JSON_TYPE(node_value) = 'OBJECT' THEN
            SET node_keys = JSON_KEYS(node_value);
            SET member_index = 0;
            SET member_count = JSON_LENGTH(node_keys);
            WHILE member_index < member_count DO
                SET member_name = JSON_UNQUOTE(JSON_EXTRACT(node_keys, CONCAT('$[', member_index, ']')));
                SET child_path = CONCAT(node_path, '.', JSON_QUOTE(member_name));
                SET child_value = JSON_EXTRACT(document_value, child_path);

                IF JSON_TYPE(child_value) = 'STRING' THEN
                    SET string_value = JSON_UNQUOTE(child_value);
                    IF BINARY member_name IN ('birthRegionId', 'currentRegionId',
                                              'birth_region_id', 'current_region_id') THEN
                        -- 仅识别这8个旧地区值，不把任意数字字符串或其他实体ID去零。
                        SET mapped_id = CASE BINARY string_value
                            WHEN '00000000000000000000000000000001' THEN '1'
                            WHEN '00000000000000000000000000000002' THEN '2'
                            WHEN '00000000000000000000000000000003' THEN '3'
                            WHEN '00000000000000000000000000000004' THEN '4'
                            WHEN '00000000000000000000000000000005' THEN '5'
                            WHEN '00000000000000000000000000000006' THEN '6'
                            WHEN '00000000000000000000000000000007' THEN '7'
                            WHEN '00000000000000000000000000000008' THEN '8'
                            ELSE NULL
                        END;
                        IF mapped_id IS NOT NULL THEN
                            SET document_value = JSON_SET(document_value, child_path, mapped_id);
                            SET document_changed = TRUE;
                        END IF;
                    ELSEIF BINARY member_name IN ('settlementResultJson', 'settlement_result_json')
                            AND JSON_VALID(string_value) THEN
                        -- detail.milestones中的结算回执以字符串保存，可继续嵌套。
                        SET child_value = CAST(string_value AS JSON);
                        IF JSON_TYPE(child_value) IN ('OBJECT', 'ARRAY') THEN
                            SET serialized_values = JSON_ARRAY_APPEND(serialized_values, '$',
                                JSON_OBJECT('path', child_path, 'original', string_value));
                            SET document_value = JSON_SET(document_value, child_path, child_value);
                        END IF;
                    END IF;
                END IF;

                IF JSON_TYPE(child_value) IN ('OBJECT', 'ARRAY') THEN
                    SET pending_paths = JSON_ARRAY_APPEND(pending_paths, '$', child_path);
                END IF;
                SET member_index = member_index + 1;
            END WHILE;
        ELSEIF JSON_TYPE(node_value) = 'ARRAY' THEN
            SET member_index = 0;
            SET member_count = JSON_LENGTH(node_value);
            WHILE member_index < member_count DO
                SET pending_paths = JSON_ARRAY_APPEND(pending_paths, '$',
                    CONCAT(node_path, '[', member_index, ']'));
                SET member_index = member_index + 1;
            END WHILE;
        END IF;
    END WHILE;

    IF NOT document_changed THEN
        RETURN source_document;
    END IF;

    -- 子回执先恢复为字符串，再恢复父回执；未发生变化的回执保留原字符串。
    SET pending_index = JSON_LENGTH(serialized_values) - 1;
    WHILE pending_index >= 0 DO
        SET node_path = JSON_UNQUOTE(JSON_EXTRACT(serialized_values,
            CONCAT('$[', pending_index, '].path')));
        SET string_value = JSON_UNQUOTE(JSON_EXTRACT(serialized_values,
            CONCAT('$[', pending_index, '].original')));
        SET node_value = JSON_EXTRACT(document_value, node_path);
        IF node_value <> CAST(string_value AS JSON) THEN
            SET string_value = CAST(node_value AS CHAR CHARACTER SET utf8mb4);
        END IF;
        SET document_value = JSON_SET(document_value, node_path, string_value);
        SET pending_index = pending_index - 1;
    END WHILE;
    RETURN document_value;
END$$

CREATE PROCEDURE `mvp_upgrade_region_v1`()
BEGIN
    DECLARE region_mapping JSON;
    DECLARE conflict_count INT DEFAULT 0;
    DECLARE current_level INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET region_mapping = JSON_ARRAY(
        JSON_OBJECT('id', '1', 'name', '广东省', 'level', 0),
        JSON_OBJECT('id', '2', 'parentId', '1', 'name', '广州', 'level', 1),
        JSON_OBJECT('id', '3', 'parentId', '1', 'name', '惠州', 'level', 1),
        JSON_OBJECT('id', '4', 'parentId', '2', 'name', '番禺县', 'level', 2),
        JSON_OBJECT('id', '5', 'parentId', '2', 'name', '南海县', 'level', 2),
        JSON_OBJECT('id', '6', 'parentId', '2', 'name', '顺德县', 'level', 2),
        JSON_OBJECT('id', '7', 'parentId', '3', 'name', '博罗县', 'level', 2),
        JSON_OBJECT('id', '8', 'parentId', '3', 'name', '海丰县', 'level', 2)
    );

    START TRANSACTION;
    -- 锁定地区后检查占用情况，避免覆盖与此次迁移无关的地区。
    SELECT id AS locked_region_id FROM region ORDER BY id FOR UPDATE;

    SELECT COUNT(*) INTO conflict_count
    FROM JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id'
    )) AS mapping
    LEFT JOIN region AS existing
        ON existing.id IN (mapping.new_id, LPAD(mapping.new_id, 32, '0'))
    WHERE existing.id IS NULL;
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '地区迁移中止：首版8个地区不完整，请先核对数据';
    END IF;

    SELECT COUNT(*) INTO conflict_count
    FROM region AS existing
    JOIN JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id',
        parent_id VARCHAR(32) PATH '$.parentId' NULL ON EMPTY,
        region_name VARCHAR(64) PATH '$.name'
    )) AS mapping
        ON existing.id IN (mapping.new_id, LPAD(mapping.new_id, 32, '0'))
    WHERE NOT ((existing.parent_id <=> mapping.parent_id)
        OR (existing.parent_id <=> LPAD(mapping.parent_id, 32, '0')))
       OR NOT (existing.region_name = mapping.region_name
           OR (mapping.new_id = '2' AND existing.region_name = '广州府')
           OR (mapping.new_id = '3' AND existing.region_name IN ('惠州府', '府')));
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '地区迁移中止：目标ID的名称或父级与首版地区不符';
    END IF;

    -- 父级先于子级建立；已有地区保留enabled，名称与父级统一到当前schema。
    WHILE current_level <= 2 DO
        INSERT INTO region (id, parent_id, region_name, enabled)
        SELECT mapping.new_id, mapping.parent_id, mapping.region_name,
               COALESCE(current_region.enabled, old_region.enabled, 1)
        FROM JSON_TABLE(region_mapping, '$[*]' COLUMNS (
            new_id VARCHAR(32) PATH '$.id',
            parent_id VARCHAR(32) PATH '$.parentId' NULL ON EMPTY,
            region_name VARCHAR(64) PATH '$.name',
            region_level INT PATH '$.level'
        )) AS mapping
        LEFT JOIN region AS current_region ON current_region.id = mapping.new_id
        LEFT JOIN region AS old_region ON old_region.id = LPAD(mapping.new_id, 32, '0')
        WHERE mapping.region_level = current_level
        ON DUPLICATE KEY UPDATE
            parent_id = VALUES(parent_id),
            region_name = VALUES(region_name);
        SET current_level = current_level + 1;
    END WHILE;

    UPDATE region AS child
    JOIN JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id'
    )) AS mapping ON child.parent_id = LPAD(mapping.new_id, 32, '0')
    SET child.parent_id = mapping.new_id;

    UPDATE game_character AS actor
    JOIN JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id'
    )) AS mapping ON actor.birth_region_id = LPAD(mapping.new_id, 32, '0')
    SET actor.birth_region_id = mapping.new_id;

    UPDATE game_character AS actor
    JOIN JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id'
    )) AS mapping ON actor.current_region_id = LPAD(mapping.new_id, 32, '0')
    SET actor.current_region_id = mapping.new_id;

    -- 请求回执包含人物及嵌套人生节点；原始参数仅按确定的地区字段处理。
    UPDATE event_record
    SET settlement_result_json = mvp_upgrade_region_json_v1(settlement_result_json),
        request_payload_json = mvp_upgrade_region_json_v1(request_payload_json);

    -- 父级引用已转到新节点，删除旧节点不需要关闭外键或删除业务记录。
    DELETE old_region
    FROM region AS old_region
    JOIN JSON_TABLE(region_mapping, '$[*]' COLUMNS (
        new_id VARCHAR(32) PATH '$.id'
    )) AS mapping ON old_region.id = LPAD(mapping.new_id, 32, '0');

    COMMIT;
END$$

DELIMITER ;

-- 可选的只读helper验收样例：在CALL之前单独执行以下SELECT。
-- 预期birthRegionId为字符串"7"；text和id仍为原32位文本；嵌套回执保持STRING类型，
-- 其内部currentRegionId变为字符串"8"；nested里的第二层回执内部出生地变为"7"。
-- SELECT mvp_upgrade_region_json_v1(JSON_OBJECT(
--     'birthRegionId', '00000000000000000000000000000007',
--     'text', '00000000000000000000000000000007',
--     'id', '00000000000000000000000000000007',
--     'settlementResultJson', CAST(JSON_OBJECT(
--         'currentRegionId', '00000000000000000000000000000008',
--         'nested', JSON_OBJECT('settlementResultJson', CAST(JSON_OBJECT(
--             'birthRegionId', '00000000000000000000000000000007'
--         ) AS CHAR CHARACTER SET utf8mb4))
--     ) AS CHAR CHARACTER SET utf8mb4)
-- )) AS migrated_json;
-- SELECT mvp_upgrade_region_json_v1(JSON_OBJECT(
--     'birthRegionId', '7', 'settlementResultJson', '{ "unchanged" : true }'
-- )) AS unchanged_json;

CALL `mvp_upgrade_region_v1`();

DROP PROCEDURE IF EXISTS `mvp_upgrade_region_v1`;
DROP FUNCTION IF EXISTS `mvp_upgrade_region_json_v1`;
