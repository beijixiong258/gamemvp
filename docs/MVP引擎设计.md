# 古代穿越人生模拟游戏 MVP 引擎设计

> 本文定义 MVP 阶段的确定性数值计算、行动流程和智能体 Resolver 编排。玩法范围见[《MVP设计文档》](./MVP设计文档.md)，持久化结构见[《MVP数据模型设计》](./MVP数据模型设计.md)。当前版本以调用方输入、内容配置和 Resolver 结果均符合约定为前提。

## 一、设计目标

引擎负责把“行动产生的变化”结算为新的世界事实。无论变化来自固定行动还是 AI Resolver，最终状态都只能由 Java 引擎计算。

MVP 的核心链路是：

```text
玩家操作
  ↓
Service读取当前快照和内容配置
  ↓
固定行动直接生成驱动量 / 自由行动交给Resolver解释
  ↓
Engine计算状态差异、事件和时间结果
  ↓
Service在短事务中保存
```

引擎需要满足以下特征：

- 相同输入和相同随机种子始终得到相同结果。
- 不查询数据库，不调用模型，不读取 HTTP 请求。
- 不开启事务，不直接保存 Entity。
- 不生成玩家或 NPC 的自由叙事文本。
- 输入是只读状态快照，输出是新的状态值和需要保存的事实。
- 公式只依赖明确字段，不从自然语言摘要反推数值。

## 二、组件职责

### 1. `CharacterEngine`

负责人物与家庭相关的数值计算：

- 出生属性与家庭财富初始化。
- 0～5 岁快速成长。
- 读书、练习文章和休息。
- 把 Resolver 返回的驱动量作用到人物、职业和家庭状态。
- 计算疲劳、健康和能力成长。
- 根据结算结果产生明确的人生节点候选。

### 2. `TurnEngine`

负责游戏时间和阶段变化：

- 判断本次行动是否结束回合。
- 推进月内回合、月份、年份、年龄和总回合数。
- 在六岁进入求学阶段。
- 在十六岁进入县试阶段。
- 为本次结算提供统一的事件回合编号。

### 3. `ExamEngine`

负责县试的全部确定性数值：

- 选择题目。
- 计算角色基础能力 `B`。
- 生成并固定综合偏移 `R`。
- 根据 `B + R` 结算系统代行。
- 根据 `B + M + R` 结算以身入局。
- 计算思维泡泡层级、最终分数和通过状态。

### 4. Resolver

Resolver 负责自然语言和语义工作：

- 理解自由行动并输出驱动量。
- 根据 NPC 设定、事实和记忆生成回应。
- 根据角色已有知识生成县试思维泡泡。
- 评价玩家手写答案并给出内容修正 `M`。
- 根据已经确定的结算事实生成事件记忆、答卷和结果叙事。

Resolver 不推进时间、不保存数据库、不计算最终属性，也不决定考试是否通过。

### 5. Service

Service 是用例编排层，负责读取 Entity、组装引擎输入、调用 Resolver、调用 Engine，并在短事务中保存状态。Controller 只接收请求和返回 VO。

## 三、统一数值规则

### 1. 数值范围

| 数值 | 范围 | 存储精度 |
|---|---:|---:|
| 五项人物通用属性 | 0～100 | 整数 |
| 五项书生能力 | 0～100 | 整数 |
| 健康 | 0～100 | 整数 |
| 疲劳 | 0～100 | 整数 |
| 单本书阅读进度 | 0～100 | 两位小数 |
| 家庭财富 | 不低于 0 | 整数，单位为文 |
| 考试分数 | 0～100 | 两位小数 |
| 中间计算值 | 不预先截断 | 四位小数 |

统一使用：

```text
clamp(min, max, value) = min(max, max(min, value))
```

一次结算先完成全部中间计算，再按照目标字段的精度取值，最后执行范围收束。小数使用 `BigDecimal`，普通四舍五入使用 `RoundingMode.HALF_UP`。

### 2. 整数能力的概率取整

人物属性和职业能力以整数保存，但学习公式会产生小数。为了让较小权重长期仍然有效，能力增长采用基于存档种子的确定性概率取整。

