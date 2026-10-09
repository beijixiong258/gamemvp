package mvp.engine;

import mvp.utils.Calculator;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.function.IntBinaryOperator;

import static mvp.engine.GameRuleConstant.BOOK_COMPLETION_PROGRESS;
import static mvp.engine.GameRuleConstant.PLAYER_READING_MAX_PROGRESS;
import static mvp.engine.GameRuleConstant.PLAYER_READING_MIN_PROGRESS;
import static mvp.engine.GameRuleConstant.READING_PROGRESS_PER_TURN;

/** 人物成长数值引擎，根据人物快照和行动输入返回确定的结算结果，不访问数据库或模型。 */
@Component
public class CharacterEngine {

    private static final int ATTRIBUTE_MIN = 0;
    private static final int ATTRIBUTE_MAX = 100;
    private static final BigDecimal INTELLIGENCE_BASE = Calculator.decimal("0.75");
    private static final BigDecimal INTELLIGENCE_WEIGHT = Calculator.decimal("0.50");
    private static final BigDecimal OVERWORK_EFFICIENCY = Calculator.decimal("0.80");
    private static final double SIGMOID_LOW = 1.0 / (1.0 + Math.exp(5.0));
    private static final double SIGMOID_RANGE = 1.0 / (1.0 + Math.exp(-5.0)) - SIGMOID_LOW;

    /**
     * “开始人生”直接返回六岁时的可玩状态，不创建零至五岁的逐年回合。
     *
     * @param birthRegionId 玩家选择的出生地区ID，同时作为开局所在地
     * @return 六岁人物、书生能力和初始家庭背景数值
     */
    public StartLifeResult startLife(String birthRegionId) {
        int initialAttribute = GameRuleConstant.INITIAL_GENERAL_ATTRIBUTE;
        int initialHealth = Calculator.clamp(
                60,
                100,
                70 + Calculator.roundToInt(
                        BigDecimal.valueOf(initialAttribute).multiply(Calculator.decimal("0.25"))
                )
        );
        CharacterState character = new CharacterState(
                initialAttribute,
                initialAttribute,
                initialAttribute,
                initialAttribute,
                initialAttribute,
                initialHealth,
                0
        );
        ScholarState scholar = new ScholarState(
                GameRuleConstant.INITIAL_LITERACY_ABILITY,
                0,
                0,
                0,
                0
        );
        return new StartLifeResult(
                character,
                scholar,
                GameRuleConstant.DEFAULT_INITIAL_FAMILY_WEALTH,
                GameRuleConstant.DEFAULT_FAMILY_BACKGROUND_SUMMARY,
                birthRegionId,
                birthRegionId
        );
    }

    /** 计算智力对学习类行动的效率系数。 */
    public BigDecimal intelligenceFactor(CharacterState character) {
        return INTELLIGENCE_BASE.add(
                INTELLIGENCE_WEIGHT.multiply(
                        Calculator.divide(BigDecimal.valueOf(character.characterZhili()), 100)
                )
        );
    }

    /** 健康0～50按归一化sigmoid提供0.50～1.00效率；50及以上不惩罚，也不额外增益。 */
    public BigDecimal healthFactor(CharacterState character) {
        return healthFactor(character.characterJiankang());
    }

    public static BigDecimal healthFactor(int healthValue) {
        int health = Calculator.clamp(0, 50, healthValue);
        if (health == 0) return Calculator.decimal("0.50");
        if (health == 25) return Calculator.decimal("0.75");
        if (health == 50) return BigDecimal.ONE;
        double sigmoid = 1.0 / (1.0 + Math.exp(-0.20 * (health - 25)));
        return BigDecimal.valueOf(0.50 + 0.50 * (sigmoid - SIGMOID_LOW) / SIGMOID_RANGE);
    }

