package mvp.engine;

import org.springframework.stereotype.Component;

/**
 * 游戏时间与成长阶段引擎，负责推进回合并在指定年龄切换到考试流程。
 */
@Component
public class TurnEngine {

    public static final String EXAM_MENGXUE = "EXAM_MENGXUE";
    public static final String EXAM_JINGYI = "EXAM_JINGYI";
    public static final String EXAM_PRE_COUNTY = "EXAM_PRE_COUNTY";
    public static final String EXAM_XIANSHI = "EXAM_XIANSHI";

    /**
     * 创建玩家点击“开始人生”后的六岁时间状态。
     *
     * @return 1547年正月第一回合的求学状态
     */
    public TurnState startLife() {
        return new TurnState(
                GameRuleConstant.DEFAULT_BIRTH_YEAR,
                GameRuleConstant.DEFAULT_START_YEAR,
                1,
                1,
                GameRuleConstant.SCHOOL_START_AGE,
                0L,
                "STUDYING",
                "STUDYING"
        );
    }

    /**
     * 根据行动是否结束回合推进日历，并返回本次触发的考试节点。
     *
     * @param current 结算前的时间与流程状态
     * @param endTurn 当前行动是否消耗一个普通回合
     * @return 推进后的状态、触发的考试类型及是否为最终县试
     */
    public TurnResult advance(TurnState current, boolean endTurn) {
        if (!endTurn) {
            return new TurnResult(current, null, false);
        }

        long totalTurnNumber = current.totalTurnNumber() + 1;
        int currentYear = current.currentYear();
        int currentMonth = current.currentMonth();
        int turnInMonth = current.turnInMonth();

        if (turnInMonth < GameRuleConstant.TURNS_PER_MONTH) {
            turnInMonth++;
        } else {
            turnInMonth = 1;
            currentMonth++;
            if (currentMonth > 12) {
                currentMonth = 1;
                currentYear++;
            }
        }

        int age = currentYear - current.birthYear();
        String examType = age > current.age() ? examTypeAtAge(age) : null;
        boolean finalExam = EXAM_XIANSHI.equals(examType);
        String status = current.status();
        String growthStage = current.growthStage();
        if (examType != null) {
            status = finalExam ? "EXAM_READY" : "STAGE_EXAM_READY";
            growthStage = finalExam ? "EXAM" : "STAGE_EXAM";
        }

        TurnState result = new TurnState(
                current.birthYear(),
                currentYear,
                currentMonth,
                turnInMonth,
                age,
                totalTurnNumber,
                status,
                growthStage
        );
        return new TurnResult(result, examType, finalExam);
    }

    /**
     * 完成当前考试流程；阶段考试回到求学，县试结束本局。
     *
     * @param current 考试结算前的时间与流程状态
     * @param examType 已完成的考试类型编码
     * @return 考试完成后的流程状态
     */
    public TurnState completeExam(TurnState current, String examType) {
        if (EXAM_XIANSHI.equals(examType)) {
            return new TurnState(
                    current.birthYear(),
                    current.currentYear(),
                    current.currentMonth(),
                    current.turnInMonth(),
                    current.age(),
                    current.totalTurnNumber(),
                    "COMPLETED",
                    "COMPLETED"
            );
        }
        return new TurnState(
                current.birthYear(),
                current.currentYear(),
                current.currentMonth(),
                current.turnInMonth(),
                current.age(),
                current.totalTurnNumber(),
                "STUDYING",
                "STUDYING"
        );
    }

    /**
     * 查询指定年龄是否存在刚性考试节点。
     *
     * @param age 当前周岁
     * @return 对应考试类型；没有考试时返回null
     */
    public String examTypeAtAge(int age) {
        return switch (age) {
            case GameRuleConstant.FIRST_STAGE_EXAM_AGE -> EXAM_MENGXUE;
            case GameRuleConstant.SECOND_STAGE_EXAM_AGE -> EXAM_JINGYI;
            case GameRuleConstant.THIRD_STAGE_EXAM_AGE -> EXAM_PRE_COUNTY;
            case GameRuleConstant.COUNTY_EXAM_AGE -> EXAM_XIANSHI;
            default -> null;
        };
    }

    /**
     * 把公元年份转换为嘉靖纪年。
     *
     * @param currentYear 公元年份
     * @return 对应的嘉靖年序，例如1547返回26
     */
    public int jiajingYear(int currentYear) {
        return currentYear - 1521;
    }

    public record TurnState(
            int birthYear,
            int currentYear,
            int currentMonth,
            int turnInMonth,
            int age,
            long totalTurnNumber,
            String status,
            String growthStage
    ) {
    }

    public record TurnResult(
            TurnState state,
            String triggeredExamType,
            boolean finalExam
    ) {
    }
}
