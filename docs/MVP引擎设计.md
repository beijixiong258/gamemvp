# 古代穿越人生模拟游戏 MVP 引擎设计

> 本文只定义 MVP 的数值计算、行动流程和智能体 Resolver 编排。玩法边界见[《MVP设计文档》](./MVP设计文档.md)，表结构见[《MVP数据模型设计》](./MVP数据模型设计.md)。

## 一、引擎边界

Java 引擎把当前状态与行动输入结算成新的确定状态。相同输入必须得到相同输出。

- `CharacterEngine`：开始人生、读书、练习文章、休息、自由行动驱动量。
- `TurnEngine`：回合、月份、年份、年龄和考试节点。
- `ExamEngine`：考试准备、角色思路层级和最终成绩。
- Resolver：理解自然语言，生成结构化驱动量或叙事。
- Service：读取数据、组织调用并保存结果。

Engine 不访问数据库、不调用模型、不管理事务，也不直接生成叙事。Resolver 不推进时间、不直接写最终属性或考试结果。

## 二、统一数值规则

### 1. 范围与精度

| 数值 | 范围 | 持久化精度 |
|---|---:|---:|
| 五项通用属性 | 0～100 | 整数 |
| 五项书生能力 | 0～100 | 整数 |
| 健康、疲劳 | 0～100 | 整数 |
| 单本书阅读进度 | 0～100 | 整数 |
| 初始家庭财富 | 10000～1000000 | 整数，单位为文 |
| 考试各项分数 | 0～100 | 整数 |

中间计算使用 `BigDecimal`，写入整数状态前统一按 `HALF_UP` 四舍五入。所有公式都不使用不确定项。

```text
clamp(min, max, value) = min(max, max(min, value))
```

`clamp` 的含义是把值限制在闭区间内。例如 `clamp(0, 100, 120) = 100`，`clamp(0, 100, -3) = 0`。

### 2. 结算顺序

一次结算遵循固定顺序：

```text
读取当前整数状态
  ↓
完成中间计算
  ↓
按HALF_UP取整
  ↓
按字段范围clamp
  ↓
返回After状态与实际差值
```

实际差值以收束后的状态为准。例如能力已经是 100，即使原始增长为 2，最终实际增长仍是 0。

## 三、开始人生

玩家先选择出生地，再点击一次“开始人生”。引擎不建立 0～5 岁逐年回合，而是直接返回六岁时的可玩状态。

```text
出生年份 = 1541
当前年份 = 1547（嘉靖二十六年）
当前月份 = 1
年龄 = 6
月内回合 = 1
累计结束回合数 = 0
成长阶段 = STUDYING

智力、道德、政治、交际、体能 = 20
健康 = 75
疲劳 = 0

识字 = 5
经义、文章、策论、文学 = 0

初始家庭财富 = 100000文
家庭背景摘要 = 家中有一定田产和积蓄，能够供角色入塾读书
出生地区ID = 玩家选择
当前地区ID = 出生地区ID
```

MVP 出生地由 Service 查询惠州 `CITY` 节点的 `COUNTY` 子节点，因此当前只有博罗县和海丰县。行政区本身不保存玩法专用的出生地标记。

当前 `GameSaveService.startLife` 已实现这段用例：先校验出生地，再分别调用 `CharacterEngine.startLife` 和 `TurnEngine.startLife`，最后在同一事务中保存存档、玩家、初始家庭背景、书生职业及书生能力档案。该流程不调用模型，也不产生随机值。

0～5 岁只形成一段童年摘要，不参与属性计算，不产生普通回合。

## 四、人物状态公式

### 1. 智力效率

```text
intelligenceFactor = 0.75 + 0.50 × 智力 / 100
```

属性范围为 0～100，因此该因子范围为 0.75～1.25。

### 2. 身体状态效率

```text
conditionFactor
= clamp(
    0.55,
    1.20,
    0.65
    + 0.003 × 健康
    + 0.002 × 体能
    - 0.003 × 疲劳
  )
```

### 3. 疲劳增长

```text
fitnessCostFactor = 1.15 - 体能 / 200
lowHealthFactor = 1 + max(0, 60 - 健康) / 100

fatigueGain
= max(
    1,
    round(行动基础疲劳 × fitnessCostFactor × lowHealthFactor)
  )
```

行动基础疲劳不大于 0 时，疲劳增长直接为 0。

### 4. 过劳伤害

```text
exhaustionDamage
= fatigueAfter <= 80
  ? 0
  : ceil((fatigueAfter - 80) / 10)
```

读书、练习文章或自由行动增加疲劳后执行该计算；休息不触发过劳伤害。

## 五、固定行动

### 1. 读书 `READ_BOOK`

#### 阅读进度

```text
readingFoundation = 0.40 × 智力 + 0.60 × 识字

difficultyFactor
= clamp(
    0.60,
    1.20,
    1 + (readingFoundation - 书籍难度) / 100
  )

studyAmount
= 单回合基础进度
  × intelligenceFactor
  × conditionFactor
  × difficultyFactor

progressGain
= min(
    完成所需进度 - 当前进度,
    max(0, round(studyAmount))
  )

progressAfter
= clamp(0, 完成所需进度, 当前进度 + progressGain)
```