```text
x = 原始增长绝对值
整数部分 = floor(x)
小数部分 = x - floor(x)

当本次随机值 u < 小数部分时：取整结果 = 整数部分 + 1
否则：取整结果 = 整数部分

原始增长为负数时，最后恢复负号
```

例如原始增长为 `0.35`，并不是永远舍弃，而是在长期重复行动中以 35% 的确定性随机机会增长 1 点。因为随机值由存档和回合派生，同一局游戏重放时结果不变。

健康、疲劳和财富变化不使用概率取整，直接四舍五入为整数。

### 3. 可重放随机数

不在存档中维护一个不断消费的随机游标。每一个需要随机结果的位置都使用稳定上下文派生自己的种子：

```text
contextSeed
= save.randomSeed
 ^ stableHash64(purposeCode)
 ^ stableHash64(actionCode)
 ^ rotateLeft(totalTurnNumber, 17)
 ^ stableHash64(targetCode)
```

`stableHash64` 对 UTF-8 字节使用固定的 FNV-1a 64 位算法。`purposeCode` 用于区分同一行动里的不同随机槽，例如识字增长、经义增长和疲劳波动各自使用不同值。

`contextSeed` 再经过固定的 SplitMix64 混合：

```text
z = contextSeed + 0x9E3779B97F4A7C15
z = (z ^ (z >>> 30)) × 0xBF58476D1CE4E5B9
z = (z ^ (z >>> 27)) × 0x94D049BB133111EB
z = z ^ (z >>> 31)

u = (z >>> 11) × 2^-53
```

`u` 位于 `[0, 1)`。闭区间整数随机值按以下方式取得：

```text
randomInt(min, max) = min + floor(u × (max - min + 1))
```

考试题目、考试偏移、出生属性、童年成长和固定行动波动使用不同的 `purposeCode`，彼此不会因为调用顺序改变结果。

## 四、出生与童年快速成长

### 1. 出生属性

玩家的五项通用属性分别独立生成：

```text
初始属性 = 45 + randomInt(-12, 12)
```

因此普通出生属性落在 33～57。五项属性使用不同随机槽，不共享同一个随机值。

初始状态为：

```text
健康 = clamp(60, 100, 70 + round(体能 × 0.25) + randomInt(-5, 5))
疲劳 = 0
当前主职业 = CAREER_PINGMIN
年龄 = 0
总回合数 = 0
月内回合 = 0
```

### 2. 家庭财富

家庭档次只在出生初始化过程中使用，最终以财富和背景摘要保存，不增加新的持久化字段。

| 随机区间 | 家庭情况 | 初始财富 |
|---:|---|---:|
| `[0.00, 0.15)` | 困顿 | 300～699 文 |
| `[0.15, 0.75)` | 普通 | 700～1599 文 |
| `[0.75, 0.95)` | 宽裕 | 1600～3499 文 |
| `[0.95, 1.00)` | 富足 | 3500～6999 文 |

档次决定背景摘要模板和区间，区间内金额继续使用派生随机数生成。书籍价格不会反向修改家庭出生档次。

### 3. 0～5 岁阶段结算

童年不运行普通行动回合，只依次结算三个阶段：

| 阶段 | 年龄 | 属性变化 |
|---|---:|---|
| 婴幼儿 | 0～1 | 体能 `+randomInt(0,2)` |
| 幼年 | 2～3 | 智力 `+randomInt(0,2)`，交际 `+randomInt(0,2)`，体能 `+randomInt(0,1)` |
| 入学前 | 4～5 | 智力 `+randomInt(1,3)`，道德 `+randomInt(0,2)`，政治 `+randomInt(0,1)`，交际 `+randomInt(0,2)` |

每个阶段另有一次健康变化：

```text
健康变化 = randomInt(-2, 3)
```

完成第三阶段后进入六岁：

```text
年龄 = 6
当前年份 = 出生年份 + 6
当前月份 = 1
月内回合 = 1
总回合数 = 0
当前主职业 = CAREER_SHUSHENG
识字 = randomInt(3, 7)
经义、文章、策论、文学 = 0
```

