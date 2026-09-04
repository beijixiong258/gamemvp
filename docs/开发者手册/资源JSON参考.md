# 资源 JSON 参考

> 本文定义 `src/main/resources` 中现有 JSON 配置的结构和引用关系。数值公式与流程语义见[《MVP 引擎设计》](../MVP引擎设计.md)。`prompt/prompt.json` 已接入运行时加载，其余文件暂时只作为配置契约；当前实现没有资源内容校验层。

## 一、文件总览

| 文件 | 根数组 | 当前条目数 | 用途 |
|---|---|---:|---|
| [`game/action.json`](../../src/main/resources/game/action.json) | `action` | 7 | 行动入口和路由元数据 |
| [`game/equipment.json`](../../src/main/resources/game/equipment.json) | `equipment` | 7 | 通用装备定义 |
| [`game/book.json`](../../src/main/resources/game/book.json) | `book` | 7 | 书籍专属规则 |
| [`game/exam.json`](../../src/main/resources/game/exam.json) | `exam` | 4 | 考试节点、题目和计分权重 |
| [`game/npc.json`](../../src/main/resources/game/npc.json) | `npc` | 5 | 固定 NPC 原型 |
| [`game/scene.json`](../../src/main/resources/game/scene.json) | `scene` | 3 | 场景及可用内容 |
| [`game/text.json`](../../src/main/resources/game/text.json) | `text` | 6 | 固定反馈文本模板 |
| [`prompt/prompt.json`](../../src/main/resources/prompt/prompt.json) | `prompt` | 6 | Resolver 系统提示词 |

地区定义不在 JSON 中，当前保存在 `db/schema.sql` 的 `region_definition` 初始化数据里。

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
| `engineRuleCode` | `string` | 是 | 业务层选择确定性结算路径的规则编码 |
| `feedbackTextCode` | `string` | 条件 | 固定行动完成后使用的 `text.json` 模板编码 |
| `promptCode` | `string` | 条件 | 需要模型参与时使用的 `prompt.json` 编码 |
| `endTurn` | `boolean` | 是 | 该选择是否完成当前行动回合；普通行动据此推进时间，考试行动由考试流程单独处理 |

`actionType` 当前取值：

| 值 | 含义 |
|---|---|
| `FIXED_ACTION` | 完全由 Java 引擎结算 |
| `FREE_ACTION` | Resolver 解析自然语言，再由 Java 应用驱动量 |
| `NPC_DIALOGUE` | NPC 对话，默认不结束回合 |
| `EXAM_ACTION` | 考试答题路径 |

`requiredParameter` 当前使用 `bookCode`、`npcCode` 和 `text`。这些名称是请求层与业务编排层的字段契约。

`EXAM_AUTO` 和 `EXAM_PLAYER` 当前也配置为 `endTurn=true`，表示完成已经触发的考试选择。考试节点由此前的普通行动推进时间后触发，结算考试时不能再次调用 `TurnEngine.advance`，否则会多推进一个普通回合。

### 规则编码与方法

| `engineRuleCode` | 主要处理入口 |
|---|---|
| `RULE_READ_BOOK` | `CharacterEngine.readBook` |
| `RULE_PRACTICE_WRITING` | `CharacterEngine.practiceWriting` |
| `RULE_REST` | `CharacterEngine.rest` |
| `RULE_DRIVER` | 自由行动 Resolver，再调用 `CharacterEngine.applyDriver` |
| `RULE_NPC_DIALOGUE` | NPC 对话 Resolver；默认不进行数值结算 |
| `RULE_EXAM_AUTO` | `ExamEngine.settleAuto` |
| `RULE_EXAM_PLAYER` | 考试评价 Resolver，再调用 `ExamEngine.settlePlayer` |

## 四、`equipment.json`