阅读进度与单回合增长都使用整数。书已经读完时，进度不再增加，但仍可温习。

#### 能力成长

```text
weightedAbility
= Σ(某项书生能力 × 书籍对应权重)

diminishingFactor
= clamp(0.20, 1.00, 1 - weightedAbility / 120)

reviewFactor = 当前进度已完成 ? 0.35 : 1.00

learningPool
= (0.50 + 0.20 × studyAmount)
  × diminishingFactor
  × reviewFactor

某项能力增长
= round(learningPool × 该项权重)
```

五项能力分别取整和收束。低于 0.5 的单次原始增长会取为 0，需通过书籍基础进度和能力权重调整成长速度。

#### 阅读记录

每次阅读后：

- `totalReadTurnNumber` 加 1。
- `lastReadTurnNumber` 写入本次行动推进后的总回合编号。
- 首次跨过 60 产生“已可用”节点。
- 首次达到 100 产生“已掌握”节点并设置 `completed=true`。

### 2. 练习文章 `PRACTICE_WRITING`

```text
writingDiminishing
= clamp(0.25, 1.00, 1 - 文章能力 / 120)

rawGain
= 2.40
  × intelligenceFactor
  × conditionFactor
  × writingDiminishing

文章能力增长 = max(0, round(rawGain))
```

基础疲劳为 4，再套用通用疲劳公式。该行动只直接训练文章能力。

### 3. 休息 `REST`

```text
fatigueRecovery = 18 + round(体能 × 0.10)
fatigueAfter = max(0, 疲劳 - fatigueRecovery)

healthRecovery
= 3
  + floor(体能 / 25)
  + (休息前疲劳 >= 70 ? 1 : 0)

healthAfter = min(100, 健康 + healthRecovery)
```

休息结束一个回合，不增加人物属性或书生能力。

## 六、自由行动

Resolver 把玩家原文转换为 `DriverPatch`：

| 驱动量 | 目标 |
|---|---|
| `attribute_intelligence_gain` | 智力 |
| `attribute_morality_gain` | 道德 |
| `attribute_politics_gain` | 政治 |
| `attribute_social_gain` | 交际 |
| `attribute_fitness_gain` | 体能 |
| `ability_shizi_gain` | 识字 |
| `ability_jingyi_gain` | 经义 |
| `ability_wenzhang_gain` | 文章 |
| `ability_celun_gain` | 策论 |
| `ability_wenxue_gain` | 文学 |
| `fatigue_offset` | 疲劳 |
| `health_offset` | 健康 |

Engine 负责最终结算：

```text
属性After = clamp(0, 100, 属性Before + round(gain))
能力After = clamp(0, 100, 能力Before + round(gain))
疲劳After = clamp(0, 100, 疲劳Before + round(fatigueOffset))
健康After = clamp(0, 100, 健康Before + round(healthOffset) - exhaustionDamage)
```

影响分用于判断是否值得形成长期事件：

```text
impactScore
= 4 × Σ|通用属性变化|
  + 4 × Σ|书生能力变化|
  + 0.5 × |健康变化|
  + 0.25 × |疲劳变化|
```

事件摘要必须基于已经结算的实际差值。

## 七、回合与阶段

### 1. 时间推进

`totalTurnNumber` 表示已完成的结束回合行动数；`turnInMonth` 表示即将进行的月内回合，取 1～4。

```text
endTurn = false:
  时间不变

endTurn = true:
  totalTurnNumber += 1

  if turnInMonth < 4:
    turnInMonth += 1
  else:
    turnInMonth = 1
    currentMonth += 1

  if currentMonth > 12:
    currentMonth = 1
    currentYear += 1
    age = currentYear - birthYear
```

月份变化不自动增加收入、恢复健康或推动 NPC。

### 2. 考试节点

| 年龄 | 考试编码 | 名称 | 通过线 | 结束人生阶段 |
|---:|---|---|---:|---:|
| 8 | `EXAM_MENGXUE` | 蒙学阶段考 | 35 | 否 |
| 12 | `EXAM_JINGYI` | 经义阶段考 | 45 | 否 |
| 15 | `EXAM_PRE_COUNTY` | 县试预考 | 52 | 否 |
| 16 | `EXAM_XIANSHI` | 县试 | 60 | 是 |

跨年后年龄命中考试节点时，`TurnEngine` 暂停普通成长行动。前三次考试无论通过或未通过，结算后都恢复 `STUDYING`；十六岁县试结算后存档进入 `COMPLETED`。

从六岁第一回合到四次考试的累计结束回合数分别为：

```text
8岁：96
12岁：288
15岁：432
16岁：480
```

考试本身不额外推进普通月内回合。

## 八、考试数值

### 1. 题目

每个考试类型在 `exam.json` 中只配置一道题，进入节点后直接使用该题。题目原文和分数线写入考试记录，结算期间不再改变。

### 2. 已学知识值 `K`