    /** 行动前剩余体力不高于20点时，学习效率再乘0.80。 */
    public BigDecimal conditionFactor(CharacterState character) {
        BigDecimal value = healthFactor(character);
        return character.stamina() <= GameRuleConstant.OVERWORK_STAMINA_THRESHOLD
                ? value.multiply(OVERWORK_EFFICIENCY) : value;
    }

    /** 体能0～100映射到整数上限50～150；锚点直返，避免向下取整受浮点尾差影响。 */
    public static int maxStamina(int fitness) {
        int value = Calculator.clamp(0, 100, fitness);
        if (value == 0) return 50;
        if (value == 50) return 100;
        if (value == 100) return 150;
        double sigmoid = 1.0 / (1.0 + Math.exp(-0.10 * (value - 50)));
        return (int) Math.floor(50.0 + 100.0 * (sigmoid - SIGMOID_LOW) / SIGMOID_RANGE);
    }

    public int stamina(CharacterState character) {
        return character.stamina();
    }

    /** 消耗采用固定整数成本；不足时不产生任何状态，成功消费后处于过劳区间则损失1健康。 */
    public CharacterState consumeStamina(CharacterState character, int cost) {
        if (cost < 0) throw new IllegalArgumentException("体力消耗不能为负数");
        if (character.stamina() < cost) throw new IllegalArgumentException("当前体力不足，不能执行该行动");
        int remaining = character.stamina() - cost;
        int damage = cost > 0 && remaining <= GameRuleConstant.OVERWORK_STAMINA_THRESHOLD
                ? GameRuleConstant.OVERWORK_HEALTH_LOSS : 0;
        return withCondition(character, Math.max(0, character.characterJiankang() - damage), remaining);
    }

    /** 只恢复体力，不附带恢复健康，也不改变体力上限。 */
    public CharacterState recoverStamina(CharacterState character, int recovery) {
        if (recovery < 0) throw new IllegalArgumentException("体力恢复不能为负数");
        int remaining = (int) Math.min(character.maxStamina(), (long) character.stamina() + recovery);
        return withCondition(character, character.characterJiankang(), remaining);
    }

    private CharacterState withCondition(CharacterState character, int health, int stamina) {
        return new CharacterState(character.characterZhili(), character.characterDaode(), character.characterZhengzhi(),
                character.characterJiaoji(), character.characterTineng(), health,
                character.maxStamina() - Calculator.clamp(0, character.maxStamina(), stamina));
    }

    /** 体能变化只改变上限，当前体力保留绝对点数；上限下降时截断超出的当前值。 */
    private CharacterState preserveStamina(CharacterState before, CharacterState after) {
        return withCondition(after, after.characterJiankang(), Math.min(before.stamina(), after.maxStamina()));
    }

    /**
     * 结算一次读书行动，包括阅读进度、书生能力、体力消耗和过劳伤害。
     *
     * @param book 所读书籍的规则快照
     * @param progress 结算前的该书阅读记录
     * @param settlementTurnNumber 本次行动所属的总回合编号，行动本身不负责推进时间
     * @param diceRoll 业务层已生成的1D100骰点；1无学习收益，100补满进度
     * @return 读书后的完整状态和实际变化
     */
    public ReadBookResult readBook(
            CharacterState character,
            ScholarState scholar,
            BookRule book,
            BookProgress progress,
            long settlementTurnNumber,
            int diceRoll
    ) {
        BigDecimal studyAmount = readingStudyAmount(character);

        int remainingProgress = Math.max(0, BOOK_COMPLETION_PROGRESS - progress.currentProgress());
        int progressGain = Math.min(remainingProgress, Math.max(0, Calculator.roundToInt(studyAmount)));
        if (diceRoll == 1) {
            progressGain = 0;
        } else if (diceRoll == 100) {
            progressGain = remainingProgress;
        }
        return settleReading(character, scholar, book, progress, settlementTurnNumber, progressGain, diceRoll,
                book.fatigueCost());
    }

