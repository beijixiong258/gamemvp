# 古代穿越人生模拟游戏 MVP 数据模型设计

> 本文只定义 MVP 阶段需要保存的数据、实体关系和生命周期。玩法范围见[《MVP设计文档》](./MVP设计文档.md)，数值变化与流程编排见[《MVP引擎设计》](./MVP引擎设计.md)，可执行结构见 [`schema.sql`](../src/main/resources/db/schema.sql)。

## 一、模型目标

MVP 的持久化模型服务于“出生、求学、县试”这一段人生，只保存继续游戏、恢复存档和生成结果所必需的事实。模型需要同时支持以下能力：

- 玩家与 NPC 使用同一套人物结构。
- 人物通用属性与职业线专属能力分开保存。
- 装备通用信息与书籍专属信息分开保存。
- 装备持有权与逐本阅读进度分开保存。
- 已发生的事件事实与人物对事件的记忆分开保存。
- 县试创建时的条件和最终结算结果可以完整恢复。

本阶段不保存普通点击、普通对话或每次行动的流水，也不记录模型名称、原始提示词、原始响应和调用耗时。

## 二、建模原则

### 1. 一实体一表

持久化 Entity 只映射一张表，只保存本表字段和关联对象的 ID。Entity 中不保存其他 Entity、集合或跨表聚合结果；完整人物、书籍详情、人物藏书和结果页数据由 Service 查询后组装为 VO。

一对一扩展使用“共享主键”。例如 `career_shusheng_profile.career_profile_id` 同时是主键和 `character_career.id` 的外键，`book_definition.equipment_id` 同时是主键和 `equipment_definition.id` 的外键。

### 2. 静态定义与运行状态分离

静态定义回答“游戏里有什么”，运行状态回答“当前存档发生了什么”。

| 数据类别 | 数据来源 | 进入数据库的表 |
|---|---|---|
| 装备名称、类型、稀有度、价格 | `game/equipment.json` | `equipment_definition` |
| 书籍条件、难度、权重、知识摘要 | `game/book.json` | `book_definition` |
| 行动、场景、NPC模板、题目、文案 | 资源 JSON | 不直接作为运行状态表 |
| 人物、职业、家庭、装备持有、读书进度 | 游戏运行过程 | 对应动态表 |
| 事件、记忆、考试 | 游戏运行过程 | 对应记录表 |

资源 JSON 是内容定义的编辑来源，定义表是游戏运行时使用的数据库表示。人物进度和存档事实不得回写资源 JSON。

### 3. 游戏时间代替现实时间

除存档管理将来可能增加的最近游玩时间外，领域表不保存 `created_at` 和 `updated_at`。事件顺序、取得装备的时间、职业解锁时间和考试时间统一使用 `total_turn_number`。

### 4. AI 字段明确标识来源

直接保存 AI 生成结果的 Java 字段使用大写 `AI` 前缀，数据库字段使用对应的小写下划线形式：

| Java 字段 | 数据库字段 | 含义 |
|---|---|---|
| `AIText` | `ai_text` | 装备来源文字 |
| `AIMemorySummary` | `ai_memory_summary` | 人物记忆摘要 |
| `AIThoughtBubble` | `ai_thought_bubble` | 县试思维泡泡 |
| `AIPlayerContentModifier` | `ai_player_content_modifier` | 玩家答案内容修正值 |
| `AIAnswerText` | `ai_answer_text` | 系统代行答卷 |
| `AIContent` | `ai_content` | 考试评价与结果叙事 |

玩家原文、配置文案以及 Java 已经确定的事实不使用 `AI` 前缀。

## 三、总体关系

静态定义独立于具体存档；动态数据以 `game_save` 为根。人物、事件和考试均属于一个存档。

```text
game_save
├─ family_state
├─ game_character
│  ├─ character_career
│  │  └─ career_shusheng_profile
│  ├─ character_equipment ──────────┐
│  ├─ character_book_progress ──────┤
│  ├─ memory_record ── event_record │
│  └─ exam_record                   │
└─ event_record                     │
                                     ▼
equipment_definition
└─ book_definition
```

关系基数如下：

| 父对象 | 子对象 | 基数 | 说明 |
|---|---|---|---|
| 存档 | 人物 | 一对多 | 一个存档包含玩家和固定 NPC |
| 存档 | 家庭状态 | 一对一 | MVP 每个存档只有一个玩家家庭 |
| 人物 | 职业档案 | 一对多 | 只为真正进入过的职业线建档 |
| 职业档案 | 书生能力 | 一对零或一 | 只有书生职业存在该扩展 |
| 装备定义 | 书籍定义 | 一对零或一 | 书籍是装备的一种业务扩展 |
| 人物 | 装备记录 | 一对多 | 同一种装备可以有多次来源或使用权 |
| 人物与书籍 | 阅读进度 | 一对一 | 每人每本书最多一条累计进度 |
| 存档 | 事件 | 一对多 | 只记录重大事实 |
| 事件与人物 | 记忆 | 一对多 | 同一事件可以形成多个人物各自的记忆 |
| 人物 | 县试记录 | 一对零或一 | MVP 每个人物只参加一次县试 |