出生、三个童年阶段和入学分别形成固定人生节点。固定 NPC、初始书籍使用权和职业记录由创建存档用例一并建立，不属于数值公式。

## 五、人物状态辅助公式

### 1. 智力效率

```text
intelligenceFactor = 0.75 + 0.50 × 智力 / 100
```

范围为 `0.75～1.25`。

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

健康和体能提高行动效率，疲劳降低行动效率。该值用于读书和练习文章。

### 3. 劳累产生的疲劳

```text
fitnessCostFactor = 1.15 - 体能 / 200
lowHealthFactor = 1 + max(0, 60 - 健康) / 100

fatigueGain
= max(
    1,
    round(行动基础疲劳 × fitnessCostFactor × lowHealthFactor)
  )
```

行动基础疲劳大于零时，最终至少增加 1 点疲劳。

### 4. 过度疲劳伤害

读书、练习文章或自由行动结算后，如果疲劳增加并且最终疲劳超过 80，则产生健康损失：

```text
exhaustionDamage
= fatigueAfter <= 80
   ? 0
   : ceil((fatigueAfter - 80) / 10)
```

疲劳 81～90 损失 1 点健康，91～100 损失 2 点健康。休息不触发该损失。

## 六、固定行动数值

### 1. 读书 `READ_BOOK`

读书输入包含人物快照、书生能力、书籍配置、当前阅读进度和本次随机上下文。

#### 阅读进度

先计算阅读基础：

```text
readingFoundation = 0.40 × 智力 + 0.60 × 识字

difficultyFactor
= clamp(
    0.60,
    1.20,
    1 + (readingFoundation - 书籍难度) / 100
  )

randomFactor = 0.95 + 0.10 × u

studyAmount
= 书籍单回合基础进度
 × intelligenceFactor
 × conditionFactor
 × difficultyFactor
 × randomFactor
```

未读完时：

```text
progressGain
= min(
    100 - 当前进度,
    round2(studyAmount)
  )
```

已经读完时进度不再增加，但仍可温习并获得较低的能力成长。

#### 能力成长

先按书籍五项权重计算当前加权能力：

```text
weightedAbility
= 识字 × 识字权重
 + 经义 × 经义权重
 + 文章 × 文章权重
 + 策论 × 策论权重
 + 文学 × 文学权重
```

权重在计算时由百分数换算为 `0～1`。

```text
diminishingFactor
= clamp(0.20, 1.00, 1 - weightedAbility / 120)

reviewFactor = 当前进度达到100 ? 0.35 : 1.00

learningPool
= (0.50 + 0.20 × studyAmount)
 × diminishingFactor
 × reviewFactor

某项能力原始增长 = learningPool × 该项权重
```

各项能力原始增长分别使用确定性概率取整。这样低权重能力可以缓慢成长，同时高能力人物会出现明显的边际递减。

#### 疲劳与记录

疲劳按照书籍的 `fatigueCost` 和通用疲劳公式计算。完成一次读书后：

- `totalReadTurnNumber + 1`。
- `lastReadTurnNumber` 记录本次结束后的总回合编号。
- 进度首次达到 60 时形成“已可用”节点。
- 进度首次达到 100 时设置 `completed=true`，并形成“已掌握”节点。

初识阶段 25 只改变知识摘要可见范围，不形成长期人生节点。

### 2. 练习文章 `PRACTICE_WRITING`

练习文章只直接增加文章能力：

```text
writingDiminishing
= clamp(0.25, 1.00, 1 - 文章能力 / 120)

randomFactor = 0.90 + 0.20 × u

rawWenzhangGain
= 2.40
 × intelligenceFactor
 × conditionFactor
 × writingDiminishing
 × randomFactor
```

`rawWenzhangGain` 使用确定性概率取整。基础疲劳固定为 4，再通过通用疲劳公式得到实际疲劳增长。

练习文章不自动增加文学或策论。玩家希望专门练习辞藻、策论或其他内容时，使用自由行动。

### 3. 休息 `REST`

休息先降低疲劳，再恢复健康：

