package mvp.constant;

public final class GameRuleConstant {

    public static final int TURNS_PER_MONTH = 4;
    public static final int SCHOOL_START_AGE = 6;
    public static final int COUNTY_EXAM_AGE = 16;
    public static final int COPPER_WEN_PER_SILVER_LIANG = 1_000;
    public static final int SILVER_LIANG_PER_GOLD_LIANG = 20;
    public static final int COPPER_WEN_PER_GOLD_LIANG = COPPER_WEN_PER_SILVER_LIANG * SILVER_LIANG_PER_GOLD_LIANG;

    private GameRuleConstant() {
    }
}
