# 古代穿越人生模拟游戏 MVP 数据模型设计

> 本文只维护持久化关系、字段与生命周期。玩法见[产品设计](./MVP设计文档.md)，公式和事务边界见[引擎设计](./MVP引擎设计.md)，当前建表定义见 [schema.sql](../src/main/resources/db/schema.sql)。

## 一、建模原则

静态定义与存档事实分离；玩家和NPC共用人物、钱包、背包、领域、对话与考试数据。不设置互斥的主职业，每个领域独立保存完整档案。

持久化代码沿用 MyBatis-Plus：`mvp.entity`、`mapper`、`service`、`service.impl`、`controller`，保留 `IService` 和 `ServiceImpl`。同一实体的业务归入对应 Service，不再拆出单类包。

游戏顺序使用 `total_turn_number`；现实创建时间只在存档上保存。金额、阅读进度、考试分数为整数，学识允许小数。数据库 AI 字段使用 `ai_` 前缀，Java 使用 `aiText` 等小驼峰命名。

## 二、总体关系

当前新库共13张表：

```text
region_definition ← parent_id 自关联
  ↑ 出生地、所在地
game_save
  ├─ family_background
  ├─ game_character（玩家与NPC）
  │   ├─ career_profile_shusheng
  │   ├─ character_equipment → equipment_definition
  │   ├─ character_book_progress → book_definition
  │   ├─ exam_record
  │   └─ memory_record → event_record
  ├─ event_record（人生节点与请求回执）
  └─ dialogue_record（双方均引用本存档人物）

equipment_definition ← book_definition 一对一扩展
```

装备和地区定义跨存档共用。人物、领域档案、背包、阅读、事件、记忆、考试和对话属于某一存档。

## 三、行政区树：region_definition

| 字段 | 含义 |
|---|---|
| `id` | 地区ID |
| `parent_id` | 唯一的直接上级ID，根节点为空 |
| `region_code`、`region_name` | 稳定编码、显示名称 |
| `region_level` | PROVINCE、CITY、COUNTY |
| `enabled`、`sort_order` | 启用状态、同级顺序 |

所有级别放在一张表，子节点不重复保存祖先。当前广东省下有广州、惠州；广州下有番禺、南海、顺德，惠州下有博罗、海丰。出生页只查询惠州下启用的 COUNTY 直接子节点。后续政治线沿父节点链确定管辖关系。

## 四、存档、人物与初始家庭背景

### game_save

| 字段 | 含义 |
|---|---|
| `id`、`created_at` | 存档ID和现实创建时间 |
| `status`、`growth_stage` | 当前流程状态与成长阶段 |
| `birth_year`、`age` | 主角出生年和当前年龄 |
| `current_year`、`current_month`、`turn_in_month` | 当前世界日历 |
| `total_turn_number` | 已经过的普通及强制休养回合数 |

存档只有一条共享时间线。NPC通过通用行动接口消耗回合时，也推进该时间线；尚无独立的后台NPC调度或个人考试日历。

### game_character

| 字段组 | 含义 |
|---|---|
| `id`、`save_id`、`name` | 人物身份及所属存档 |
| `type` | 控制类型，1为玩家、0为NPC，不决定可执行动作 |
| `npc_code` | 本局固定NPC对应的资源编码；玩家为空 |
| `birth_region_id`、`current_region_id` | 出生地与当前所在地 |
| `character_zhili/daode/zhengzhi/jiaoji/tineng` | 五项通用属性 |
| `character_jiankang`、`character_pilao` | 当前健康、疲劳 |
| `wallet` | 当前可支配资金，整数文数 |
| `sick_turns_remaining` | 尚未完成的强制休养回合 |
| `official_position`、`official_rank`、`degree` | 存档列表需要的官职、级别、学位/功名展示字段 |
| `titles_json` | 可同时保有的称号数组，初始为 [] |
| `birthday`、`personality_summary`、`current_state` | 生日、稳定性格及当前状态摘要 |
| `available_stage_code_json`、`enabled` | 可参与阶段与启用状态 |

`(save_id, npc_code)` 唯一，保证同一NPC原型在一局中只生成一次。所有人物都可有自己的钱包、背包和书生档案。初始资金当前固定2000文，与家庭财富没有实时关联。

身份字段只是展示占位，不自动授予官职、学位或权限，也不代表完整组织任职模型。多组织任职与历史仍需后续独立建模。

### family_background

每局一条，`save_id` 唯一。`initial_wealth` 保存初始家庭财富，范围10000～1000000文，当前固定100000；`background_summary` 保存出生背景。交易不修改这张表。

## 五、领域档案：career_profile_shusheng

一位人物一份书生领域档案，`character_id` 唯一并直接引用人物，不再依赖通用 CareerProfile 表。

| 字段 | 含义 |
|---|---|
| `id`、`character_id` | 档案ID与所属人物 |
| `unlock_turn_number` | 首次建立档案的回合 |
| `last_active_turn_number` | 最近参与该领域结算的回合，可为空 |
| `ability_shizi/jingyi/wenzhang/celun/wenxue` | 识字、经义、文章、策论、文学能力 |

开局识字5，其余0，五项能力限制0～100。读书、练习、考试及AI属性结算更新最后活跃回合，休息不更新。后续领域使用自己的完整档案表，与书生档案并存，不区分主次或切换职业。

## 六、装备、书籍与阅读

### equipment_definition / book_definition

通用装备保存 `equipment_code`、名称、类型、稀有度、整数价格、`supplier_npc_code` 和介绍。供应编码对应NPC原型，由业务层映射到当前存档中的实际人物，不跨存档引用NPC主键。

书籍以 `equipment_id` 一对一扩展装备，保存领域编码、阅读条件JSON、难度、基础进度、五项能力权重、疲劳、`total_knowledge` 和单一 `knowledge_summary`。`required_progress` 固定100。

