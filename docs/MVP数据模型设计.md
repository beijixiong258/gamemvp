# 古代穿越人生模拟游戏 MVP 数据模型设计

> 本文只说明持久化对象、关系、关键字段与生命周期。玩法见[《MVP设计文档》](./MVP设计文档.md)，计算和 Resolver 编排见[《MVP引擎设计》](./MVP引擎设计.md)，实际建表以 `src/main/resources/db/schema.sql` 为准。

## 一、建模原则

- 静态定义与存档状态分开：地区、装备和书籍是定义；人物、成长和事件属于存档。
- 一个业务实体使用一张主表；职业和书籍只在确有专属字段时使用扩展表。
- 游戏内顺序统一使用 `total_turn_number`，领域表暂不增加现实时间戳。
- AI 生成字段使用 `ai_` 前缀，避免与确定事实混淆。
- 金额、阅读进度和考试分数都保存为整数。
- 地区只使用一张邻接表，不为省、市、县分别建表。

持久化代码按 MyBatis-Plus 技术层组织：实体统一位于 `mvp.entity`，Mapper 位于 `mvp.mapper`，Service 接口位于 `mvp.service`，实现位于 `mvp.service.impl`，HTTP 入口位于 `mvp.controller`。同一实体保留 `IService` 与 `ServiceImpl`，业务方法放入对应 Service，避免再为单个业务对象建立纵向小包。

## 二、总体关系

```text
region_definition
  └─< region_definition
       （parent_id自关联）

game_save
  ├─< game_character >─ region_definition
  │    ├─< character_career
  │    │    └─ career_profile_shusheng
  │    ├─< character_equipment >─ equipment_definition
  │    ├─< character_book_progress >─ book_definition
  │    ├─< memory_record
  │    └─< exam_record
  ├── family_background
  └─< event_record
       └─< memory_record

equipment_definition
  └── book_definition
```

`game_save` 是运行数据根节点。`region_definition` 与装备定义不属于某个存档，可被多个存档引用。

## 三、行政区树

### `region_definition`

所有行政区节点放在同一张表：

| 字段 | 含义 |
|---|---|
| `id` | 地区主键 |
| `parent_id` | 直接上级地区 ID；根节点为 `NULL` |
| `region_code` | 稳定业务编码 |
| `region_name` | 显示名称 |
| `region_level` | `PROVINCE`、`CITY` 或 `COUNTY` |
| `enabled` | 是否启用 |
| `sort_order` | 同一父节点下的显示顺序 |

`parent_id` 外键回指本表 `id`，这就是唯一的层级关系；`region_level` 只描述节点等级，不产生分层表。

当前树为：

```text
广东省（PROVINCE）
├─ 广州（CITY）
│  ├─ 番禺县（COUNTY）
│  ├─ 南海县（COUNTY）
│  └─ 顺德县（COUNTY）
└─ 惠州（CITY）
   ├─ 博罗县（COUNTY）
   └─ 海丰县（COUNTY）
```

MVP 的可选出生地不写入地区表。业务层读取惠州节点下启用的 `COUNTY` 子节点，结果即博罗县和海丰县。后续政治线可以沿 `parent_id` 向上确定管辖链，或向下取得作用范围。

## 四、存档、人物与家庭

### 1. `game_save`

保存一局的时间与流程：

- `status`：`STUDYING`、`STAGE_EXAM_READY`、`EXAM_READY`、`COMPLETED` 等。
- `birth_year`：默认 1541。
- `current_year`：默认开局 1547。
- `current_month`：1～12。
- `turn_in_month`：正常游戏阶段为 1～4。
- `age`：开局为 6。
- `total_turn_number`：已完成的结束回合行动数，开局为 0。
- `growth_stage`：求学、阶段考试、县试或完成。

一局只有一个当前时间，不另外创建日历流水表。

### 2. `game_character`

玩家与固定 NPC 共用人物表。关键字段包括：

- `save_id`、`name`、`type`。
- `birth_region_id`：出生地区，创建后不变。
- `current_region_id`：当前所在地区，后续允许移动。
- 五项通用属性：智力、道德、政治、交际、体能。
- 当前健康、疲劳、主职业、生日和启用状态。
- `personality_summary`、`current_state` 与可参与阶段编码。

两个地区字段都引用 `region_definition.id`。五项通用属性开局固定为 20；人物表不保存书生能力、装备集合或阅读进度。

### 3. `family_background`

每个存档只有一条开局家庭背景，对应实体 `FamilyBackground`：

- `initial_wealth`：开局家庭财富，整数文数，范围 10000～1000000，当前固定为 100000。
- `background_summary`：开局时固定的家庭背景摘要。

家庭背景属于存档，不绑定单个人物，也不承担游戏进行中的可变财富状态。后续若加入个人财产或家庭账目，应单独建运行状态模型。