根结构为 `{ "equipment": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `equipmentCode` | `string` | 装备唯一编码，也是其他文件引用装备的键 |
| `equipmentName` | `string` | 显示名称 |
| `equipmentType` | `string` | 装备类型；当前只有 `BOOK` |
| `rarityCode` | `string` | 稀有度；当前有 `COMMON`、`UNCOMMON`、`RARE` |
| `price` | `int` | 整数价格，单位为文 |
| `description` | `string` | 基础介绍，不包含人物使用状态 |

书籍必须先在本文件中具有通用装备定义，再由 `book.json` 使用同一个 `equipmentCode` 增加书籍规则。

## 五、`book.json`

根结构为 `{ "book": [] }`。

### 书籍字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `equipmentCode` | `string` | 对应 `equipment.json.equipmentCode` |
| `applicableCareerCode` | `string` | 可使用该书的职业编码；当前为 `CAREER_SHUSHENG` |
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
| `knowledgeChushiSummary` | `string` | 阅读进度达到 `25` 后可使用的初识摘要 |
| `knowledgeKeyongSummary` | `string` | 阅读进度达到 `60` 后可使用的可用摘要 |
| `knowledgeZhangwoSummary` | `string` | 阅读进度达到 `100` 后可使用的掌握摘要 |

同一本书的五项能力权重按百分比配置，合计应为 `100`。`readingRequirement` 中的多项条件按同时满足理解。

### 阅读条件字段

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `type` | `string` | 是 | 条件类型 |
| `target` | `string` | 条件 | 被检查的字段、职业或书籍编码 |
| `value` | `int` | 条件 | 最低年龄、属性、能力或阅读进度 |

| `type` | `target` | `value` | 语义 |
|---|---|---|---|
| `MIN_AGE` | 不使用 | 必填 | 当前年龄至少为 `value` |
| `CURRENT_CAREER` | 职业编码 | 不使用 | 当前主职业等于 `target` |
| `MIN_GENERAL_ATTRIBUTE` | `CharacterState` 字段名 | 必填 | 对应通用属性至少为 `value` |
| `MIN_CAREER_ABILITY` | `ScholarState` 字段名 | 必填 | 对应书生能力至少为 `value` |
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

五项通用属性权重、五项职业能力权重与 `knowledgeWeight` 合计应为 `1.00`。权重传给 `ExamEngine.ExamWeights` 时使用 `BigDecimal`。

## 七、`npc.json`

根结构为 `{ "npc": [] }`。

| 字段 | 类型 | 含义 |
|---|---|---|
| `npcCode` | `string` | NPC 原型唯一编码 |
| `displayName` | `string` | 尚未生成正式姓名时使用的显示名称 |
| `roleCode` | `string` | 身份角色编码 |
| `nameMode` | `string` | 姓名来源；当前为 `GENERATED` |
| `defaultSceneCode` | `string` | 默认所在场景编码 |
| `personalitySummary` | `string` | 提供给 NPC 对话 Resolver 的稳定性格摘要 |
| `enabled` | `boolean` | 是否启用该原型 |

本文件定义 NPC 原型，不保存某局中的关系、记忆或临时状态。

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
| `FREE_ACTION` | 自由行动的结构化驱动量和事件语义 |
| `NPC_DIALOGUE` | NPC 回应及允许的事件语义 |
| `MEMORY_SUMMARY` | 基于已发生事件的简短记忆 |
| `EXAM_THOUGHT` | 不包含完整答案的作答思路 |
| `EXAM_EVALUATION` | 玩家答案的内容修正和事实评价 |
| `EXAM_ANSWER` | 与 Java 已定成绩一致的答卷表现和结果叙事 |

`systemText` 只定义稳定边界。人物快照、题目、场景、记忆等动态内容应由调用方在每次调用时组装，不应回写进静态文件。

运行时由 `mvp.ai.GameClient` 通过 `ClasspathJsonLoader` 一次加载并按 `promptCode` 建立只读索引。底层读取和反序列化使用项目已有的 Hutool `ResourceUtil` 与 `JSONUtil`；目前 `FreeActionResolver` 已使用 `PROMPT_FREE_ACTION`，其余提示词等待对应 Resolver 接入。

## 十一、跨文件引用

| 来源字段 | 目标字段 |
|---|---|
| `action.availableSceneCode[]` | `scene.sceneCode` |
| `action.feedbackTextCode` | `text.textCode` |
| `action.promptCode` | `prompt.promptCode` |
| `equipment.equipmentCode` | `book.equipmentCode` |
| `book.readingRequirement[].target`（`BOOK_PROGRESS`） | `book.equipmentCode` |
| `exam.sceneCode` | `scene.sceneCode` |
| `exam.*PromptCode` | `prompt.promptCode` |
| `npc.defaultSceneCode` | `scene.sceneCode` |
| `scene.availableActionCode[]` | `action.actionCode` |
| `scene.availableNpcCode[]` | `npc.npcCode` |

添加或重命名编码时，需要同步修改表中所有引用位置。显示名称可以独立调整，不应被业务逻辑当作引用键。