    /** 阅读（手动）将0至100分换算为10至90点进度；体力只扣本书阅读成本。 */
    public ReadBookResult readBookAsPlayer(
            CharacterState character, ScholarState scholar, BookRule book, BookProgress progress,
            long settlementTurnNumber, int score
    ) {
        int boundedScore = Calculator.clamp(0, 100, score);
        int awardedProgress = PLAYER_READING_MIN_PROGRESS + Calculator.roundToInt(
                Calculator.ratio(boundedScore)
                        .multiply(BigDecimal.valueOf(PLAYER_READING_MAX_PROGRESS - PLAYER_READING_MIN_PROGRESS))
        );
        int remainingProgress = Math.max(0, BOOK_COMPLETION_PROGRESS - progress.currentProgress());
        return settleReading(character, scholar, book, progress, settlementTurnNumber,
                Math.min(remainingProgress, awardedProgress), null, book.fatigueCost());
    }

    private BigDecimal readingStudyAmount(CharacterState character) {
        return BigDecimal.valueOf(READING_PROGRESS_PER_TURN)
                .multiply(intelligenceFactor(character))
                .multiply(conditionFactor(character));
    }

    private ReadBookResult settleReading(
            CharacterState character, ScholarState scholar, BookRule book, BookProgress progress,
            long settlementTurnNumber, int progressGain, Integer diceRoll, int staminaCost
    ) {
        if (progress.currentProgress() >= BOOK_COMPLETION_PROGRESS) {
            throw new IllegalArgumentException("这本书已完成，不能继续阅读");
        }
        int progressAfter = Calculator.clamp(
                0,
                BOOK_COMPLETION_PROGRESS,
                progress.currentProgress() + progressGain
        );

        ReadingReward alreadyEarned = book.readingReward().atProgress(progress.currentProgress())
                .maximum(progress.legacyReward());
        ReadingReward reward = book.readingReward().atProgress(progressAfter).subtractPositive(alreadyEarned);
        // 骰点1没有新增进度，但仍消耗这次阅读的固定体力成本。
        WorkCondition workCondition = settleWorkCondition(character, staminaCost);
        CharacterState characterAfter = applyReadingAttributes(workCondition.characterAfter(), reward);
        ScholarState scholarAfter = applyReadingAbilities(scholar, reward);
        ScholarState appliedGain = difference(scholar, scholarAfter);
        ReadingReward appliedReward = readingRewardDifference(character, scholar, characterAfter, scholarAfter);
        BookProgress progressAfterState = new BookProgress(
                progressAfter,
                progress.totalReadTurnNumber() + 1,
                progressAfter >= BOOK_COMPLETION_PROGRESS,
                settlementTurnNumber, progress.legacyReward()
        );
        return new ReadBookResult(
                characterAfter,
                scholarAfter,
                progressAfterState,
                progressGain,
                appliedGain, appliedReward,
                workCondition.fatigueGain(),
                workCondition.exhaustionDamage(),
                diceRoll,
                progress.currentProgress() < BOOK_COMPLETION_PROGRESS
                        && progressAfter >= BOOK_COMPLETION_PROGRESS
        );
    }

    /**
     * 结算一次练习文章行动。
     *
     * @return 练习后的能力、体力和健康结果
     */
    public PracticeWritingResult practiceWriting(CharacterState character, ScholarState scholar) {
        // 固定成长不依赖当前文章和智力，避免先练习再读书能多领成长。
        BigDecimal rawGain = BigDecimal.valueOf(GameRuleConstant.PRACTICE_WRITING_BASE_GAIN)
                .multiply(conditionFactor(character));
        int roundedGain = Math.max(0, Calculator.roundToInt(rawGain));
        int abilityAfter = Calculator.clamp(
                ATTRIBUTE_MIN,
                ATTRIBUTE_MAX,
                scholar.abilityWenzhang() + roundedGain
        );
        ScholarState scholarAfter = new ScholarState(
                scholar.abilityShizi(),
                scholar.abilityJingyi(),
                abilityAfter,
                scholar.abilityCelun(),
                scholar.abilityWenxue()
        );
        WorkCondition workCondition = settleWorkCondition(character, GameRuleConstant.PRACTICE_STAMINA_COST);
        return new PracticeWritingResult(
                workCondition.characterAfter(),
                scholarAfter,
                abilityAfter - scholar.abilityWenzhang(),
                workCondition.fatigueGain(),
                workCondition.exhaustionDamage()
        );
    }

