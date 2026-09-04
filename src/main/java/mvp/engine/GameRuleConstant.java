package mvp.engine;

public final class GameRuleConstant {

    public static final int TURNS_PER_MONTH = 4;
    public static final int DEFAULT_START_YEAR = 1547;
    public static final int SCHOOL_START_AGE = 6;
    public static final int DEFAULT_BIRTH_YEAR = DEFAULT_START_YEAR - SCHOOL_START_AGE;
    public static final int FIRST_STAGE_EXAM_AGE = 8;
    public static final int SECOND_STAGE_EXAM_AGE = 12;
    public static final int THIRD_STAGE_EXAM_AGE = 15;
    public static final int COUNTY_EXAM_AGE = 16;
    public static final int INITIAL_GENERAL_ATTRIBUTE = 20;
    public static final int INITIAL_LITERACY_ABILITY = 5;
    public static final int MIN_INITIAL_FAMILY_WEALTH = 10_000;
    public static final int DEFAULT_INITIAL_FAMILY_WEALTH = 100_000;
    public static final int MAX_INITIAL_FAMILY_WEALTH = 1_000_000;
    public static final String DEFAULT_FAMILY_BACKGROUND_SUMMARY =
            "家中有一定田产和积蓄，能够供角色入塾读书。";
    public static final int COPPER_WEN_PER_SILVER_LIANG = 1_000;
    public static final int SILVER_LIANG_PER_GOLD_LIANG = 20;
    public static final int COPPER_WEN_PER_GOLD_LIANG = COPPER_WEN_PER_SILVER_LIANG * SILVER_LIANG_PER_GOLD_LIANG;

    private GameRuleConstant() {
    }
}