## 四、存档与人物

### 1. `game_save`

对应 `GameSave`，是动态数据的聚合根。

| 字段组 | 字段 | 说明 |
|---|---|---|
| 标识 | `id` | 32 位 UUID 字符串 |
| 流程 | `status`、`growth_stage` | 当前游戏流程和成长阶段 |
| 日历 | `birth_year`、`current_year`、`current_month`、`age` | 当前人生时间 |
| 回合 | `turn_in_month`、`total_turn_number` | 月内回合和已完成的总回合数 |
| 随机 | `random_seed` | 当前存档所有程序随机结果的根种子 |

`turn_in_month=0` 只用于尚未进入正常回合的阶段。六岁入学后取值为 1～4。县试完成后 `status` 进入完成态，不再创建普通成长回合。

建议状态编码：

```text
CREATED → CHILDHOOD → STUDYING → EXAM_READY → COMPLETED
```

### 2. `game_character`

对应 `Character`，统一保存玩家与 NPC。

| 字段组 | 字段 | 说明 |
|---|---|---|
| 归属 | `id`、`save_id` | 人物标识及所属存档 |
| 身份 | `name`、`type`、`birthday` | 姓名、控制方式和生日 |
| 通用属性 | `character_zhili`、`character_daode`、`character_zhengzhi`、`character_jiaoji`、`character_tineng` | 跨职业长期保留的能力 |
| 当前状态 | `character_jiankang`、`character_pilao` | 健康与疲劳 |
| 职业入口 | `current_main_career_code` | 当前主职业编码 |
| 语义信息 | `personality_summary`、`current_state` | 人物性格与当前状态摘要 |
| 可用范围 | `available_stage_code_json`、`enabled` | 可参与阶段及是否启用 |

`type=1` 表示由人类玩家控制，`type=0` 表示 NPC。父亲、母亲、先生、同窗和考官等身份由初始化模板与场景配置提供，不使用 `type` 表达。

人物表不保存书生能力、装备集合、读书进度、事件列表或量化关系。

### 3. `family_state`

对应 `FamilyProfile`。每个存档最多一条，保存：

- `id`：家庭状态记录 ID。
- `save_id`：所属存档 ID，同时具有唯一性。
- `wealth`：家庭可用财富，单位为文。
- `background_summary`：家庭背景摘要。

父母本人仍保存为 `game_character` 中的普通 NPC，不在家庭表重复人物字段。

## 五、职业模型

### 1. `character_career`

对应 `CareerProfile`，表示人物已经进入过的一条职业线。

| 字段 | 说明 |
|---|---|
| `id` | 职业档案 ID |
| `character_id` | 所属人物 |
| `career_code` | 稳定职业编码 |
| `unlock_turn_number` | 进入该职业线时的总回合数 |
| `last_active_turn_number` | 最后以该职业活动的总回合数 |
| `status` | 职业档案状态 |

同一人物与同一职业编码只能存在一条记录。人物出生时建立 `CAREER_PINGMIN`；六岁入学时才建立 `CAREER_SHUSHENG`。切换主职业不会删除过去的职业档案。

### 2. `career_shusheng_profile`

对应 `CareerShushengProfile`，只保存书生职业线专属能力：

- `ability_shizi`：识字。
- `ability_jingyi`：经义。
- `ability_wenzhang`：文章。
- `ability_celun`：策论。
- `ability_wenxue`：文学。

`career_profile_id` 既是主键，也是父职业档案外键。平民职业当前没有专属能力，因此不建立空的平民扩展表。

## 六、装备与书籍

### 1. `equipment_definition`

对应 `Equipment`，保存所有装备共有的静态字段：

- `id` 与唯一的 `equipment_code`。
- `equipment_name`、`equipment_type`、`rarity_code`。
- 以铜钱文数保存的 `price`。
- `description`。

MVP 当前只有书籍，但通用装备表不依赖书籍模块。以后增加武器或护甲时，由对应模块建立自己的扩展表。

### 2. `book_definition`

对应 `Book`，通过共享的 `equipment_id` 一对一扩展通用装备。它只保存书籍独有的数据：

- 适用职业 `applicable_career_code`。
- 固定结构的 `reading_requirement_json`。
- `difficulty`、`required_progress`、`base_progress_per_turn`。
- 五项书生能力收益权重。
- `fatigue_cost`。
- 初识、可用和掌握三个阶段的知识摘要。

书名、稀有度、价格和装备类型只从父装备定义取得，不在书籍表重复保存。

### 3. `character_equipment`

对应 `EquipmentRecord`。每条记录表示人物对一件装备的一次持有或使用权：

- `save_id`、`character_id`、`equipment_id` 确定归属。
- `ai_text` 记录由 AI 生成的来源描述。
- `acquired_turn_number` 记录取得时间。
- `expiration_turn_number` 表示临时权限到期回合，永久持有时为空。
- `status` 表示有效、归还、遗失或过期等状态。