`total_knowledge` 是整本读满后的学识量；`knowledge_summary` 是内容介绍，不再保存25/60/100三档摘要。计算公式只在引擎文档维护。

新开局或显式补齐内容时，按编码插入缺失定义，不覆盖已有数据库定义。JSON修改不等于数据库自动更新。当前“先查再插”仍可能在公共定义首次并发初始化时发生唯一键竞争，暂不增加复杂重试。

### character_equipment

| 字段 | 含义 |
|---|---|
| `save_id`、`character_id`、`equipment_id` | 存档、持有人、装备定义 |
| `quantity` | 本次获取数量，正整数 |
| `acquired_turn_number` | 本次实际入包回合 |
| `status` | 当前使用 OWNED 表示持有 |
| `ai_text` | 可选来源叙事，Java为 aiText |

一次成功获取产生一条记录；同种装备允许多条记录和多件数量，背包按装备定义汇总。没有“人物＋装备”唯一限制。新结构不使用借阅或到期字段。

免费教材同样必须执行获取行为才建立记录；购买在同一事务完成扣款、提供者收款、入包和请求回执。不自动向新存档或旧存档发放物品。

### character_book_progress

`(character_id, equipment_id)` 唯一，保存 `current_progress`、`total_read_turn_number`、`completed` 和 `last_read_turn_number`。无记录按零进度展示，首次实际读书才创建。

持有数量与阅读进度相互独立：同一种书多本不重复加学识，失去物品不删除已学进度。总学识从所有阅读记录计算，不在人物表再存一份可变汇总。

## 七、事件回执、对话与记忆

### event_record

同表区分两种记录：

| 类型 | 特征与用途 |
|---|---|
| 人生节点 | `life_milestone=true`，用于展示首次读满、考试和重要自由行动；`request_id` 可为空 |
| 操作回执 | `life_milestone=false`，保存固定行动、获取、自由行动、对话的原请求与已执行结果 |

`(save_id, request_id)` 唯一。`request_payload_json` 保存首次参数，`settlement_result_json` 保存实际响应；同一编号、相同参数重传返回原结果，更换参数则拒绝。固定读书的骰点也在回执中保存。客户端不能把“重新操作”和“网络重传”使用同一个编号。

`event_code`、`event_summary`、`related_character_id_json`、`occurred_turn_number` 描述已发生事实。人生节点列表只读取标记为真的记录，不展示普通回执。

### dialogue_record

| 字段 | 含义 |
|---|---|
| `id`、`save_id` | 整场对话与所属存档 |
| `actor_id`、`counterpart_id` | 本存档内的发起者和对话对象 |
| `scene_code`、`started_turn_number` | 场景与开始回合 |
| `version` | 每次成功往返递增的版本 |
| `ended` | 是否已经结束并完成整场属性结算 |
| `messages_json` | 对话原文、手动结束标记及已执行交易 |

普通往返可完成本轮明确的交易，但不结算属性；手动或模型结束时只结算一次属性。结束标志、历史、交易、属性和回执在同一短事务提交。模型请求在事务外执行。

### memory_record

保留长期记忆结构：`owner_character_id`、`source_event_id`、`ai_memory_summary`、相关人物和回合。`(owner_character_id, source_event_id)` 唯一。当前对话使用本场历史，不自动生成长期记忆或执行RAG检索。

## 八、考试：exam_record

`(character_id, exam_type)` 唯一，每人物每种考试一份；类型为 EXAM_MENGXUE、EXAM_JINGYI、EXAM_PRE_COUNTY、EXAM_XIANSHI。

准备时固定题目、通过线、基础能力分B、身体偏移R、`dice_roll`、`luck_offset`、`knowledge_total` 和发生回合。`knowledge_total` 使用 DECIMAL(16,4)，其他上述分数和骰点为整数。

结算时保存参与方式、最终整数分和通过状态。系统代行直接使用已保存的B/R及骰点，不重新投骰；重复同一考试ID返回已完成记录。当前考试 `request_id` 仍为空，由考试ID和状态防重。

玩家原文及 `ai_thought_bubble`、`ai_player_content_modifier`、`ai_answer_text`、`ai_content` 为玩家作答和考试AI链路预留，尚未接入时保持空值。

## 九、约束与生命周期

地区、装备编码唯一；运行数据通过人物/存档复合外键避免跨存档引用。删除存档会级联删除该局人物及其领域、背包、进度、事件、记忆、考试与对话；正在被人物引用的地区和被运行数据引用的装备不能直接删除。

本阶段不加入种子字段、随机流表、完整组织任职、关系数值、世界人口或县试后成长数据。种子仅有[引擎设计第十二节的备忘](./MVP引擎设计.md)。

## 十、新库与旧库升级

- 新库使用 [schema.sql](../src/main/resources/db/schema.sql)。`CREATE TABLE IF NOT EXISTS` 不会修改已有表，不能拿它代替迁移。
- [upgrade_mvp_inventory.sql](../src/main/resources/db/upgrade_mvp_inventory.sql) 只面向本次改动前的12表版本，手动执行一次；已有同名新字段的库不适用，也不是通用迁移器。MySQL DDL会隐式提交，执行前需核对当前结构。
- 旧记录保留：旧摘要列改成可空并保留内容；旧 BORROWED 记录不计入新背包，旧阅读进度仍保留，之后需实际领取教材。旧存档创建时间只能填升级时间。
- 旧考试保留原B/R和结果，补中性骰点50、修正0；历史总学识未知，填0而不伪造原快照。
- 完成结构升级后，可对旧存档调用 `POST /api/save/{saveId}/content`，补齐NPC和新增教材定义，不覆盖已有定义、不自动发书。
