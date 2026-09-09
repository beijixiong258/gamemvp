package mvp.engine;

import mvp.utils.Calculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 考试数值引擎，负责从人物状态和题目权重计算整数成绩，不生成考试叙事。 */
@Component
public class ExamEngine {

    /**
     * 创建考试数值快照，供思维泡泡Resolver和后续两种答题路径共同使用。
     *
     * @param knowledgeTotal 人物全部已读书籍贡献的学识总量
     * @return 已学知识值、基础能力分、临场偏移和思维层级
     */
    public ExamPreparation prepare(
            CharacterEngine.CharacterState character,
            CharacterEngine.ScholarState scholar,
            BigDecimal knowledgeTotal,
            ExamWeights weights
    ) {
        int knowledgeScore = knowledgeScore(knowledgeTotal);
        int baseAbilityScore = baseAbilityScore(character, scholar, knowledgeScore, weights);
        int stateOffset = stateOffset(character);
        return new ExamPreparation(
                knowledgeScore,
                baseAbilityScore,
                stateOffset,
                thoughtLevel(baseAbilityScore + stateOffset)
        );
    }

    /**
     * 将不封顶的总学识换算成考试知识分，首版每2学识为1分。
     *
     * @param knowledgeTotal 全部书籍贡献的学识总量
     * @return 0到100的考试知识分
     */
    public int knowledgeScore(BigDecimal knowledgeTotal) {
        return Calculator.clamp(0, 100, Calculator.roundToInt(
                Calculator.divide(knowledgeTotal, GameRuleConstant.KNOWLEDGE_PER_EXAM_POINT)));
    }

    /**
     * 普通骰点采用立方曲线，中间影响小、两端影响大。
     *
     * @param diceRoll 已保存的1D100骰点
     * @return -25至25的整数修正；1和100还会在最终结算覆盖结果
     */
    public int luckOffset(int diceRoll) {
        if (diceRoll < 1 || diceRoll > 100) {
            throw new IllegalArgumentException("骰点必须在1至100之间");
        }
        BigDecimal position = BigDecimal.valueOf(diceRoll).subtract(Calculator.decimal("50.5"))
                .divide(Calculator.decimal("49.5"), 8, RoundingMode.HALF_UP);
        return Calculator.roundToInt(position.pow(3).multiply(
                BigDecimal.valueOf(GameRuleConstant.EXAM_LUCK_AMPLITUDE)));
    }

    /**
     * 按题目权重汇总通用属性、书生能力和知识值。
     *
     * @return 0到100之间的整数基础能力分
     */
    public int baseAbilityScore(
            CharacterEngine.CharacterState character,
            CharacterEngine.ScholarState scholar,
            int knowledgeScore,
            ExamWeights weights
    ) {
        BigDecimal score = BigDecimal.valueOf(character.characterZhili()).multiply(weights.characterZhili())
                .add(BigDecimal.valueOf(character.characterDaode()).multiply(weights.characterDaode()))
                .add(BigDecimal.valueOf(character.characterZhengzhi()).multiply(weights.characterZhengzhi()))
                .add(BigDecimal.valueOf(character.characterJiaoji()).multiply(weights.characterJiaoji()))
                .add(BigDecimal.valueOf(character.characterTineng()).multiply(weights.characterTineng()))
                .add(BigDecimal.valueOf(scholar.abilityShizi()).multiply(weights.abilityShizi()))
                .add(BigDecimal.valueOf(scholar.abilityJingyi()).multiply(weights.abilityJingyi()))
                .add(BigDecimal.valueOf(scholar.abilityWenzhang()).multiply(weights.abilityWenzhang()))
                .add(BigDecimal.valueOf(scholar.abilityCelun()).multiply(weights.abilityCelun()))
                .add(BigDecimal.valueOf(scholar.abilityWenxue()).multiply(weights.abilityWenxue()))
                .add(BigDecimal.valueOf(knowledgeScore).multiply(weights.knowledge()));
        return Calculator.clamp(0, 100, Calculator.roundToInt(score));
    }