```text
fatigueRecovery
= 16
 + round(体能 × 0.10)
 + randomInt(0, 4)

fatigueAfter = max(0, 疲劳 - fatigueRecovery)

healthRecovery
= 3
 + floor(体能 / 25)
 + (休息前疲劳 >= 70 ? 1 : 0)

healthAfter = min(100, 健康 + healthRecovery)
```

休息结束回合，不增加人物能力。

## 七、自由行动驱动量

自由行动由 Resolver 理解玩家原文，输出已有驱动量。MVP 使用以下统一编码：

| 驱动量 | 目标状态 |
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
| `family_wealth_offset` | 家庭财富，单位为文 |

引擎把驱动量转成状态差异：

```text
人物属性After = clamp(0, 100, 人物属性Before + 概率取整(gain))
职业能力After = clamp(0, 100, 职业能力Before + 概率取整(gain))
疲劳After     = clamp(0, 100, 疲劳Before + round(fatigue_offset))
健康After     = clamp(0, 100, 健康Before + round(health_offset) - exhaustionDamage)
家庭财富After = max(0, 家庭财富Before + round(family_wealth_offset))
```

自由行动当前配置为 `endTurn=true`。它的状态变化、事件、记忆和时间推进作为一次完整结算保存。

### 自由行动的重要性分数

自由行动是否形成重大事件可以由数值影响和语义类型共同决定。数值影响分数为：

```text
impactScore
= 4 × Σ|人物通用属性变化|
 + 4 × Σ|职业能力变化|
 + 0.5 × |健康变化|
 + 0.25 × |疲劳变化|
 + |财富变化| / 100
```

以下任一条件成立时形成事件：

- `impactScore >= 12`。
- Resolver 把行为归为承诺、冲突、帮助、学习路线选择或县试准备。
- 行动同时触发入学、能力满值、健康危险等固定人生节点。

事件是否属于 `lifeMilestone` 由事件类型决定。事件摘要使用已经发生的结算事实，不保存玩家仅仅打算做但没有发生的内容。

## 八、NPC 对话

NPC 对话在 MVP 中是非结束回合操作。为了避免玩家在同一回合无限对话获取数值，普通对话不直接产生人物属性、职业能力、健康、疲劳或财富变化。

NPC Resolver 返回：

- NPC 当次回应。
- 对玩家行为和语气的事实性概括。
- 是否出现承诺、冲突、帮助或其他重要语义事件。
- 与事件相关的人物 ID。

普通回应只返回前端，不写操作流水。如果形成重要事件，则保存事件，并为相关人物生成记忆；`turnNumber` 保持不变。对话产生的知识启发、获赠书籍或财富变化应转成一个明确的后续行动，再由对应行动结算。

## 九、回合与时间推进

### 1. 回合字段语义

- `totalTurnNumber` 表示已经完成的结束回合行动数量。
- `turnInMonth` 表示玩家当前将要进行的月内回合，正常阶段为 1～4。
- 非结束回合操作不改变两个字段。
- 结束回合行动产生的事件使用推进后的 `totalTurnNumber`。

### 2. 普通推进公式

当行动的 `endTurn=false`：

```text
时间状态保持不变
```

当行动的 `endTurn=true`：

```text
totalTurnNumber = totalTurnNumber + 1

if turnInMonth < 4:
    turnInMonth = turnInMonth + 1
else:
    turnInMonth = 1
    currentMonth = currentMonth + 1

if currentMonth > 12:
    currentMonth = 1
    currentYear = currentYear + 1
    age = currentYear - birthYear
```

当年龄从 15 进入 16 时：

```text
status = EXAM_READY
growthStage = EXAM
```

此后不再接受普通成长行动。六岁第一回合开始到十六岁县试前恰好完成：

```text
10年 × 12月 × 4回合 = 480个结束回合行动
```

### 3. 月份与年龄变化

MVP 不增加月度被动收入、自动恢复或后台 NPC 行动。月份变化只改变日历；所有人物数值变化都来自明确行动、童年快速结算或县试。

## 十、县试数值

### 1. 题目选择