同一人物可以因为不同来源拥有同一装备的多条记录。当前是否可使用由有效状态和到期回合共同决定。

### 4. `character_book_progress`

对应 `BookRecord`，保存人物对一本书的累计学习状态：

- `character_id` 与 `equipment_id` 组成业务唯一关系。
- `current_progress` 保存 0～100 的阅读进度。
- `total_read_turn_number` 保存累计投入的阅读回合数。
- `completed` 保存是否完成。
- `last_read_turn_number` 保存最后阅读时间。

阅读进度不绑定某一次装备持有记录。人物归还书籍、重新取得书籍或临时切换职业后，已经形成的阅读进度仍然保留。

## 七、事件与记忆

### 1. `event_record`

对应 `EventRecord`，只保存已经成立的重大事实：

- `save_id` 和稳定的 `event_code`。
- `event_summary`。
- `related_character_id_json`。
- `occurred_turn_number`。
- `settlement_result_json`。
- `life_milestone`。

普通查看、普通点击和没有长期影响的一般行动不形成事件。事件一旦写入，就代表该事实已经在游戏世界中发生。

### 2. `memory_record`

对应 `MemoryRecord`，表示某个人物以后可能回忆起某个事件：

- `save_id` 与 `owner_character_id`。
- `source_event_id`。
- `ai_memory_summary`。
- `related_character_id_json`。
- `occurred_turn_number`。

同一人物对同一来源事件最多保存一条记忆。同一个事件可以分别成为玩家、父母、先生或同窗的记忆。MVP 只按人物、事件和回合查询，不保存向量。

## 八、考试记录

### `exam_record`

对应 `ExamRecord`，同时承担县试创建状态、结算状态和结果展示数据。

| 阶段 | 主要字段 |
|---|---|
| 创建考试 | `save_id`、`character_id`、`exam_type`、`question_text`、`pass_threshold`、`base_ability_score`、`random_offset`、`ai_thought_bubble`、`turn_number`、`status` |
| 玩家选择 | `request_id`、`player_choice`、`player_input` |
| 以身入局 | `ai_player_content_modifier` |
| 最终结果 | `final_score`、`ai_answer_text`、`ai_content`、`status` |

题目、基础能力值、综合随机偏移和思维泡泡在考试创建后固定保存。`request_id` 标识一次最终结算请求；`player_input` 只在以身入局时存在；`ai_answer_text` 只在系统代行时存在。

同一人物在 MVP 中最多存在一条 `EXAM_XIANSHI` 记录。推荐状态编码：

```text
READY → COMPLETED_PASS
      └→ COMPLETED_FAIL
```

## 九、数据约束与删除关系

数据库脚本已经表达以下关系：

- 每张表都有独立主键，一对一扩展表使用父表主键。
- `equipment_code` 全局唯一。
- 同一存档只有一条家庭状态。
- 同一人物的同一职业只有一条职业档案。
- 同一人物对同一本书只有一条阅读进度。
- 同一人物对同一事件只有一条记忆。
- 同一人物在 MVP 中只有一条同类型考试记录。
- 人物装备、记忆和考试通过复合外键保持 `save_id` 与所属人物或事件处于同一存档。

删除存档时，其人物、家庭、职业、装备记录、读书进度、事件、记忆和考试数据一并删除。装备定义已经被人物持有或形成阅读进度时不能直接删除；删除书籍的父装备定义会同步删除没有运行状态引用的书籍扩展。

## 十、MVP暂不建模的内容

以下内容不进入当前数据模型：

- 通用人物关系表和好感、信任、敬重等量化态度。
- 自由生成并永久存在的新 NPC。
- 普通操作流水和完整聊天历史。
- LangGraph 检查点或长期智能体会话状态。
- 向量、Embedding 和独立向量数据库。
- 官职、权限、地区、政策、战争和经济世界模型。
- 模型调用日志、Token 用量与调试响应。

需要新增内容时，先判断它是静态定义、当前状态、事件事实还是人物记忆，再放入对应边界，不能把所有信息继续堆入人物主表。

## 十一、命名规则

数据库表名和字段名使用小写下划线。跨数据库、后端和前端传递的稳定业务编码使用大写下划线。

中国特色且没有准确通用英文的名称使用“业务类别英文前缀＋完整拼音”：

| 中文概念 | 编码或字段 |
|---|---|
| 平民职业 | `CAREER_PINGMIN` |
| 书生职业 | `CAREER_SHUSHENG` |
| 县试 | `EXAM_XIANSHI` |
| 私塾 | `SCENE_SISHU` |
| 《三字经》 | `BOOK_SANZIJING` |
| 识字能力 | `ability_shizi` |
| 经义能力 | `ability_jingyi` |
| 文章能力 | `ability_wenzhang` |
| 策论能力 | `ability_celun` |
| 文学能力 | `ability_wenxue` |

`character`、`career`、`health`、`memory` 等通用概念继续使用英文。稳定编码在资源、Java 和数据库之间保持一致。
