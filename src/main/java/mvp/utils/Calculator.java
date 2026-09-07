package mvp.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 统一引擎的数值精度、取整和范围限制。 */
public final class Calculator {

    public static final BigDecimal ONE = BigDecimal.ONE;

    private Calculator() {
    }

    public static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    public static BigDecimal ratio(int percentage) {
        return BigDecimal.valueOf(percentage).movePointLeft(2);
    }

    public static BigDecimal divide(BigDecimal value, int divisor) {
        return value.divide(BigDecimal.valueOf(divisor), 8, RoundingMode.HALF_UP);
    }

    public static int roundToInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    public static int clamp(int min, int max, int value) {
        return Math.min(max, Math.max(min, value));
    }

    public static BigDecimal clamp(BigDecimal min, BigDecimal max, BigDecimal value) {
        return value.min(max).max(min);
    }
}