    /**
     * 结算一次休息行动，恢复固定体力和健康，不因体能重复放大收益。
     *
     * @return 休息后的人物状态和实际恢复量
     */
    public RestResult rest(CharacterState character) {
        CharacterState recovered = recoverStamina(character, GameRuleConstant.REST_STAMINA_RECOVERY);
        int healthAfter = ordinaryHealthRecovery(character.characterJiankang(), GameRuleConstant.REST_HEALTH_RECOVERY);
        CharacterState characterAfter = withCondition(recovered, healthAfter, recovered.stamina());
        return new RestResult(
                characterAfter,
                characterAfter.stamina() - character.stamina(),
                healthAfter - character.characterJiankang()
        );
    }

    /**
     * 把自由行动或对话Resolver给出的驱动量应用到人物与书生能力。
     *
     * @param patch Resolver输出的结构化驱动量
     * @return 收束后的状态与过劳伤害
     */
    public DriverResult applyDriver(
            CharacterState character,
            ScholarState scholar,
            DriverPatch patch
    ) {
        patch = limitDriver(patch);
        CharacterState characterAfter = new CharacterState(
                applyBoundedChange(character.characterZhili(), patch.attributeIntelligenceGain()),
                applyBoundedChange(character.characterDaode(), patch.attributeMoralityGain()),
                applyBoundedChange(character.characterZhengzhi(), patch.attributePoliticsGain()),
                applyBoundedChange(character.characterJiaoji(), patch.attributeSocialGain()),
                applyBoundedChange(character.characterTineng(), patch.attributeFitnessGain()),
                character.characterJiankang(),
                character.characterPilao()
        );
        ScholarState scholarAfter = new ScholarState(
                applyBoundedChange(scholar.abilityShizi(), patch.abilityShiziGain()),
                applyBoundedChange(scholar.abilityJingyi(), patch.abilityJingyiGain()),
                applyBoundedChange(scholar.abilityWenzhang(), patch.abilityWenzhangGain()),
                applyBoundedChange(scholar.abilityCelun(), patch.abilityCelunGain()),
                applyBoundedChange(scholar.abilityWenxue(), patch.abilityWenxueGain())
        );

        // AI没有体力恢复或额外消耗权限；统一业务成本由服务层在同一事务内扣除。
        int healthChange = Calculator.roundToInt(patch.healthOffset());
        int healthAfter = healthChange > 0 ? ordinaryHealthRecovery(character.characterJiankang(), healthChange)
                : Calculator.clamp(0, 100, character.characterJiankang() + healthChange);
        characterAfter = new CharacterState(
                characterAfter.characterZhili(),
                characterAfter.characterDaode(),
                characterAfter.characterZhengzhi(),
                characterAfter.characterJiaoji(),
                characterAfter.characterTineng(),
                healthAfter,
                character.characterPilao()
        );
        return new DriverResult(preserveStamina(character, characterAfter), scholarAfter, 0);
    }

    /** 普通恢复最多到75；已有75以上的健康不因恢复而被降低。 */
    private int ordinaryHealthRecovery(int health, int recovery) {
        return Math.max(health, Math.min(GameRuleConstant.REST_HEALTH_CAP, health + recovery));
    }

