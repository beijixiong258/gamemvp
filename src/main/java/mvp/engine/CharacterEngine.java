package mvp.engine;

import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 人物成长数值引擎，根据人物快照和行动输入返回确定的结算结果，不访问数据库或模型。
 */
@Component
public class CharacterEngine {

    private static final int ATTRIBUTE_MIN = 0;
    private static final int ATTRIBUTE_MAX = 100;
    private static final BigDecimal INTELLIGENCE_BASE = Utils.decimal("0.75");
    private static final BigDecimal INTELLIGENCE_WEIGHT = Utils.decimal("0.50");
    private static final BigDecimal CONDITION_MIN = Utils.decimal("0.55");
    private static final BigDecimal CONDITION_MAX = Utils.decimal("1.20");

    /**
     * “开始人生”直接返回六岁时的可玩状态，不创建零至五岁的逐年回合。
     *
     * @param birthRegionId 玩家选择的出生地区ID，同时作为开局所在地
     * @return 六岁人物、书生能力和初始家庭背景数值
     */
    public StartLifeResult startLife(String birthRegionId) {
        int initialAttribute = GameRuleConstant.INITIAL_GENERAL_ATTRIBUTE;
        int initialHealth = Utils.clamp(
                60,
                100,
                70 + Utils.roundToInt(
                        BigDecimal.valueOf(initialAttribute).multiply(Utils.decimal("0.25"))
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

    /**
     * 计算智力对学习类行动的效率系数。
     *
     * @param character 当前人物状态
     * @return 智力效率系数
     */
    public BigDecimal intelligenceFactor(CharacterState character) {
        return INTELLIGENCE_BASE.add(
                INTELLIGENCE_WEIGHT.multiply(
                        Utils.divide(BigDecimal.valueOf(character.characterZhili()), 100)
                )
        );
    }

    /**
     * 综合健康、体能和疲劳，计算当前身体状态效率。
     *
     * @param character 当前人物状态
     * @return 限制在0.55到1.20之间的状态效率系数
     */
    public BigDecimal conditionFactor(CharacterState character) {
        BigDecimal value = Utils.decimal("0.65")
                .add(BigDecimal.valueOf(character.characterJiankang()).multiply(Utils.decimal("0.003")))
                .add(BigDecimal.valueOf(character.characterTineng()).multiply(Utils.decimal("0.002")))
                .subtract(BigDecimal.valueOf(character.characterPilao()).multiply(Utils.decimal("0.003")));
        return Utils.clamp(CONDITION_MIN, CONDITION_MAX, value);
    }

    /**
     * 根据行动基础疲劳和人物状态计算实际疲劳增长。
     *
     * @param baseFatigue 行动配置的基础疲劳
     * @param character 当前人物状态
     * @return 本次行动实际增加的整数疲劳
     */
    public int fatigueGain(int baseFatigue, CharacterState character) {
        if (baseFatigue <= 0) {
            return 0;
        }
        BigDecimal fitnessCostFactor = Utils.decimal("1.15")
                .subtract(Utils.divide(BigDecimal.valueOf(character.characterTineng()), 200));
        int missingHealth = Math.max(0, 60 - character.characterJiankang());
        BigDecimal lowHealthFactor = Utils.ONE
                .add(Utils.divide(BigDecimal.valueOf(missingHealth), 100));
        int result = Utils.roundToInt(
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
     * @param character 当前人物状态
     * @param scholar 当前书生能力
     * @param book 所读书籍的规则快照
     * @param progress 结算前的该书阅读记录
     * @param settlementTurnNumber 本次行动推进后的总回合编号
     * @return 读书后的完整状态和实际变化
     */
    public ReadBookResult readBook(
            CharacterState character,
            ScholarState scholar,
            BookRule book,
            BookProgress progress,
            long settlementTurnNumber
    ) {
        BigDecimal readingFoundation = BigDecimal.valueOf(character.characterZhili())
                .multiply(Utils.decimal("0.40"))
                .add(BigDecimal.valueOf(scholar.abilityShizi()).multiply(Utils.decimal("0.60")));
        BigDecimal difficultyFactor = Utils.clamp(
                Utils.decimal("0.60"),
                Utils.decimal("1.20"),
                Utils.ONE.add(
                        Utils.divide(readingFoundation.subtract(BigDecimal.valueOf(book.difficulty())), 100)
                )
        );
        BigDecimal studyAmount = BigDecimal.valueOf(book.baseProgressPerTurn())
                .multiply(intelligenceFactor(character))
                .multiply(conditionFactor(character))
                .multiply(difficultyFactor);

        int remainingProgress = Math.max(0, book.requiredProgress() - progress.currentProgress());
        int progressGain = Math.min(remainingProgress, Math.max(0, Utils.roundToInt(studyAmount)));
        int progressAfter = Utils.clamp(
                0,
                book.requiredProgress(),
                progress.currentProgress() + progressGain
        );

        BigDecimal weightedAbility = weightedAbility(scholar, book);
        BigDecimal diminishingFactor = Utils.clamp(
                Utils.decimal("0.20"),
                Utils.ONE,
                Utils.ONE.subtract(
                        weightedAbility.divide(Utils.decimal("120"), 8, RoundingMode.HALF_UP)
                )
        );
        BigDecimal reviewFactor = progress.currentProgress() >= book.requiredProgress()
                ? Utils.decimal("0.35")
                : Utils.ONE;
        BigDecimal learningPool = Utils.decimal("0.50")
                .add(Utils.decimal("0.20").multiply(studyAmount))
                .multiply(diminishingFactor)
                .multiply(reviewFactor);

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
                progressAfter >= book.requiredProgress(),
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
                progress.currentProgress() < 60 && progressAfter >= 60,
                progress.currentProgress() < book.requiredProgress()
                        && progressAfter >= book.requiredProgress()
        );
    }

    /**
     * 结算一次练习文章行动。
     *
     * @param character 当前人物状态
     * @param scholar 当前书生能力
     * @return 练习后的能力、疲劳和健康结果
     */
    public PracticeWritingResult practiceWriting(CharacterState character, ScholarState scholar) {
        BigDecimal writingDiminishing = Utils.clamp(
                Utils.decimal("0.25"),
                Utils.ONE,
                Utils.ONE.subtract(
                        BigDecimal.valueOf(scholar.abilityWenzhang())
                                .divide(Utils.decimal("120"), 8, RoundingMode.HALF_UP)
                )
        );
        BigDecimal rawGain = Utils.decimal("2.40")
                .multiply(intelligenceFactor(character))
                .multiply(conditionFactor(character))
                .multiply(writingDiminishing);
        int roundedGain = Math.max(0, Utils.roundToInt(rawGain));
        int abilityAfter = Utils.clamp(
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
     * @param character 当前人物状态
     * @return 休息后的人物状态和实际恢复量
     */
    public RestResult rest(CharacterState character) {
        int plannedFatigueRecovery = 18 + Utils.roundToInt(
                BigDecimal.valueOf(character.characterTineng()).multiply(Utils.decimal("0.10"))
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
     * 把自由行动Resolver给出的驱动量应用到人物与书生能力。
     *
     * @param character 当前人物状态
     * @param scholar 当前书生能力
     * @param patch Resolver输出的结构化驱动量
     * @return 收束后的状态、过劳伤害和事件影响分
     */
    public DriverResult applyDriver(
            CharacterState character,
            ScholarState scholar,
            DriverPatch patch
    ) {
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

        int fatigueOffset = Utils.roundToInt(patch.fatigueOffset());
        int fatigueAfter = Utils.clamp(0, 100, character.characterPilao() + fatigueOffset);
        int exhaustionDamage = fatigueOffset > 0 ? exhaustionDamage(fatigueAfter) : 0;
        int healthAfter = Utils.clamp(
                0,
                100,
                character.characterJiankang()
                        + Utils.roundToInt(patch.healthOffset())
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
        BigDecimal impactScore = impactScore(character, characterAfter, scholar, scholarAfter);
        return new DriverResult(characterAfter, scholarAfter, exhaustionDamage, impactScore);
    }

    /**
     * 按书籍能力权重计算人物当前的综合书生能力。
     *
     * @param scholar 当前书生能力
     * @param book 当前书籍规则
     * @return 加权后的能力值
     */
    private BigDecimal weightedAbility(ScholarState scholar, BookRule book) {
        return BigDecimal.valueOf(scholar.abilityShizi()).multiply(Utils.ratio(book.abilityShiziWeight()))
                .add(BigDecimal.valueOf(scholar.abilityJingyi()).multiply(Utils.ratio(book.abilityJingyiWeight())))
                .add(BigDecimal.valueOf(scholar.abilityWenzhang()).multiply(Utils.ratio(book.abilityWenzhangWeight())))
                .add(BigDecimal.valueOf(scholar.abilityCelun()).multiply(Utils.ratio(book.abilityCelunWeight())))
                .add(BigDecimal.valueOf(scholar.abilityWenxue()).multiply(Utils.ratio(book.abilityWenxueWeight())));
    }

    /**
     * 从本回合学习池中计算一项能力的整数增长。
     *
     * @param learningPool 本回合可分配的学习量
     * @param weightPercentage 该项能力的百分比权重
     * @return 四舍五入后的非负能力增长
     */
    private int abilityGain(BigDecimal learningPool, int weightPercentage) {
        return Math.max(0, Utils.roundToInt(learningPool.multiply(Utils.ratio(weightPercentage))));
    }

    /**
     * 把五项能力增长应用到当前书生状态并收束到合法范围。
     *
     * @param current 当前书生能力
     * @param gain 五项能力的本次增长
     * @return 收束后的书生能力
     */
    private ScholarState applyAbilityGain(ScholarState current, ScholarState gain) {
        return new ScholarState(
                Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityShizi() + gain.abilityShizi()),
                Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityJingyi() + gain.abilityJingyi()),
                Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityWenzhang() + gain.abilityWenzhang()),
                Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityCelun() + gain.abilityCelun()),
                Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current.abilityWenxue() + gain.abilityWenxue())
        );
    }

    /**
     * 计算书生能力结算前后的实际差值。
     *
     * @param before 结算前能力
     * @param after 结算后能力
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
     * @param character 行动前人物状态
     * @param baseFatigue 行动基础疲劳
     * @return 行动后人物状态、实际疲劳增长和健康损失
     */
    private WorkCondition settleWorkCondition(CharacterState character, int baseFatigue) {
        int fatigueGain = fatigueGain(baseFatigue, character);
        int fatigueAfter = Utils.clamp(0, 100, character.characterPilao() + fatigueGain);
        int exhaustionDamage = exhaustionDamage(fatigueAfter);
        int healthAfter = Utils.clamp(0, 100, character.characterJiankang() - exhaustionDamage);
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
     * @param current 当前整数值
     * @param change 原始变化量
     * @return 四舍五入并收束后的整数值
     */
    private int applyBoundedChange(int current, BigDecimal change) {
        return Utils.clamp(ATTRIBUTE_MIN, ATTRIBUTE_MAX, current + Utils.roundToInt(change));
    }

    /**
     * 根据本次人物和职业能力的实际变化计算事件影响分。
     *
     * @param characterBefore 结算前人物状态
     * @param characterAfter 结算后人物状态
     * @param scholarBefore 结算前书生能力
     * @param scholarAfter 结算后书生能力
     * @return 保留两位小数的事件影响分
     */
    private BigDecimal impactScore(
            CharacterState characterBefore,
            CharacterState characterAfter,
            ScholarState scholarBefore,
            ScholarState scholarAfter
    ) {
        int attributeChange = Math.abs(characterAfter.characterZhili() - characterBefore.characterZhili())
                + Math.abs(characterAfter.characterDaode() - characterBefore.characterDaode())
                + Math.abs(characterAfter.characterZhengzhi() - characterBefore.characterZhengzhi())
                + Math.abs(characterAfter.characterJiaoji() - characterBefore.characterJiaoji())
                + Math.abs(characterAfter.characterTineng() - characterBefore.characterTineng());
        int abilityChange = Math.abs(scholarAfter.abilityShizi() - scholarBefore.abilityShizi())
                + Math.abs(scholarAfter.abilityJingyi() - scholarBefore.abilityJingyi())
                + Math.abs(scholarAfter.abilityWenzhang() - scholarBefore.abilityWenzhang())
                + Math.abs(scholarAfter.abilityCelun() - scholarBefore.abilityCelun())
                + Math.abs(scholarAfter.abilityWenxue() - scholarBefore.abilityWenxue());
        int healthChange = Math.abs(characterAfter.characterJiankang() - characterBefore.characterJiankang());
        int fatigueChange = Math.abs(characterAfter.characterPilao() - characterBefore.characterPilao());
        return BigDecimal.valueOf(attributeChange * 4L)
                .add(BigDecimal.valueOf(abilityChange * 4L))
                .add(BigDecimal.valueOf(healthChange).multiply(Utils.decimal("0.50")))
                .add(BigDecimal.valueOf(fatigueChange).multiply(Utils.decimal("0.25")))
                .setScale(2, RoundingMode.HALF_UP);
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
            int difficulty,
            int requiredProgress,
            int baseProgressPerTurn,
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
            boolean reachedUsable,
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
            int exhaustionDamage,
            BigDecimal impactScore
    ) implements Serializable {
    }
}