每本书按整数阅读进度转换为阶段分：

| 进度 | 阶段分 |
|---:|---:|
| `< 25` | 0 |
| `25～59` | 35 |
| `60～99` | 70 |
| `100` | 100 |

取最高的三本书，缺少位置按 0：

```text
K = round(0.50 × K1 + 0.30 × K2 + 0.20 × K3)
```

### 3. 基础能力 `B`

题目配置五项通用属性、五项书生能力与 `K` 的权重，全部权重合计为 1：

```text
B
= round(
    Σ(通用属性 × 对应权重)
    + Σ(书生能力 × 对应权重)
    + K × knowledgeWeight
  )

B = clamp(0, 100, B)
```

### 4. 临场状态偏移 `R`

```text
healthModifier = (健康 - 70) × 0.06
fatigueModifier = -max(0, 疲劳 - 20) × 0.08
fitnessModifier = (体能 - 50) × 0.03

R
= round(
    clamp(
      -8,
      4,
      healthModifier + fatigueModifier + fitnessModifier
    )
  )
```

`R` 只反映当时身体状态，保存为 `exam_record.state_offset`。

### 5. 思维泡泡

引擎按 `B + R` 给出层级：

| 分数 | 层级 |
|---:|---|
| `< 35` | `DIFFICULT` |
| `35～59` | `BASIC` |
| `60～79` | `ORGANIZED` |
| `>= 80` | `CONFIDENT` |

Resolver 只接收该层级、题目和角色已达到相应进度的知识摘要，生成提示思路，不生成标准答案。

### 6. 两种结算

系统代行：

```text
finalScore = clamp(0, 100, B + R)
```

以身入局时，Resolver 只评价玩家答案的内容贡献 `M`：

```text
effectiveM = clamp(-25, 25, round(M))
finalScore = clamp(0, 100, B + R + effectiveM)
```

```text
passed = finalScore >= passThreshold
```

最终分数、`M` 与分数线均按整数保存。Resolver 生成的答卷、评价和结果叙事不反向修改成绩。

## 九、Resolver 编排

### 1. 固定行动

```text
Service读取人物、职业及内容配置
  ↓
直接调用CharacterEngine
  ↓
按endTurn调用TurnEngine
  ↓
短事务保存After状态、事件和时间
```

读书、练习文章和休息不调用模型。

### 2. 自由行动

```text
加载当前人物、初始家庭背景、场景和少量相关记忆
  ↓
FreeActionResolver输出DriverPatch与事件语义
  ↓
CharacterEngine计算实际状态变化
  ↓
MemoryResolver按已发生事实生成必要记忆
  ↓
短事务保存状态、事件、记忆和时间
```

当前第一阶段已经落地的 LangGraph 只覆盖一次模型解析和一次确定性结算：

```text
START
  ↓
resolve_action：FreeActionResolver生成DriverPatch和事件语义
  ↓
settle_driver：CharacterEngine.applyDriver结算最终数值
  ↓
END
```

入口为 `FreeActionWorkflow.execute`。图状态只保存本次调用需要的输入快照、Resolver 输出和结算结果；数据库读取、记忆生成、时间推进与持久化仍留给后续 Service 编排，不属于当前这张图。

### 3. NPC 对话

```text
加载NPC公开设定、当前场景、共同事件和相关记忆
  ↓
NpcDialogueResolver生成回应与重要事件语义
  ↓
普通回应直接返回；重要事件保存事件与记忆
```

NPC 对话默认 `endTurn=false`，也不直接改变数值。需要改变知识或物品时，应转成后续明确行动。

### 4. 考试

```text
ExamEngine.prepare计算K、B、R和思维层级
  ↓
ExamThoughtResolver生成角色思维泡泡
  ↓
保存READY考试记录
  ↓
玩家选择系统代行或以身入局
  ├─ 系统代行：ExamEngine.settleAuto
  └─ 以身入局：ExamEvaluationResolver给出M，再调用settlePlayer
  ↓
引擎固定分数与通过状态
  ↓
Resolver按结果生成展示文本
  ↓
短事务保存考试结果并恢复求学或完成存档
```

模型调用放在事务外；同一存档的操作按顺序执行。

## 十、实现入口

当前基础引擎入口为：

```text
CharacterEngine.startLife
CharacterEngine.readBook
CharacterEngine.practiceWriting
CharacterEngine.rest
CharacterEngine.applyDriver

TurnEngine.startLife
TurnEngine.advance
TurnEngine.completeExam
TurnEngine.examTypeAtAge
TurnEngine.jiajingYear

ExamEngine.prepare
ExamEngine.settleAuto
ExamEngine.settlePlayer

FreeActionWorkflow.execute
FreeActionResolver.resolve
GameClient.chat
```

三个 Engine 只表达规则；`FreeActionWorkflow` 已负责自由行动中 Resolver 与人物引擎的两节点编排。`GameSaveService` 已完成“开始人生”的输入校验、快照转换和事务持久化；读书、考试等后续用例仍由相应 Service 继续承接数据库、时间与记忆编排。