    /** 书籍成长只受0～100属性范围约束，不经过AI单次变化上限。 */
    public CharacterState applyReadingAttributes(CharacterState current, ReadingReward reward) {
        CharacterState after = new CharacterState(
                Calculator.clamp(0, 100, current.characterZhili() + reward.characterZhili()),
                Calculator.clamp(0, 100, current.characterDaode() + reward.characterDaode()),
                Calculator.clamp(0, 100, current.characterZhengzhi() + reward.characterZhengzhi()),
                Calculator.clamp(0, 100, current.characterJiaoji() + reward.characterJiaoji()),
                Calculator.clamp(0, 100, current.characterTineng() + reward.characterTineng()),
                current.characterJiankang(), current.characterPilao());
        return preserveStamina(current, after);
    }

    public ScholarState applyReadingAbilities(ScholarState current, ReadingReward reward) {
        return applyAbilityGain(current, new ScholarState(reward.abilityShizi(), reward.abilityJingyi(),
                reward.abilityWenzhang(), reward.abilityCelun(), reward.abilityWenxue()));
    }

    /** 返回实际到账的成长，达到100上限的部分不重复补发。 */
    public ReadingReward readingRewardDifference(CharacterState before, ScholarState scholarBefore,
                                                  CharacterState after, ScholarState scholarAfter) {
        return new ReadingReward(after.characterZhili() - before.characterZhili(),
                after.characterDaode() - before.characterDaode(),
                after.characterZhengzhi() - before.characterZhengzhi(),
                after.characterJiaoji() - before.characterJiaoji(),
                after.characterTineng() - before.characterTineng(),
                scholarAfter.abilityShizi() - scholarBefore.abilityShizi(),
                scholarAfter.abilityJingyi() - scholarBefore.abilityJingyi(),
                scholarAfter.abilityWenzhang() - scholarBefore.abilityWenzhang(),
                scholarAfter.abilityCelun() - scholarBefore.abilityCelun(),
                scholarAfter.abilityWenxue() - scholarBefore.abilityWenxue());
    }