考试类型固定为 `EXAM_XIANSHI`。当配置中只有一道题时直接使用；出现多道题时，根据：

```text
purposeCode = EXAM_QUESTION
targetCode = examType
```

生成稳定下标。题目在考试创建后写入 `exam_record`，之后不再重新选择。

### 2. 已掌握知识值 `K`

每本书按照当前进度得到一个阶段分：

| 阅读进度 | 阶段分 |
|---:|---:|
| `< 25` | 0 |
| `25～不足60` | 35 |
| `60～不足100` | 70 |
| `100` | 100 |

把所有书籍阶段分从高到低排列为 `K1、K2、K3...`，缺少的位置按 0 处理：

```text
K = 0.50 × K1 + 0.30 × K2 + 0.20 × K3
```

MVP 只有一道综合修身题，因此先使用掌握程度最高的三本书。以后题库按知识主题拆分时，再由题目配置指定相关书籍或知识标签。

### 3. 基础能力 `B`

`exam.json` 为题目配置五项通用属性、五项书生能力和知识值的权重：

```text
B
= 智力 × 题目智力权重
 + 道德 × 题目道德权重
 + 政治 × 题目政治权重
 + 交际 × 题目交际权重
 + 体能 × 题目体能权重
 + 识字 × 题目识字权重
 + 经义 × 题目经义权重
 + 文章 × 题目文章权重
 + 策论 × 题目策论权重
 + 文学 × 题目文学权重
 + K × 题目知识权重
```

当前县试配置的全部权重合计为 1，因此 `B` 位于 0～100，保存两位小数。

### 4. 综合偏移 `R`

先计算临场状态：

```text
healthModifier = (健康 - 70) × 0.06
fatigueModifier = -max(0, 疲劳 - 20) × 0.08
fitnessModifier = (体能 - 50) × 0.03

stateModifier
= clamp(
    -8,
    4,
    healthModifier + fatigueModifier + fitnessModifier
  )
```

程序随机修正使用题目配置的 `randomOffsetMin` 和 `randomOffsetMax`：

```text
programRandom = randomInt(randomOffsetMin, randomOffsetMax)
R = round2(stateModifier + programRandom)
```

`R` 在创建考试时计算一次并保存到 `exam_record.random_offset`。系统代行和以身入局都使用同一个值。

### 5. 思维泡泡层级

引擎根据 `B + R` 得到角色当前思路层级，并把层级与已解锁知识摘要交给 Resolver：

| `B + R` | 层级 | 表现 |
|---:|---|---|
| `< 35` | `DIFFICULT` | 难以理解题目，只能想到零碎内容 |
| `35～不足60` | `BASIC` | 能抓住一两个基础观点 |
| `60～不足80` | `ORGANIZED` | 思路基本完整且有组织 |
| `>= 80` | `CONFIDENT` | 思路清晰、全面，并能调动已有知识 |

Resolver 只获得人物已经达到对应进度阶段的知识摘要。输出是思路提示，不是完整答卷。

### 6. 系统代行

```text
S_auto = clamp(0, 100, round2(B + R))
```

引擎先确定 `finalScore` 和通过状态，再把人物能力、思维层级、分数与结果交给 Resolver 生成答卷表现和结果叙事。生成的文字不再参与分数计算。

### 7. 以身入局

Resolver 阅读题目评分点和玩家原文后返回内容修正 `M`。引擎使用的有效修正为：

```text
effectiveM = clamp(-25, 25, round2(M))
S_player = clamp(0, 100, round2(B + effectiveM + R))
```

`M` 只表示玩家本次答案带来的内容增减，不包含角色属性和临场偏移。角色的 `B` 始终保留，因此不同角色提交相同答案仍会得到不同总分。

### 8. 通过与完成

```text
passed = finalScore >= passThreshold
```

通过时考试状态为 `COMPLETED_PASS`，否则为 `COMPLETED_FAIL`。两种结果都会把存档状态改为 `COMPLETED`，结束当前 MVP。

### 9. 计算示例

假设某角色县试时数据如下：