    /**
     * 根据健康、疲劳和体能计算确定的临场状态偏移。
     *
     * @return -8到4之间的整数状态偏移
     */
    public int stateOffset(CharacterEngine.CharacterState character) {
        BigDecimal healthModifier = BigDecimal.valueOf(character.characterJiankang() - 70L)
                .multiply(Calculator.decimal("0.06"));
        BigDecimal fatigueModifier = BigDecimal.valueOf(-Math.max(0, character.characterPilao() - 20L))
                .multiply(Calculator.decimal("0.08"));
        BigDecimal fitnessModifier = BigDecimal.valueOf(character.characterTineng() - 50L)
                .multiply(Calculator.decimal("0.03"));
        BigDecimal result = Calculator.clamp(
                Calculator.decimal("-8"),
                Calculator.decimal("4"),
                healthModifier.add(fatigueModifier).add(fitnessModifier)
        );
        return Calculator.roundToInt(result);
    }

    /**
     * 把当前可发挥分映射为思维泡泡层级。
     *
     * @param score 基础能力分与临场偏移之和
     * @return 供思维泡泡Resolver使用的层级
     */
    public ThoughtLevel thoughtLevel(int score) {
        if (score < 35) {
            return ThoughtLevel.DIFFICULT;
        }
        if (score < 60) {
            return ThoughtLevel.BASIC;
        }
        if (score < 80) {
            return ThoughtLevel.ORGANIZED;
        }
        return ThoughtLevel.CONFIDENT;
    }

    /**
     * 结算系统代行路径，在角色能力及身体状态基础上应用已冻结的骰点。
     *
     * @param diceRoll 考试开始时保存的骰点，不能在重传时重投
     * @param luckOffset 考试准备时已保存的普通骰点修正
     * @return 最终成绩与通过状态
     */
    public ExamResult settleAuto(int baseAbilityScore, int stateOffset, int diceRoll, int luckOffset, int passThreshold) {
        int finalScore = Calculator.clamp(0, 100, baseAbilityScore + stateOffset + luckOffset);
        return result(finalScore, 0, diceRoll, passThreshold);
    }

    /**
     * 结算玩家“以身入局”路径，在角色成绩上叠加答案内容修正。
     *
     * @param contentModifier AI给出的答案内容修正，先限制在-10至10，再取整
     * @param diceRoll 考试开始时保存的骰点，不能在重传时重投
     * @param luckOffset 考试准备时已保存的普通骰点修正
     * @return 最终成绩、实际采用的内容修正与通过状态
     */
    public ExamResult settlePlayer(
            int baseAbilityScore,
            int stateOffset,
            BigDecimal contentModifier,
            int diceRoll,
            int luckOffset,
            int passThreshold
    ) {
        int effectiveContentModifier = Calculator.clamp(
                BigDecimal.valueOf(-10), BigDecimal.TEN, contentModifier
        ).setScale(0, RoundingMode.HALF_UP).intValue();
        int finalScore = Calculator.clamp(
                0,
                100,
                baseAbilityScore + stateOffset + effectiveContentModifier + luckOffset
        );
        return result(finalScore, effectiveContentModifier, diceRoll, passThreshold);
    }

    /**
     * 根据最终分和分数线生成统一考试结果。
     *
     * @param finalScore 已收束的最终分
     * @param effectiveContentModifier 实际采用的玩家内容修正
     * @param diceRoll 考试开始时保存的骰点，不能在重传时重投
     * @return 包含完成状态的考试结果
     */
    private ExamResult result(int finalScore, int effectiveContentModifier, int diceRoll, int passThreshold) {
        finalScore = diceRoll == 1 ? 0 : diceRoll == 100 ? 100 : finalScore;
        boolean passed = diceRoll != 1 && (diceRoll == 100 || finalScore >= passThreshold);
        return new ExamResult(
                finalScore,
                effectiveContentModifier,
                passed,
                passed ? "COMPLETED_PASS" : "COMPLETED_FAIL"
        );
    }

    public enum ThoughtLevel {
        DIFFICULT,
        BASIC,
        ORGANIZED,
        CONFIDENT
    }

    public record ExamWeights(
            BigDecimal characterZhili,
            BigDecimal characterDaode,
            BigDecimal characterZhengzhi,
            BigDecimal characterJiaoji,
            BigDecimal characterTineng,
            BigDecimal abilityShizi,
            BigDecimal abilityJingyi,
            BigDecimal abilityWenzhang,
            BigDecimal abilityCelun,
            BigDecimal abilityWenxue,
            BigDecimal knowledge
    ) {
    }

    public record ExamPreparation(
            int knowledgeScore,
            int baseAbilityScore,
            int stateOffset,
            ThoughtLevel thoughtLevel
    ) {
    }

    public record ExamResult(
            int finalScore,
            int effectiveContentModifier,
            boolean passed,
            String status
    ) {
    }
}
