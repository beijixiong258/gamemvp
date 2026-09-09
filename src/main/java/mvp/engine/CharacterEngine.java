package mvp.engine;

import mvp.utils.Calculator;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
    private static final BigDecimal CONDITION_MIN = Calculator.decimal("0.55");
    private static final BigDecimal CONDITION_MAX = Calculator.decimal("1.20");

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

    /**
     * 综合健康、体能和疲劳，计算当前身体状态效率。
     *
     * @return 限制在0.55到1.20之间的状态效率系数
     */
    public BigDecimal conditionFactor(CharacterState character) {
        BigDecimal value = Calculator.decimal("0.65")
                .add(BigDecimal.valueOf(character.characterJiankang()).multiply(Calculator.decimal("0.003")))
                .add(BigDecimal.valueOf(character.characterTineng()).multiply(Calculator.decimal("0.002")))
                .subtract(BigDecimal.valueOf(character.characterPilao()).multiply(Calculator.decimal("0.003")));
        return Calculator.clamp(CONDITION_MIN, CONDITION_MAX, value);
    }

    /**
     * 根据行动基础疲劳和人物状态计算实际疲劳增长。
     *
     * @param baseFatigue 行动配置的基础疲劳
     * @return 本次行动实际增加的整数疲劳
     */
    public int fatigueGain(int baseFatigue, CharacterState character) {
        if (baseFatigue <= 0) {
            return 0;
        }
        BigDecimal fitnessCostFactor = Calculator.decimal("1.15")
                .subtract(Calculator.divide(BigDecimal.valueOf(character.characterTineng()), 200));
        int missingHealth = Math.max(0, 60 - character.characterJiankang());
        BigDecimal lowHealthFactor = Calculator.ONE
                .add(Calculator.divide(BigDecimal.valueOf(missingHealth), 100));
        int result = Calculator.roundToInt(
                BigDecimal.valueOf(baseFatigue).multiply(fitnessCostFactor).multiply(lowHealthFactor)
        );
        return Math.max(1, result);
    }

    /**
     * 计算疲劳超过80后造成的健康损失。
     *
     * @param fatigueAfter 行动结算后的疲劳值
     * @return 本次过劳造成的健康损失
     */
    public int exhaustionDamage(int fatigueAfter) {
        if (fatigueAfter <= 80) {
            return 0;
        }
        return (fatigueAfter - 80 + 9) / 10;
    }

    /**
     * 结算一次读书行动，包括阅读进度、书生能力、疲劳和过劳伤害。
     *
     * @param book 所读书籍的规则快照
     * @param progress 结算前的该书阅读记录
     * @param settlementTurnNumber 本次行动推进后的总回合编号
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
        return settleReading(character, scholar, book, progress, settlementTurnNumber, studyAmount, progressGain, diceRoll);
    }

    /** 以身入局将0至100分线性换算为10至90点进度；能力和疲劳仍按一次普通读书计算。 */
    public ReadBookResult readBookAsPlayer(
            CharacterState character, ScholarState scholar, BookRule book, BookProgress progress,
            long settlementTurnNumber, int score
    ) {
        int boundedScore = Calculator.clamp(0, 100, score);
        int awardedProgress = PLAYER_READING_MIN_PROGRESS + Calculator.roundToInt(
                Calculator.ratio(boundedScore)
                        .multiply(BigDecimal.valueOf(PLAYER_READING_MAX_PROGRESS - PLAYER_READING_MIN_PROGRESS))
        );
        BigDecimal studyAmount = readingStudyAmount(character);
        int remainingProgress = Math.max(0, BOOK_COMPLETION_PROGRESS - progress.currentProgress());
        return settleReading(character, scholar, book, progress, settlementTurnNumber, studyAmount,
                Math.min(remainingProgress, awardedProgress), null);
    }

    private BigDecimal readingStudyAmount(CharacterState character) {
        return BigDecimal.valueOf(READING_PROGRESS_PER_TURN)
                .multiply(intelligenceFactor(character))
                .multiply(conditionFactor(character));
    }

    private ReadBookResult settleReading(
            CharacterState character, ScholarState scholar, BookRule book, BookProgress progress,
            long settlementTurnNumber, BigDecimal studyAmount, int progressGain, Integer diceRoll
    ) {
        int progressAfter = Calculator.clamp(
                0,
                BOOK_COMPLETION_PROGRESS,
                progress.currentProgress() + progressGain
        );

        BigDecimal weightedAbility = weightedAbility(scholar, book);
        BigDecimal diminishingFactor = Calculator.clamp(
                Calculator.decimal("0.20"),
                Calculator.ONE,
                Calculator.ONE.subtract(
                        weightedAbility.divide(Calculator.decimal("120"), 8, RoundingMode.HALF_UP)
                )
        );
        BigDecimal reviewFactor = progress.currentProgress() >= BOOK_COMPLETION_PROGRESS
                ? Calculator.decimal("0.35")
                : Calculator.ONE;
        BigDecimal learningPool = Calculator.decimal("0.50")
                .add(Calculator.decimal("0.20").multiply(studyAmount))
                .multiply(diminishingFactor)
                .multiply(reviewFactor);
        if (Integer.valueOf(1).equals(diceRoll)) {
            learningPool = BigDecimal.ZERO;
        }

        ScholarState roundedGain = new ScholarState(
                abilityGain(learningPool, book.abilityShiziWeight()),
                abilityGain(learningPool, book.abilityJingyiWeight()),
                abilityGain(learningPool, book.abilityWenzhangWeight()),
                abilityGain(learningPool, book.abilityCelunWeight()),
                abilityGain(learningPool, book.abilityWenxueWeight())
        );
        ScholarState scholarAfter = applyAbilityGain(scholar, roundedGain);
        ScholarState appliedGain = difference(scholar, scholarAfter);

        WorkCondition workCondition = settleWorkCondition(character, book.fatigueCost());
        BookProgress progressAfterState = new BookProgress(
                progressAfter,
                progress.totalReadTurnNumber() + 1,
                progressAfter >= BOOK_COMPLETION_PROGRESS,
                settlementTurnNumber
        );
        return new ReadBookResult(
                workCondition.characterAfter(),
                scholarAfter,
                progressAfterState,
                progressGain,
                appliedGain,
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
     * @return 练习后的能力、疲劳和健康结果
     */
    public PracticeWritingResult practiceWriting(CharacterState character, ScholarState scholar) {
        BigDecimal writingDiminishing = Calculator.clamp(
                Calculator.decimal("0.25"),
                Calculator.ONE,
                Calculator.ONE.subtract(
                        BigDecimal.valueOf(scholar.abilityWenzhang())
                                .divide(Calculator.decimal("120"), 8, RoundingMode.HALF_UP)
                )
        );
        BigDecimal rawGain = Calculator.decimal("2.40")
                .multiply(intelligenceFactor(character))
                .multiply(conditionFactor(character))
                .multiply(writingDiminishing);
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
        WorkCondition workCondition = settleWorkCondition(character, 4);
        return new PracticeWritingResult(
                workCondition.characterAfter(),
                scholarAfter,
                abilityAfter - scholar.abilityWenzhang(),
                workCondition.fatigueGain(),
                workCondition.exhaustionDamage()
        );
    }

    /**
     * 结算一次休息行动，先恢复疲劳，再恢复健康。
     *
     * @return 休息后的人物状态和实际恢复量
     */
    public RestResult rest(CharacterState character) {
        int plannedFatigueRecovery = 18 + Calculator.roundToInt(
                BigDecimal.valueOf(character.characterTineng()).multiply(Calculator.decimal("0.10"))
        );
        int fatigueAfter = Math.max(0, character.characterPilao() - plannedFatigueRecovery);
        int plannedHealthRecovery = 3
                + character.characterTineng() / 25
                + (character.characterPilao() >= 70 ? 1 : 0);
        int healthAfter = Math.min(100, character.characterJiankang() + plannedHealthRecovery);
        CharacterState characterAfter = new CharacterState(
                character.characterZhili(),
                character.characterDaode(),
                character.characterZhengzhi(),
                character.characterJiaoji(),
                character.characterTineng(),
                healthAfter,
                fatigueAfter
        );
        return new RestResult(
                characterAfter,
                character.characterPilao() - fatigueAfter,
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

        int fatigueOffset = Calculator.roundToInt(patch.fatigueOffset());
        int fatigueAfter = Calculator.clamp(0, 100, character.characterPilao() + fatigueOffset);
        int exhaustionDamage = fatigueOffset > 0 ? exhaustionDamage(fatigueAfter) : 0;
        int healthAfter = Calculator.clamp(
                0,
                100,
                character.characterJiankang()
                        + Calculator.roundToInt(patch.healthOffset())
                        - exhaustionDamage
        );
        characterAfter = new CharacterState(
                characterAfter.characterZhili(),
                characterAfter.characterDaode(),
                characterAfter.characterZhengzhi(),
                characterAfter.characterJiaoji(),
                characterAfter.characterTineng(),
                healthAfter,
                fatigueAfter
        );
        return new DriverResult(characterAfter, scholarAfter, exhaustionDamage);
    }

    /**
     * 按书籍能力权重计算人物当前的综合书生能力。
     *
     * @return 加权后的能力值
     */
    private BigDecimal weightedAbility(ScholarState scholar, BookRule book) {
        return BigDecimal.valueOf(scholar.abilityShizi()).multiply(Calculator.ratio(book.abilityShiziWeight()))
                .add(BigDecimal.valueOf(scholar.abilityJingyi()).multiply(Calculator.ratio(book.abilityJingyiWeight())))
                .add(BigDecimal.valueOf(scholar.abilityWenzhang()).multiply(Calculator.ratio(book.abilityWenzhangWeight())))
                .add(BigDecimal.valueOf(scholar.abilityCelun()).multiply(Calculator.ratio(book.abilityCelunWeight())))
                .add(BigDecimal.valueOf(scholar.abilityWenxue()).multiply(Calculator.ratio(book.abilityWenxueWeight())));
    }

    /**
     * 从本回合学习池中计算一项能力的整数增长。
     *
     * @return 四舍五入后的非负能力增长
     */
    private int abilityGain(BigDecimal learningPool, int weightPercentage) {
        return Math.max(0, Calculator.roundToInt(learningPool.multiply(Calculator.ratio(weightPercentage))));
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
     * 统一结算劳累行动产生的疲劳与过劳健康损失。
     *
     * @return 行动后人物状态、实际疲劳增长和健康损失
     */
    private WorkCondition settleWorkCondition(CharacterState character, int baseFatigue) {
        int fatigueGain = fatigueGain(baseFatigue, character);
        int fatigueAfter = Calculator.clamp(0, 100, character.characterPilao() + fatigueGain);
        int exhaustionDamage = fatigueGain > 0 ? exhaustionDamage(fatigueAfter) : 0;
        int healthAfter = Calculator.clamp(0, 100, character.characterJiankang() - exhaustionDamage);
        CharacterState characterAfter = new CharacterState(
                character.characterZhili(),
                character.characterDaode(),
                character.characterZhengzhi(),
                character.characterJiaoji(),
                character.characterTineng(),
                healthAfter,
                fatigueAfter
        );
        return new WorkCondition(characterAfter, fatigueAfter - character.characterPilao(), exhaustionDamage);
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
        return new CharacterState(Math.max(0, current.characterZhili() - loss),
                Math.max(0, current.characterDaode() - loss), Math.max(0, current.characterZhengzhi() - loss),
                Math.max(0, current.characterJiaoji() - loss), Math.max(0, current.characterTineng() - loss),
                0, current.characterPilao());
    }

    /**
     * 结算一回合重病休养；最后一回合恢复到40健康。
     *
     * @param lastTurn 是否为最后一回合休养
     * @return 疲劳恢复后的人物状态
     */
    public CharacterState recoverIllnessTurn(CharacterState current, boolean lastTurn) {
        return new CharacterState(current.characterZhili(), current.characterDaode(), current.characterZhengzhi(),
                current.characterJiaoji(), current.characterTineng(),
                lastTurn ? GameRuleConstant.SICK_RECOVERY_HEALTH : 0, Math.max(0, current.characterPilao() - 20));
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
                bounded(patch.fatigueOffset(), 20), bounded(patch.healthOffset(), 10));
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

    public record CharacterState(
            int characterZhili,
            int characterDaode,
            int characterZhengzhi,
            int characterJiaoji,
            int characterTineng,
            int characterJiankang,
            int characterPilao
    ) implements Serializable {
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

    public record BookRule(
            int abilityShiziWeight,
            int abilityJingyiWeight,
            int abilityWenzhangWeight,
            int abilityCelunWeight,
            int abilityWenxueWeight,
            int fatigueCost
    ) {
    }

    public record BookProgress(
            int currentProgress,
            int totalReadTurnNumber,
            boolean completed,
            Long lastReadTurnNumber
    ) {
    }

    public record ReadBookResult(
            CharacterState character,
            ScholarState scholar,
            BookProgress progress,
            int progressGain,
            ScholarState abilityGain,
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