```text
智力60，道德55，政治40，交际50，体能50
识字65，经义55，文章45，策论35，文学40
知识值K=70，健康80，疲劳25
程序随机修正=+2
```

使用当前 `exam.json` 权重：

```text
B = 53.20
stateModifier = (80-70)×0.06 - (25-20)×0.08 + (50-50)×0.03
              = 0.20
R = 0.20 + 2 = 2.20

系统代行：53.20 + 2.20 = 55.40，未达到60分
以身入局且M=12：53.20 + 12 + 2.20 = 67.40，通过
```

这个结果体现了 MVP 的目标：角色能力决定基础，玩家认真作答可以改变结果，但不会抹掉角色差异。

## 十一、普通行动流程

### 1. 同存档串行

MVP 以单机、单应用实例运行。每个 `saveId` 对应一个进程内互斥锁。同一存档的一切状态变更在锁内串行，不同存档可以并行。

AI 调用期间可以继续持有进程锁，但不能持有数据库事务。这样既能保证当前存档的行动顺序，又不会让模型响应时间变成长事务时间。

### 2. 通用流程

```text
取得saveId对应的进程锁
  ↓
读取存档、人物、职业、家庭和本次所需内容配置
  ↓
根据actionType进入固定行动或Resolver流程
  ↓
Engine生成SettlementResult
  ↓
如形成重大事件，调用记忆Resolver生成摘要
  ↓
开启短事务
  ↓
保存人物、职业、家庭、装备或读书进度
  ↓
保存事件和人物记忆
  ↓
按endTurn保存新的时间状态
  ↓
提交事务并释放进程锁
```

`SettlementResult` 至少包含：

```text
人物属性After
人物状态After
职业能力After
家庭财富After
书籍进度After
状态差异列表
事件候选列表
时间推进结果
固定反馈所需事实
```

没有变化的模块不出现在本次结果中。

### 3. 固定行动

```text
actionCode
  ↓
读取action.json和目标内容
  ↓
CharacterEngine计算READ_BOOK / PRACTICE_WRITING / REST
  ↓
TurnEngine根据endTurn推进时间
  ↓
使用text.json模板生成反馈
  ↓
短事务保存
```

固定行动不创建 Prompt，也不进入 LangGraph。

### 4. 自由行动

```text
玩家原文
  ↓
FreeActionResolver组装当前作用域
  ↓
AI返回recognizedAction、driverPatch、eventKind和叙事意图
  ↓
CharacterEngine应用driverPatch并计算重要性
  ↓
TurnEngine结束本回合
  ↓
需要时生成事件与记忆摘要
  ↓
短事务保存
```

自由行动 Resolver 返回的是变化来源，`CharacterEngine` 返回的才是最终世界状态。

### 5. NPC 对话

```text
npcId与玩家原文
  ↓
NpcDialogueResolver读取NPC设定、可见事实和相关记忆
  ↓
AI生成npcReply和可能的重要语义事件
  ↓
普通回应直接返回；重要事件进入事件与记忆流程
  ↓
不推进回合
```

同一回合的临时对话上下文由前端随下一次对话请求携带，设置长度上限。离开场景、结束回合或刷新后可以丢弃，不写入数据库。

## 十二、县试流程

### 1. 创建考试

```text
存档进入EXAM_READY
  ↓
ExamEngine选择题目并计算K、B、R和思维层级
  ↓
ExamThoughtResolver读取题目、层级和已解锁知识摘要
  ↓
生成思维泡泡
  ↓
短事务写入exam_record
```

题目、`B`、`R`、思维泡泡和考试发生回合同时写入。之后读取县试页面直接返回这条记录。

### 2. 系统代行

```text
读取READY状态考试
  ↓
ExamEngine计算S_auto和通过状态
  ↓
ExamNarrativeResolver按既定分数生成答卷与结果叙事
  ↓
短事务保存考试结果并完成存档
```

### 3. 以身入局

```text
读取READY状态考试和玩家原文
  ↓
ExamEvaluationResolver按题目评分点生成评价与M
  ↓
ExamEngine计算effectiveM、S_player和通过状态
  ↓
ExamNarrativeResolver按既定结果生成评价叙事
  ↓
短事务保存玩家原文、M、考试结果并完成存档
```

