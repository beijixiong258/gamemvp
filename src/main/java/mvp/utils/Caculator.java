package mvp.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Caculator {

    static final BigDecimal ONE = BigDecimal.ONE;

    private Caculator() {
    }

    static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    static BigDecimal ratio(int percentage) {
        return BigDecimal.valueOf(percentage).movePointLeft(2);
    }

    static BigDecimal divide(BigDecimal value, int divisor) {
        return value.divide(BigDecimal.valueOf(divisor), 8, RoundingMode.HALF_UP);
    }

    static int roundToInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    static int clamp(int min, int max, int value) {
        return Math.min(max, Math.max(min, value));
    }

    static BigDecimal clamp(BigDecimal min, BigDecimal max, BigDecimal value) {
        return value.min(max).max(min);
    }
}
