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
     * 将总学识归一化为快照参考分；当前MVP知识权重为0，不计入成绩。
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
     * 根据健康和绝对剩余体力计算临场修正，体能只通过体力上限间接作用。
     *
     * @return -5到0之间的整数状态偏移；健康不低于50且不过劳时无惩罚
     */
    public int stateOffset(CharacterEngine.CharacterState character) {
        BigDecimal healthModifier = BigDecimal.ONE.subtract(CharacterEngine.healthFactor(character.characterJiankang()))
                .multiply(BigDecimal.valueOf(-6));
        BigDecimal overworkModifier = character.stamina() <= GameRuleConstant.OVERWORK_STAMINA_THRESHOLD
                ? BigDecimal.valueOf(-3) : BigDecimal.ZERO;
        BigDecimal result = Calculator.clamp(
                Calculator.decimal("-5"),
                BigDecimal.ZERO,
                healthModifier.add(overworkModifier)
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
     * 结算系统操作路径，在角色能力及身体状态基础上应用已冻结的骰点。
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
     * 结算玩家“以身入局”路径，以答卷相对60分的表现主导相对及格线的成绩。
     *
     * @param answerScore AI给出的0至100答卷分；答卷权重80%，角色权重20%，普通运气缩小到20%
     * @param diceRoll 考试开始时保存的骰点，不能在重传时重投
     * @param luckOffset 考试准备时已保存的普通骰点修正
     * @return 最终成绩、相对原自动基线B+R+L的最终调整与通过状态；1/100仍强制覆盖结果
     */
    public ExamResult settlePlayer(
            int baseAbilityScore,
            int stateOffset,
            BigDecimal answerScore,
            int diceRoll,
            int luckOffset,
            int passThreshold
    ) {
        if (answerScore == null) throw new IllegalArgumentException("缺少答卷评分");
        BigDecimal boundedAnswer = Calculator.clamp(BigDecimal.ZERO, BigDecimal.valueOf(100), answerScore);
        BigDecimal answerContribution = boundedAnswer
                .subtract(BigDecimal.valueOf(GameRuleConstant.PLAYER_EXAM_ANSWER_PASS_SCORE))
                .multiply(Calculator.ratio(GameRuleConstant.PLAYER_EXAM_ANSWER_WEIGHT_PERCENT));
        BigDecimal characterContribution = BigDecimal.valueOf(baseAbilityScore - passThreshold)
                .multiply(Calculator.ratio(GameRuleConstant.PLAYER_EXAM_CHARACTER_WEIGHT_PERCENT));
        int reducedLuck = Calculator.roundToInt(BigDecimal.valueOf(luckOffset)
                .multiply(Calculator.ratio(GameRuleConstant.PLAYER_EXAM_LUCK_WEIGHT_PERCENT)));
        int finalScore = Calculator.clamp(0, 100, passThreshold
                + Calculator.roundToInt(answerContribution.add(characterContribution)) + stateOffset + reducedLuck);
        ExamResult settled = result(finalScore, 0, diceRoll, passThreshold);
        return new ExamResult(settled.finalScore(),
                settled.finalScore() - (baseAbilityScore + stateOffset + luckOffset),
                settled.passed(), settled.status());
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
