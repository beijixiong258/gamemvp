# 资源 JSON 参考

> 本文定义 `src/main/resources` 中现有 JSON 配置的结构和引用关系。数值公式与流程语义见[《MVP 引擎设计》](../MVP引擎设计.md)。当前已接入固定行动、获取/购买、NPC生成、自由行动和对话；考试AI与长期记忆提示词仍预留。

## 一、文件总览

| 文件 | 根数组 | 当前条目数 | 用途 |
|---|---|---:|---|
| [`game/action.json`](../../src/main/resources/game/action.json) | `action` | 8 | 行动入口和路由元数据 |
| [`game/equipment.json`](../../src/main/resources/game/equipment.json) | `equipment` | 10 | 通用装备定义 |
| [`game/book.json`](../../src/main/resources/game/book.json) | `book` | 10 | 书籍专属规则 |
| [`game/exam.json`](../../src/main/resources/game/exam.json) | `exam` | 4 | 考试节点、题目和计分权重 |
| [`game/npc.json`](../../src/main/resources/game/npc.json) | `npc` | 6 | 固定 NPC 原型 |
| [`game/scene.json`](../../src/main/resources/game/scene.json) | `scene` | 4 | 场景及可用内容 |
| [`game/text.json`](../../src/main/resources/game/text.json) | `text` | 6 | 固定反馈文本模板 |
| [`prompt/prompt.json`](../../src/main/resources/prompt/prompt.json) | `prompt` | 6 | Resolver 系统提示词 |

地区定义不在 JSON 中，当前保存在 `db/schema.sql` 的 `region_definition` 初始化数据里。

运行时使用分为两类：行动、考试与固定文本由对应 Service 读取；`equipment.json`、`book.json` 在新开局时按编码补充缺失的数据库定义，后续阅读以数据库定义为准。已有装备或书籍定义不会被隐式覆盖，修改教材 JSON 不会自动更新已入库内容，显式同步机制留待后续确定。场景配置还用于判断获取行为的提供者和对话对象是否在场。固定NPC在开局生成；旧存档可显式调用补齐内容接口。普通读档不导入定义、不发放物品。

## 二、通用格式

- 文件根节点统一为对象，对象中放置一个与内容同名的数组。
- 业务编码使用大写蛇形命名，例如 `READ_BOOK`、`SCENE_SISHU`。
- JSON 不支持注释，不应保留尾随逗号。
- `int` 表示 JSON 整数，`number` 表示允许小数的 JSON 数字。
- 标记为“条件”的字段只在对应类型下出现；未标记的字段按当前契约视为必填。
- 跨文件引用使用稳定编码，不使用显示名称。

## 三、`action.json`

根结构：

```json
{
  "action": []
}
```

### 行动字段

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `actionCode` | `string` | 是 | 行动唯一编码 |
| `actionName` | `string` | 是 | 玩家可见名称 |
| `actionType` | `string` | 是 | 行动分类 |
| `availableSceneCode` | `string[]` | 是 | 允许执行该行动的场景编码 |
| `requiredParameter` | `string[]` | 是 | 调用该行动时必须提供的参数名；无参数时为空数组 |
| `engineRuleCode` | `string` | 是 | 业务层选择结算路径的规则编码 |
| `feedbackTextCode` | `string` | 条件 | 固定行动完成后使用的 `text.json` 模板编码 |
| `promptCode` | `string` | 条件 | 需要模型参与时使用的 `prompt.json` 编码 |
| `endTurn` | `boolean` | 是 | 是否消耗一个普通行动回合；固定成长和自由行动为 `true`，获取、对话与考试为 `false` |

`actionType` 当前取值：

| 值 | 含义 |
|---|---|
| `FIXED_ACTION` | 完全由 Java 引擎结算 |
| `FREE_ACTION` | Resolver 解析自然语言，再由 Java 应用驱动量 |
| `NPC_DIALOGUE` | NPC 对话，默认不结束回合 |
| `EXAM_ACTION` | 考试答题路径 |
| `EQUIPMENT_ACTION` | 免费获取或购买，统一进入背包业务 |

`requiredParameter` 表达行动特有的内容参数，不替代HTTP请求契约。固定行动还需 `requestId`、`expectedTurnNumber`；NPC_DIALOGUE的元数据描述开始对话，使用实际 `counterpartId`，后续消息另传text和expectedVersion。获取请求使用 `requestId`、`supplierNpcCode`、`equipmentCode`、`quantity`，价格由服务端查表，不接收模型报价。完整参数见[核心方法参考](./核心方法参考.md)。