“采纳”和“以身入局”最终都把同一条考试记录从 `READY` 推进到唯一完成态。

## 十三、Resolver 编排

### 1. 总体方式

所有含 AI 的任务共用一个 `GameResolver` 入口，由服务端传入明确的 `taskType`。路由由程序决定，模型不自行选择任务。

```text
GameResolver
├─ FREE_ACTION
├─ NPC_DIALOGUE
├─ MEMORY_SUMMARY
├─ EXAM_THOUGHT
├─ EXAM_EVALUATION
└─ EXAM_NARRATIVE
```

每次调用创建一次临时 LangGraph 状态，结束后立即释放。MVP 不建立 LangGraph 检查点，也不把图状态当作游戏存档。

### 2. Resolver 状态

所有任务共享以下基础字段：

```text
taskType
saveId
totalTurnNumber
sceneCode
playerInput
targetNpcId
examId
scopeSnapshot
recalledMemory
promptCode
resolverResult
```

没有使用的字段保持为空。`scopeSnapshot` 是当前调用所需事实的只读组合，不直接使用数据库 Entity 作为模型协议。

### 3. 只读上下文适配器

LangGraph 节点通过以下只读适配器取得上下文：

| 适配器 | 返回内容 |
|---|---|
| `CharacterContextProvider` | 玩家通用属性、健康、疲劳和当前状态 |
| `CareerContextProvider` | 当前职业与书生能力 |
| `SceneContextProvider` | 场景说明、可见 NPC 和行动背景 |
| `NpcContextProvider` | 目标 NPC 的公开设定和可知事实 |
| `BookKnowledgeProvider` | 达到当前进度阶段的知识摘要 |
| `MemoryContextProvider` | 与当前人物、场景和任务相关的少量记忆 |
| `ExamContextProvider` | 已保存题目、评分点、B、R、分数与状态 |

这些适配器只负责查询和组装，不修改任何 Entity。

### 4. 通用图结构

```text
START
  ↓
LoadScopeNode
  ↓
LoadTaskContextNode
  ↓
LoadMemoryNode（需要人物历史时）
  ↓
BuildPromptNode
  ↓
CallModelNode
  ↓
MapResultNode
  ↓
END
```

- `LoadScopeNode` 读取玩家、职业、时间和场景快照。
- `LoadTaskContextNode` 根据任务补充 NPC、书籍或考试内容。
- `LoadMemoryNode` 只为自由行动、NPC 对话和需要人生经历的叙事召回记忆。
- `BuildPromptNode` 从 `prompt.json` 选择模板，组成任务说明、作用域状态和玩家原文。
- `CallModelNode` 通过 `GameClient` 请求结构化结果。
- `MapResultNode` 把模型结果转换为对应 Resolver 返回对象。

图节点不写数据库。是否调用记忆节点由任务类型固定决定，不让模型自由扩展上下文范围。

### 5. 各任务上下文

| 任务 | 必要上下文 | Resolver 结果 |
|---|---|---|
| `FREE_ACTION` | 玩家、职业、场景、相关记忆、玩家原文 | 行动识别、驱动量、事件类型、叙事意图 |
| `NPC_DIALOGUE` | 玩家可见状态、NPC设定、双方相关记忆、对话原文 | NPC回应、事实概括、事件类型、相关人物 |
| `MEMORY_SUMMARY` | 已经成立的事件、记忆拥有者、相关人物 | 该人物视角的简短记忆摘要 |
| `EXAM_THOUGHT` | 题目、思维层级、人物属性、已解锁知识摘要 | 思维泡泡文本 |
| `EXAM_EVALUATION` | 题目、评分点、玩家原文 | 分项评价、内容修正M、反馈意图 |
| `EXAM_NARRATIVE` | 选择方式、最终分数、通过状态、人物能力 | 答卷表现和结果叙事 |

### 6. 自由行动结果

