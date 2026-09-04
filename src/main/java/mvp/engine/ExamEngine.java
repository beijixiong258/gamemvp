package mvp.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 考试数值引擎，负责从人物状态和题目权重计算整数成绩，不生成考试叙事。
 */
public class ExamEngine {

    /**
     * 创建考试数值快照，供思维泡泡Resolver和后续两种答题路径共同使用。
     *
     * @param character 当前人物状态
     * @param scholar 当前书生能力
     * @param bookProgresses 人物各本书的整数阅读进度
     * @param weights 当前考试题目的计分权重
     * @return 已学知识值、基础能力分、临场偏移和思维层级
     */
    public ExamPreparation prepare(
            CharacterEngine.CharacterState character,
            CharacterEngine.ScholarState scholar,
            List<Integer> bookProgresses,
            ExamWeights weights
    ) {
        int knowledgeScore = knowledgeScore(bookProgresses);
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
     * 把阅读进度转换成知识阶段分，并按最高三本书汇总。
     *
     * @param bookProgresses 人物各本书的整数阅读进度
     * @return 0到100之间的整数知识值
     */
    public int knowledgeScore(List<Integer> bookProgresses) {
        List<Integer> topScores = bookProgresses.stream()
                .map(this::knowledgeStageScore)
                .sorted(Comparator.reverseOrder())
                .limit(3)
                .toList();
        int first = topScores.size() > 0 ? topScores.get(0) : 0;
        int second = topScores.size() > 1 ? topScores.get(1) : 0;
        int third = topScores.size() > 2 ? topScores.get(2) : 0;
        return Utils.roundToInt(
                BigDecimal.valueOf(first).multiply(Utils.decimal("0.50"))
                        .add(BigDecimal.valueOf(second).multiply(Utils.decimal("0.30")))
                        .add(BigDecimal.valueOf(third).multiply(Utils.decimal("0.20")))
        );
    }

    /**
     * 按题目权重汇总通用属性、书生能力和知识值。
     *
     * @param character 当前人物状态
     * @param scholar 当前书生能力
     * @param knowledgeScore 已学知识值
     * @param weights 当前考试题目的计分权重
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
        return Utils.clamp(0, 100, Utils.roundToInt(score));
    }

    /**
     * 根据健康、疲劳和体能计算确定的临场状态偏移。
     *
     * @param character 考试开始时的人物状态
     * @return -8到4之间的整数状态偏移
     */
    public int stateOffset(CharacterEngine.CharacterState character) {
        BigDecimal healthModifier = BigDecimal.valueOf(character.characterJiankang() - 70L)
                .multiply(Utils.decimal("0.06"));
        BigDecimal fatigueModifier = BigDecimal.valueOf(-Math.max(0, character.characterPilao() - 20L))
                .multiply(Utils.decimal("0.08"));
        BigDecimal fitnessModifier = BigDecimal.valueOf(character.characterTineng() - 50L)
                .multiply(Utils.decimal("0.03"));
        BigDecimal result = Utils.clamp(
                Utils.decimal("-8"),
                Utils.decimal("4"),
                healthModifier.add(fatigueModifier).add(fitnessModifier)
        );
        return Utils.roundToInt(result);
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
     * 结算系统代行路径，成绩只由角色能力和临场状态决定。
     *
     * @param baseAbilityScore 基础能力分
     * @param stateOffset 临场状态偏移
     * @param passThreshold 当前考试通过线
     * @return 最终成绩与通过状态
     */
    public ExamResult settleAuto(int baseAbilityScore, int stateOffset, int passThreshold) {
        int finalScore = Utils.clamp(0, 100, baseAbilityScore + stateOffset);
        return result(finalScore, 0, passThreshold);
    }

    /**
     * 结算玩家“以身入局”路径，在角色成绩上叠加答案内容修正。
     *
     * @param baseAbilityScore 基础能力分
     * @param stateOffset 临场状态偏移
     * @param contentModifier 答案评价Resolver给出的内容修正
     * @param passThreshold 当前考试通过线
     * @return 最终成绩、实际采用的内容修正与通过状态
     */
    public ExamResult settlePlayer(
            int baseAbilityScore,
            int stateOffset,
            BigDecimal contentModifier,
            int passThreshold
    ) {
        int effectiveContentModifier = Utils.clamp(
                -25,
                25,
                contentModifier.setScale(0, RoundingMode.HALF_UP).intValue()
        );
        int finalScore = Utils.clamp(
                0,
                100,
                baseAbilityScore + stateOffset + effectiveContentModifier
        );
        return result(finalScore, effectiveContentModifier, passThreshold);
    }

    /**
     * 把一本书的阅读进度映射为考试知识阶段分。
     *
     * @param progress 该书的整数阅读进度
     * @return 0、35、70或100
     */
    private int knowledgeStageScore(int progress) {
        if (progress >= 100) {
            return 100;
        }
        if (progress >= 60) {
            return 70;
        }
        if (progress >= 25) {
            return 35;
        }
        return 0;
    }

    /**
     * 根据最终分和分数线生成统一考试结果。
     *
     * @param finalScore 已收束的最终分
     * @param effectiveContentModifier 实际采用的玩家内容修正
     * @param passThreshold 当前考试通过线
     * @return 包含完成状态的考试结果
     */
    private ExamResult result(int finalScore, int effectiveContentModifier, int passThreshold) {
        boolean passed = finalScore >= passThreshold;
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