## 五、职业

### 1. `character_career`

一条记录代表一个人物的一段职业身份，对应 `mvp.entity.CareerProfile`：

- `character_id`、`career_code`。
- `unlock_turn_number`、`last_active_turn_number`。
- `status`。

同一人物与同一职业编码唯一。MVP 开局直接建立 `CAREER_SHUSHENG` 档案，不为 0～5 岁建立逐年职业状态。

### 2. `career_profile_shusheng`

对应 `mvp.entity.CareerProfileShusheng`，以 `career_profile_id` 同时作为主键和外键，保存书生专属能力：

- 识字。
- 经义。
- 文章。
- 策论。
- 文学。

五项均为 0～100 的整数。初始识字为 5，其余为 0。

## 六、装备、书籍与阅读

### 1. `equipment_definition`

通用装备定义保存编码、名称、类型、稀有度、价格和基础介绍。价格使用整数文数。

### 2. `book_definition`

书籍以 `equipment_id` 一对一扩展通用装备，保存：

- 适用职业和阅读条件。
- 难度。
- 完成所需进度和单回合基础进度，均为整数。
- 五项能力权重百分比。
- 基础疲劳。
- 初识、可用、掌握三个知识摘要。

书籍定义不保存任何人物状态。

### 3. `character_equipment`

保存人物对装备的持有权或使用权：

- 所属存档、人物和装备。
- 取得回合、可选的到期回合、状态。
- 可选的 AI 来源叙事。

临时借阅通过到期回合表达，不复制书籍定义。

### 4. `character_book_progress`

每个人物、每本书最多一条累计记录：

- `current_progress`：0～100 的整数。
- `total_read_turn_number`：累计阅读回合数。
- `completed`：是否完成。
- `last_read_turn_number`：最后阅读回合，可为 `NULL`。

阅读进度与持有记录分离。归还后进度仍保留，再次获得使用权可继续阅读。

## 七、事件与记忆

### 1. `event_record`

只保存已经发生且值得长期保留的事实：

- 事件编码、事实摘要和相关人物 ID。
- 发生回合。
- 已执行的结算结果。
- 是否为人生节点。

普通操作不需要逐条写成事件。

### 2. `memory_record`

同一事件可以为不同人物形成不同记忆：

- 记忆拥有者。
- 来源事件。
- AI 生成的记忆摘要。
- 相关人物与发生回合。

`owner_character_id + source_event_id` 唯一，避免同一人物重复记住同一事件。

## 八、考试

### `exam_record`

每次阶段考试和最终县试都使用同一张表。`exam_type` 区分：

| 年龄 | `exam_type` |
|---:|---|
| 8 | `EXAM_MENGXUE` |
| 12 | `EXAM_JINGYI` |
| 15 | `EXAM_PRE_COUNTY` |
| 16 | `EXAM_XIANSHI` |

创建考试时保存：

- `save_id`、`character_id`、`exam_type` 和 `turn_number`。
- `question_text`、`pass_threshold`。
- `base_ability_score`、`state_offset`。
- `ai_thought_bubble`。
- `status`。

最终结算时补充：

- `request_id` 与 `player_choice`。
- 以身入局使用的 `player_input` 和 `ai_player_content_modifier`。
- 系统代行使用的 `ai_answer_text`。
- `final_score` 与 `ai_content`。

`pass_threshold`、`base_ability_score`、`state_offset`、`ai_player_content_modifier` 和 `final_score` 均为整数。`character_id + exam_type` 唯一，因此同一人物每种考试最多一条记录。

前三种考试完成后继续求学；`EXAM_XIANSHI` 完成后存档结束。流程差异由存档状态和考试类型表达，不另建阶段考试表。

## 九、约束与删除关系

- 地区编码、装备编码唯一。
- 人物 ID 与存档 ID 建立复合唯一键，供跨表复合外键引用。
- 每个存档只有一条初始家庭背景。
- 同一人物同一职业唯一。
- 同一人物同一本书只有一条阅读进度。
- 同一人物同一考试类型只有一条考试记录。
- 删除存档时级联删除人物、家庭、职业、装备持有、阅读进度、事件、记忆和考试。
- 已被人物引用的地区不能删除；行政区父节点也不能在仍有子节点时删除。
- 装备定义被运行状态引用时不允许删除。

## 十、当前不建模

- 0～5 岁逐年状态和普通回合。
- 完整明代行政区全集与历年沿革。
- 人口、税收、政策、战争等地区运行状态。
- 官职、权限和差遣。
- 通用量化关系与完整操作流水。
- 县试之后的考试和人生阶段。

这些内容后续可以围绕现有地区树、人物、职业和事件继续扩展，不需要改变当前主关系。