`EXAM_AUTO` 和 `EXAM_PLAYER` 均配置为 `endTurn=false`。考试节点由此前的普通行动推进时间后触发，结算考试只切换阶段，不能再次调用 `TurnEngine.advance`。

### 规则编码与方法

| `engineRuleCode` | 主要处理入口 |
|---|---|
| `RULE_READ_BOOK` | `CharacterEngine.readBook` |
| `RULE_PRACTICE_WRITING` | `CharacterEngine.practiceWriting` |
| `RULE_REST` | `CharacterEngine.rest` |
| `RULE_DRIVER` | 自由行动 Resolver，再调用 `CharacterEngine.applyDriver` |
| `RULE_NPC_DIALOGUE` | 对话 Resolver，整场结束时应用一次属性驱动量 |
| `RULE_ACQUIRE_EQUIPMENT` | `EquipmentRecordService.acquire`，免费与收费共用 |
| `RULE_EXAM_AUTO` | `ExamEngine.settleAuto` |
| `RULE_EXAM_PLAYER` | 考试评价 Resolver，再调用 `ExamEngine.settlePlayer` |

## 四、`equipment.json`

根结构为 `{ "equipment": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `equipmentCode` | `string` | 装备唯一编码，也是其他文件引用装备的键 |
| `equipmentName` | `string` | 显示名称 |
| `equipmentType` | `string` | 装备类型；当前只有 `BOOK` |
| `rarityCode` | `string` | 稀有度；当前有 `COMMON`、`UNCOMMON`、`RARE`、`LEGENDARY` |
| `price` | `int` | 整数价格，单位为文；0仍需执行获取行为 |
| `supplierNpcCode` | `string` | 提供者的NPC原型编码，执行时定位本存档实际人物 |
| `description` | `string` | 基础介绍，不包含人物使用状态 |

书籍必须先在本文件中具有通用装备定义，再由 `book.json` 使用同一个 `equipmentCode` 增加书籍规则。

## 五、`book.json`

根结构为 `{ "book": [] }`。

### 书籍字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `equipmentCode` | `string` | 对应 `equipment.json.equipmentCode` |
| `growthDomainCode` | `string` | 学习收益所属领域；当前为 `DOMAIN_SHUSHENG`，不作为身份或阅读资格条件 |
| `readingRequirement` | `object[]` | 开始阅读前需要满足的条件 |
| `difficulty` | `int` | 阅读难度，参与阅读进度计算 |
| `requiredProgress` | `int` | 完成阅读所需进度；当前均为 `100` |
| `baseProgressPerTurn` | `int` | 未应用人物修正前的单回合基础进度 |
| `abilityShiziWeight` | `int` | 识字收益权重百分比 |
| `abilityJingyiWeight` | `int` | 经义收益权重百分比 |
| `abilityWenzhangWeight` | `int` | 文章收益权重百分比 |
| `abilityCelunWeight` | `int` | 策论收益权重百分比 |
| `abilityWenxueWeight` | `int` | 文学收益权重百分比 |
| `fatigueCost` | `int` | 阅读行动的基础疲劳 |
| `totalKnowledge` | `int` | 本书进度100时获得的完整学识量，不同书可不同 |
| `knowledgeSummary` | `string` | 书籍内容介绍，不按25/60阈值分档解锁 |

同一本书的五项能力权重按百分比配置，合计应为 `100`。`readingRequirement` 中的多项条件按同时满足理解。

学校七本教材由 `NPC_XIANSHENG` 免费提供，新增三本由集市 `NPC_SHANGREN` 收费提供，均需实际获取后才入包。持有不等于可读，还需满足年龄、属性、能力和前置进度。无阅读记录时进度为0，读满后仍可温习。库存数量不会重复增加同一书的学识。

`BOOK_GPT1_WEIGHTS` 显示为《GPT-1的权重》，对应真实的 *WEIGHTS — The Complete Parameters of GPT-1*：约1.17亿个参数分成80卷，首卷另有纸笔推理指南，游戏中将整套合成一件物品。文字可以整活，但参数集不是现代百科全书；学识、策论等加成是游戏抽象。出处：[出版页](https://weights-press.netlify.app/)、[完整卷目](https://the-open-accelerator.com/GPT-1/index.html)。价格和各书学识见[机制总览](../MVP机制总览与待定事项.md)。

### 阅读条件字段

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `type` | `string` | 是 | 条件类型 |
| `target` | `string` | 条件 | 被检查的属性、领域能力字段或书籍编码 |
| `value` | `int` | 条件 | 最低年龄、属性、能力或阅读进度 |

| `type` | `target` | `value` | 语义 |
|---|---|---|---|
| `MIN_AGE` | 不使用 | 必填 | 行动人物年龄至少为 `value`，按世界年份和该人物生日年份计算 |
| `MIN_GENERAL_ATTRIBUTE` | `CharacterState` 字段名 | 必填 | 对应通用属性至少为 `value` |
| `MIN_CAREER_ABILITY` | `ScholarState` 字段名 | 必填 | 对应领域 CP 中的能力至少为 `value`；当前为书生能力，条件编码沿用 |
| `BOOK_PROGRESS` | 书籍 `equipmentCode` | 必填 | 对应书籍阅读进度至少为 `value` |

## 六、`exam.json`

根结构为 `{ "exam": [] }`。

### 考试字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `examType` | `string` | 考试唯一编码，与 `TurnEngine` 的考试常量一致 |
| `examName` | `string` | 显示名称 |
| `triggerAge` | `int` | 触发周岁，应与 `TurnEngine.examTypeAtAge` 一致 |
| `sceneCode` | `string` | 考试使用的场景编码 |
| `finalExam` | `boolean` | 是否为结束 MVP 人生阶段的县试 |
| `question` | `object[]` | 题目数组；MVP 每种考试当前只有一道题 |

`examType` 当前为 `EXAM_MENGXUE`、`EXAM_JINGYI`、`EXAM_PRE_COUNTY`、`EXAM_XIANSHI`。

### 题目字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `questionCode` | `string` | 题目唯一编码 |
| `questionText` | `string` | 展示并写入考试记录的题目原文 |
| `passThreshold` | `int` | 整数通过线 |
| `generalAbilityWeight` | `object` | 五项通用属性权重 |
| `careerAbilityWeight` | `object` | 五项书生能力权重 |
| `knowledgeWeight` | `number` | 已学知识值权重 |
| `scoringPoint` | `string[]` | 提供给评价 Resolver 的评分要点 |
| `thoughtPromptCode` | `string` | 思维泡泡提示词编码 |
| `evaluationPromptCode` | `string` | 玩家答案评价提示词编码 |
| `answerPromptCode` | `string` | 系统代行答卷与结果叙事提示词编码 |

`generalAbilityWeight` 固定包含：

```text
characterZhili
characterDaode
characterZhengzhi
characterJiaoji
characterTineng
```

`careerAbilityWeight` 固定包含：

```text
abilityShizi
abilityJingyi
abilityWenzhang
abilityCelun
abilityWenxue
```

五项通用属性权重、五项书生领域能力权重与 `knowledgeWeight` 合计应为 `1.00`。权重传给 `ExamEngine.ExamWeights` 时使用 `BigDecimal`。

触发考试时，业务层读取该类型的固定题目，以触发回合的结算后状态计算 B/R，并把题目、通过线、B/R、总学识、1D100骰点和普通骰点修正固定进考试记录。系统代行直接使用已保存快照计分；`scoringPoint` 和三个提示词编码为后续模型流程保留，当前不产生 AI 思路、答卷或评价。

## 七、`npc.json`

根结构为 `{ "npc": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `npcCode` | `string` | NPC 原型唯一编码 |
| `displayName` | `string` | 本阶段直接使用的固定姓名 |
| `roleCode` | `string` | 身份角色编码 |
| `nameMode` | `string` | 姓名来源；当前为 `FIXED` |
| `defaultSceneCode` | `string` | 默认所在场景编码 |
| `personalitySummary` | `string` | 提供给 NPC 对话 Resolver 的稳定性格摘要 |
| `enabled` | `boolean` | 是否启用该原型 |

本文件定义NPC原型，不保存某局中的资金、背包、对话或临时状态。开局映射到 `game_character`（type=0），并建立与玩家相同结构的书生档案；同一存档按npcCode防止再次生成。当前不调用模型起名，也不自动运行NPC后台行动。

## 八、`scene.json`

根结构为 `{ "scene": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `sceneCode` | `string` | 场景唯一编码 |
| `sceneName` | `string` | 显示名称 |
| `description` | `string` | 场景基础说明 |
| `availableActionCode` | `string[]` | 场景允许的行动编码 |
| `availableNpcCode` | `string[]` | 场景中可交互的 NPC 编码 |

`availableActionCode` 应与 `action.json.availableSceneCode` 保持双向一致；`availableNpcCode` 应包含 `npc.json` 中以该场景为 `defaultSceneCode` 的启用 NPC。

## 九、`text.json`

根结构为 `{ "text": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `textCode` | `string` | 文本模板唯一编码 |
| `template` | `string` | 使用 `{{变量名}}` 占位符的中文模板 |

当前模板变量：

| 变量 | 含义 |
|---|---|
| `bookName` | 书籍显示名称 |
| `progressDelta` | 实际阅读进度增长 |
| `fatigueDelta` | 实际疲劳变化 |
| `abilityDelta` | 实际文章能力增长 |
| `healthDelta` | 实际健康恢复 |
| `reason` | 行动被拒绝的原因 |

模板只负责展示已经确定的事实，不参与数值计算。新增占位符时，渲染调用方必须提供同名值。

## 十、`prompt.json`

根结构为 `{ "prompt": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `promptCode` | `string` | 提示词唯一编码，也是其他 JSON 的引用键 |
| `taskType` | `string` | Resolver 任务分类 |
| `systemText` | `string` | 模型调用使用的系统约束 |

`taskType` 当前取值：

| 值 | 预期输出 |
|---|---|
| `FREE_ACTION` | driverPatch、eventSummary、lifeMilestone、acquisitions |
| `NPC_DIALOGUE` | reply、endDialogue、driverPatch、acquisitions |
| `MEMORY_SUMMARY` | 基于已发生事件的简短记忆 |
| `EXAM_THOUGHT` | 不包含完整答案的作答思路 |
| `EXAM_EVALUATION` | 玩家答案的内容修正和事实评价 |
| `EXAM_ANSWER` | 与 Java 已定成绩一致的答卷表现和结果叙事 |

`systemText` 只定义稳定边界。人物快照、题目、场景、记忆等动态内容应由调用方在每次调用时组装，不应回写进静态文件。

运行时由 `mvp.ai.GameClient` 通过 `ClasspathJsonLoader` 一次加载并按 `promptCode` 建立只读索引。底层读取和反序列化使用项目已有的 Hutool `ResourceUtil` 与 `JSONUtil`；目前 `FreeActionResolver` 已使用 `PROMPT_FREE_ACTION` 与 `PROMPT_NPC_DIALOGUE`，其余提示词等待对应链路接入。

`acquisitions` 每项只包含供应者编码、装备编码和数量；业务层执行后才成为事实，不是允许模型任意改库的工具。对话普通轮忽略属性驱动量，结束轮按整场给出一次变化；手动结束不执行历史中重复出现的交易。`history` 中的 `executedTrades` 是既成事实，不能再次解释成新订单。驱动量的字段与边界见[核心方法参考](./核心方法参考.md)。

模型配置固定写在 `application.yaml`。模型失败直接报错，不使用自动重试掩盖网络或余额问题。资源中不配置随机种子。

## 十一、跨文件引用

| 来源字段 | 目标字段 |
|---|---|
| `action.availableSceneCode[]` | `scene.sceneCode` |
| `action.feedbackTextCode` | `text.textCode` |
| `action.promptCode` | `prompt.promptCode` |
| `book.equipmentCode` | `equipment.equipmentCode` |
| `equipment.supplierNpcCode` | `npc.npcCode` |
| `book.readingRequirement[].target`（`BOOK_PROGRESS`） | `book.equipmentCode` |
| `exam.sceneCode` | `scene.sceneCode` |
| `exam.*PromptCode` | `prompt.promptCode` |
| `npc.defaultSceneCode` | `scene.sceneCode` |
| `scene.availableActionCode[]` | `action.actionCode` |
| `scene.availableNpcCode[]` | `npc.npcCode` |

添加或重命名编码时，需要同步修改表中所有引用位置。显示名称可以独立调整，不应被业务逻辑当作引用键。