    /**
     * 把五项能力增长应用到当前书生状态并收束到合法范围。
     *
     * @return 收束后的书生能力
     */
    private ScholarState applyAbilityGain(ScholarState current, ScholarState gain) {
        return new ScholarState(
                Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityShizi() + gain.abilityShizi()),
                Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityJingyi() + gain.abilityJingyi()),
                Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityWenzhang() + gain.abilityWenzhang()),
                Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityCelun() + gain.abilityCelun()),
                Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityWenxue() + gain.abilityWenxue())
        );
    }

    /**
     * 计算书生能力结算前后的实际差值。
     *
     * @return 五项能力的实际变化
     */
    private ScholarState difference(ScholarState before, ScholarState after) {
        return new ScholarState(
                after.abilityShizi() - before.abilityShizi(),
                after.abilityJingyi() - before.abilityJingyi(),
                after.abilityWenzhang() - before.abilityWenzhang(),
                after.abilityCelun() - before.abilityCelun(),
                after.abilityWenxue() - before.abilityWenxue()
        );
    }

    /**
     * 统一结算行动的固定体力消耗与过劳健康损失。
     *
     * @return 行动后人物状态、实际体力消耗和健康损失（fatigueGain保留兼容字段名）
     */
    private WorkCondition settleWorkCondition(CharacterState character, int cost) {
        CharacterState after = consumeStamina(character, cost);
        return new WorkCondition(after, character.stamina() - after.stamina(),
                character.characterJiankang() - after.characterJiankang());
    }

    /**
     * 把一个驱动量应用到0至100的人物数值。
     *
     * @return 四舍五入并收束后的整数值
     */
    private int applyBoundedChange(int current, BigDecimal change) {
        return Calculator.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current + Calculator.roundToInt(change));
    }

    /**
     * 计算单书已经取得的学识，未读满时按六成比例折算，读满取得全部。
     *
     * @param totalKnowledge 该书完整学识
     * @param progress 0至100的整数进度
     * @return 学识贡献，最多四位小数，不在每次阅读时反复取整
     */
    public BigDecimal knowledgeContribution(int totalKnowledge, int progress) {
        return progress >= BOOK_COMPLETION_PROGRESS ? BigDecimal.valueOf(totalKnowledge)
                : BigDecimal.valueOf(totalKnowledge).multiply(Calculator.decimal("0.006"))
                        .multiply(BigDecimal.valueOf(Math.max(0, progress)));
    }

    /**
     * 重病入场只扣一次五维，健康保持0，回合数由业务层保存。
     *
     * @param current 重病开始前的人物状态
     * @return 五维各减2后的状态
     */
    public CharacterState enterIllness(CharacterState current) {
        int loss = GameRuleConstant.SICK_ATTRIBUTE_LOSS;
        CharacterState after = new CharacterState(Math.max(0, current.characterZhili() - loss),
                Math.max(0, current.characterDaode() - loss), Math.max(0, current.characterZhengzhi() - loss),
                Math.max(0, current.characterJiaoji() - loss), Math.max(0, current.characterTineng() - loss),
                0, current.characterPilao());
        return preserveStamina(current, after);
    }

    /**
     * 结算一回合重病休养；最后一回合恢复到40健康。
     *
     * @param lastTurn 是否为最后一回合休养
     * @return 体力恢复后的人物状态
     */
    public CharacterState recoverIllnessTurn(CharacterState current, boolean lastTurn) {
        CharacterState recovered = recoverStamina(current, GameRuleConstant.REST_STAMINA_RECOVERY);
        return new CharacterState(current.characterZhili(), current.characterDaode(), current.characterZhengzhi(),
                current.characterJiaoji(), current.characterTineng(),
                lastTurn ? GameRuleConstant.SICK_RECOVERY_HEALTH : 0, recovered.characterPilao());
    }

    /**
     * 收束一场AI行为的驱动量；缺省字段按0，属性/能力采用首版变化上限。
     *
     * @param patch AI给出的变化量
     * @return 可交给引擎的有界驱动量
     */
    public DriverPatch limitDriver(DriverPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("AI未返回属性结算");
        }
        int attributeLimit = GameRuleConstant.AI_ATTRIBUTE_CHANGE_LIMIT;
        int abilityLimit = GameRuleConstant.AI_ABILITY_CHANGE_LIMIT;
        return new DriverPatch(bounded(patch.attributeIntelligenceGain(), attributeLimit), bounded(patch.attributeMoralityGain(), attributeLimit),
                bounded(patch.attributePoliticsGain(), attributeLimit), bounded(patch.attributeSocialGain(), attributeLimit),
                bounded(patch.attributeFitnessGain(), attributeLimit), bounded(patch.abilityShiziGain(), abilityLimit),
                bounded(patch.abilityJingyiGain(), abilityLimit), bounded(patch.abilityWenzhangGain(), abilityLimit),
                bounded(patch.abilityCelunGain(), abilityLimit), bounded(patch.abilityWenxueGain(), abilityLimit),
                BigDecimal.ZERO, bounded(patch.healthOffset(), 10));
    }

    private BigDecimal bounded(BigDecimal value, int limit) {
        return value == null ? BigDecimal.ZERO : Calculator.clamp(BigDecimal.valueOf(-limit), BigDecimal.valueOf(limit), value);
    }

    private record WorkCondition(
            CharacterState characterAfter,
            int fatigueGain,
            int exhaustionDamage
    ) {
    }

    /** characterPilao是兼容存储字段：记录距动态体力上限已消耗的点数。 */
    public record CharacterState(
            int characterZhili,
            int characterDaode,
            int characterZhengzhi,
            int characterJiaoji,
            int characterTineng,
            int characterJiankang,
            int characterPilao
    ) implements Serializable {
        public int maxStamina() {
            return CharacterEngine.maxStamina(characterTineng);
        }

        public int stamina() {
            return Calculator.clamp(0, maxStamina(), maxStamina() - characterPilao);
        }
    }

    public record ScholarState(
            int abilityShizi,
            int abilityJingyi,
            int abilityWenzhang,
            int abilityCelun,
            int abilityWenxue
    ) implements Serializable {
    }

    public record StartLifeResult(
            CharacterState character,
            ScholarState scholar,
            int initialFamilyWealth,
            String familyBackgroundSummary,
            String birthRegionId,
            String currentRegionId
    ) {
    }

    /** 每本书的完整成长额度；旧收益也用同一结构保存，独立于当前人物属性。 */
    public record ReadingReward(
            int characterZhili, int characterDaode, int characterZhengzhi, int characterJiaoji, int characterTineng,
            int abilityShizi, int abilityJingyi, int abilityWenzhang, int abilityCelun, int abilityWenxue
    ) implements Serializable {
        public static final ReadingReward ZERO = new ReadingReward(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public ReadingReward atProgress(int progress) {
            int bounded = Calculator.clamp(0, BOOK_COMPLETION_PROGRESS, progress);
            return combine(ZERO, (value, ignored) -> value * bounded / BOOK_COMPLETION_PROGRESS);
        }

        public ReadingReward plus(ReadingReward other) { return combine(other, Math::addExact); }
        public ReadingReward maximum(ReadingReward other) { return combine(other, Math::max); }
        public ReadingReward subtractPositive(ReadingReward other) {
            return combine(other, (value, paid) -> Math.max(0, value - paid));
        }

        private ReadingReward combine(ReadingReward other, IntBinaryOperator operation) {
            return new ReadingReward(
                    operation.applyAsInt(characterZhili, other.characterZhili),
                    operation.applyAsInt(characterDaode, other.characterDaode),
                    operation.applyAsInt(characterZhengzhi, other.characterZhengzhi),
                    operation.applyAsInt(characterJiaoji, other.characterJiaoji),
                    operation.applyAsInt(characterTineng, other.characterTineng),
                    operation.applyAsInt(abilityShizi, other.abilityShizi),
                    operation.applyAsInt(abilityJingyi, other.abilityJingyi),
                    operation.applyAsInt(abilityWenzhang, other.abilityWenzhang),
                    operation.applyAsInt(abilityCelun, other.abilityCelun),
                    operation.applyAsInt(abilityWenxue, other.abilityWenxue));
        }
    }

    /** fatigueCost保留配置字段名，含义为本书一次阅读的固定体力成本。 */
    public record BookRule(ReadingReward readingReward, int fatigueCost) {
    }

    public record BookProgress(
            int currentProgress, int totalReadTurnNumber, boolean completed, Long lastReadTurnNumber,
            ReadingReward legacyReward
    ) {
    }

    public record ReadBookResult(
            CharacterState character,
            ScholarState scholar,
            BookProgress progress,
            int progressGain,
            ScholarState abilityGain,
            ReadingReward readingRewardGain,
            int fatigueGain,
            int exhaustionDamage,
            Integer diceRoll,
            boolean reachedMastered
    ) {
    }

    public record PracticeWritingResult(
            CharacterState character,
            ScholarState scholar,
            int writingAbilityGain,
            int fatigueGain,
            int exhaustionDamage
    ) {
    }

    public record RestResult(
            CharacterState character,
            int fatigueRecovery,
            int healthRecovery
    ) {
    }

    public record DriverPatch(
            BigDecimal attributeIntelligenceGain,
            BigDecimal attributeMoralityGain,
            BigDecimal attributePoliticsGain,
            BigDecimal attributeSocialGain,
            BigDecimal attributeFitnessGain,
            BigDecimal abilityShiziGain,
            BigDecimal abilityJingyiGain,
            BigDecimal abilityWenzhangGain,
            BigDecimal abilityCelunGain,
            BigDecimal abilityWenxueGain,
            BigDecimal fatigueOffset,
            BigDecimal healthOffset
    ) implements Serializable {
    }

    public record DriverResult(
            CharacterState character,
            ScholarState scholar,
            int exhaustionDamage
    ) implements Serializable {
    }
}
