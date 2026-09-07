package mvp.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ExamEngineTest {
    private final ExamEngine exams = new ExamEngine();

    @ParameterizedTest
    @CsvSource({"-1E100,-10", "-30,-10", "-10,-10", "-9.5,-10", "-0.5,-1",
            "-0.4,0", "0,0", "0.4,0", "0.5,1", "8,8", "9.5,10", "30,10", "1E100,10"})
    void contentModifierIsRoundedAndLimitedBeforeIntegerConversion(String raw, int expected) {
        var result = exams.settlePlayer(50, 0, new BigDecimal(raw), 50, 0, 60);
        assertEquals(expected, result.effectiveContentModifier());
        assertEquals(50 + expected, result.finalScore());
        assertEquals(result.finalScore() >= 60, result.passed());
    }

    @ParameterizedTest
    @CsvSource({"1,0,false", "100,100,true"})
    void extremeDiceOverrideBothAnswerModes(int dice, int score, boolean passed) {
        var player = exams.settlePlayer(50, 0, BigDecimal.TEN, dice, exams.luckOffset(dice), 60);
        var auto = exams.settleAuto(50, 0, dice, exams.luckOffset(dice), 60);
        assertEquals(score, player.finalScore());
        assertEquals(score, auto.finalScore());
        assertEquals(passed, player.passed());
        assertEquals(passed, auto.passed());
    }

    @Test
    void finalScoreStaysBetweenZeroAndOneHundred() {
        assertEquals(100, exams.settlePlayer(100, 4, BigDecimal.TEN, 99, 24, 60).finalScore());
        assertEquals(0, exams.settlePlayer(0, -8, BigDecimal.TEN.negate(), 2, -24, 60).finalScore());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-1,0", "0.4,0", "1,2"})
    void exhaustionOnlyAppliesToPositiveRoundedFatigue(String fatigue, int damage) {
        var character = new CharacterEngine.CharacterState(20, 20, 20, 20, 20, 80, 90);
        var scholar = new CharacterEngine.ScholarState(5, 0, 0, 0, 0);
        BigDecimal zero = BigDecimal.ZERO;
        var patch = new CharacterEngine.DriverPatch(zero, zero, zero, zero, zero, zero,
                zero, zero, zero, zero, new BigDecimal(fatigue), zero);
        var result = new CharacterEngine().applyDriver(character, scholar, patch);
        assertEquals(damage, result.exhaustionDamage());
        assertEquals(80 - damage, result.character().characterJiankang());
    }
}