```json
{
  "accepted": true,
  "recognizedAction": "尝试向先生请教治事之道",
  "driverPatch": {
    "attribute_politics_gain": 0.4,
    "ability_celun_gain": 1.2,
    "fatigue_offset": 2
  },
  "eventKind": "LEARNING_CHOICE",
  "relatedCharacterId": ["先生人物ID"],
  "narrativeIntent": "玩家主动把经义与治事联系起来"
}
```

`accepted=false` 表示行为没有在世界中发生，Resolver 返回解释文本，不进入数值与时间结算。

### 7. NPC 对话结果

```json
{
  "npcReply": "先生放下书，反问你读书究竟是为了什么。",
  "factSummary": "玩家向先生请教读书与治事的关系",
  "eventKind": "NONE",
  "relatedCharacterId": ["先生人物ID"]
}
```

普通 NPC 对话没有 `driverPatch`。

### 8. 记忆生成

事件已经由 Engine 确定后，对每个需要记住该事件的人物分别调用一次 `MEMORY_SUMMARY`：

```text
EventRecord事实
+ 记忆拥有者身份
+ 该人物当时可知内容
→ AIMemorySummary
```

事件本身保存客观事实，人物记忆允许因视角不同而使用不同表达。记忆摘要不能代替事件记录。

## 十四、事务与持久化边界

### 1. 普通结束回合行动

以下内容在同一个短事务内保存：

- 人物通用属性、健康和疲劳。
- 当前职业能力。
- 家庭财富。
- 装备或书籍进度。
- 本次重大事件与人物记忆。
- 存档的回合、月份、年份、年龄和阶段。

### 2. 非结束回合操作

查看场景只读取内容配置。普通 NPC 回应只返回文本。NPC 对话形成重大事件时，只保存事件和记忆，不改变时间。

### 3. 县试

考试创建是一笔短事务；最终结算是另一笔短事务。模型调用在事务外完成，最终分数、考试完成态和存档完成态在同一次最终事务中保存。

## 十五、配置与公式归属

| 内容 | 所属位置 |
|---|---|
| 回合数、入学年龄、县试年龄、货币换算 | `GameRuleConstant` |
| 固定行动入口与是否结束回合 | `game/action.json` |
| 书籍难度、进度、权重和疲劳 | `game/book.json` |
| 题目、属性权重、门槛和随机区间 | `game/exam.json` |
| 场景、NPC、固定反馈文案 | 对应 `game/*.json` |
| Resolver 系统提示词 | `prompt/prompt.json` |
| 本文中的计算关系 | `CharacterEngine`、`TurnEngine`、`ExamEngine` |
| 当前存档事实 | MySQL |

公式中的系数先作为 Engine 内部具名常量保存，例如 `READ_INTELLIGENCE_BASE`、`WRITING_BASE_GAIN` 和 `EXAM_FATIGUE_PENALTY`。只有在实际试玩确认需要频繁调平衡时，再把对应系数迁入专门的数值配置；不把公式拆散到 Controller 或 Service。

## 十六、建议的代码入口

保持现有三个 Engine，不为每个小公式建立独立类：

```java
public final class CharacterEngine {
    BirthResult initializeBirth(BirthContext context);
    ChildhoodResult settleChildhood(ChildhoodContext context);
    CharacterSettlement settleRead(ReadContext context);
    CharacterSettlement settleWriting(WritingContext context);
    CharacterSettlement settleRest(RestContext context);
    CharacterSettlement applyDriverPatch(DriverContext context);
}

public final class TurnEngine {
    TurnResult advance(TurnContext context);
}

public final class ExamEngine {
    ExamCreationResult createExam(ExamContext context);
    ExamSettlement settleAuto(ExamRecordSnapshot exam);
    ExamSettlement settlePlayer(ExamRecordSnapshot exam, BigDecimal modifier);
}
```

Resolver 保持一个入口和按任务拆分的返回类型：

```java
public interface GameResolver {
    <T> T resolve(ResolverRequest request, Class<T> resultType);
}
```

具体业务由存档、回合和考试 Service 组合这些入口。Engine 只表达规则，Resolver 只表达语义，Service 只负责编排与保存，三者之间不互相替代。
