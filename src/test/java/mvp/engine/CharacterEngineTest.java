package mvp.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CharacterEngineTest {
    private final CharacterEngine engine = new CharacterEngine();

    @Test
    void normalizedStaminaCurveHasExactAnchorsAndIntegerBounds() {
        int[][] samples = {{0, 50}, {10, 51}, {20, 54}, {25, 57}, {40, 76},
                {50, 100}, {60, 123}, {75, 142}, {90, 148}, {100, 150}};
        for (int[] sample : samples) assertEquals(sample[1], CharacterEngine.maxStamina(sample[0]));
        int previous = 50;
        for (int fitness = 0; fitness <= 100; fitness++) {
            int maximum = CharacterEngine.maxStamina(fitness);
            assertTrue(maximum >= previous && maximum >= 50 && maximum <= 150);
            previous = maximum;
        }
        assertEquals(50, CharacterEngine.maxStamina(-1));
        assertEquals(150, CharacterEngine.maxStamina(101));
        assertEquals(54, engine.startLife("TEST").character().stamina());
    }

    @Test
    void consumptionIsAtomicAndTwentyIsOverwork() {
        var before = character(20, 75, 25);
        var after = engine.consumeStamina(before, 5);
        assertEquals(20, after.stamina());
        assertEquals(74, after.characterJiankang());
        assertEquals(75, engine.consumeStamina(character(20, 75, 26), 5).characterJiankang());
        assertEquals(before, engine.consumeStamina(before, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.consumeStamina(before, 26));
        assertThrows(IllegalArgumentException.class, () -> engine.consumeStamina(before, -1));
        assertEquals(25, before.stamina());
        assertEquals(0, engine.consumeStamina(character(20, 0, 5), 5).characterJiankang());
        assertEquals(0, new BigDecimal("0.80").compareTo(engine.conditionFactor(character(20, 100, 20))));
        assertEquals(0, BigDecimal.ONE.compareTo(engine.conditionFactor(character(20, 100, 21))));
    }

    @Test
    void healthCurveOnlyPenalizesBelowFiftyAndHasExactAnchors() {
        assertEquals(0, new BigDecimal("0.50").compareTo(CharacterEngine.healthFactor(0)));
        assertEquals(0, new BigDecimal("0.75").compareTo(CharacterEngine.healthFactor(25)));
        BigDecimal previous = CharacterEngine.healthFactor(0);
        for (int health = 0; health <= 100; health++) {
            BigDecimal factor = CharacterEngine.healthFactor(health);
            assertTrue(factor.compareTo(previous) >= 0 && factor.compareTo(BigDecimal.ONE) <= 0);
            if (health >= 50) assertEquals(0, BigDecimal.ONE.compareTo(factor));
            previous = factor;
        }
    }

    @Test
    void fitnessChangesPreserveAbsoluteStaminaAndAiCannotRecoverIt() {
        var before = character(49, 80, 80);
        var raised = engine.applyDriver(before, scholar(), fitnessPatch(3, -100)).character();
        assertEquals(52, raised.characterTineng());
        assertEquals(80, raised.stamina());
        assertTrue(raised.maxStamina() > before.maxStamina());
        var lowered = engine.applyDriver(character(51, 80, 102), scholar(), fitnessPatch(-3, 100)).character();
        assertEquals(48, lowered.characterTineng());
        assertEquals(lowered.maxStamina(), lowered.stamina());
        var reward = new CharacterEngine.ReadingReward(0, 0, 0, 0, 10, 0, 0, 0, 0, 0);
        assertEquals(80, engine.applyReadingAttributes(before, reward).stamina());
        assertEquals(80, engine.enterIllness(before).stamina());
    }

    @Test
    void fixedRestRecoveryDoesNotScaleWithFitness() {
        for (int fitness : new int[]{0, 20, 50, 100}) {
            var result = engine.rest(character(fitness, 60, 10));
            assertEquals(30, result.character().stamina());
            assertEquals(20, result.fatigueRecovery());
            assertEquals(5, result.healthRecovery());
        }
        var capped = engine.rest(character(20, 72, 50));
        assertEquals(54, capped.character().stamina());
        assertEquals(4, capped.fatigueRecovery());
        assertEquals(3, capped.healthRecovery());
        var alreadyHigh = engine.rest(character(20, 90, 50));
        assertEquals(90, alreadyHigh.character().characterJiankang());
        assertEquals(0, alreadyHigh.healthRecovery());
    }

    @Test
    void manualReadingOnlyChargesTheBookCost() {
        var book = new CharacterEngine.BookRule(CharacterEngine.ReadingReward.ZERO, 4);
        var progress = new CharacterEngine.BookProgress(0, 0, false, null, CharacterEngine.ReadingReward.ZERO);
        var result = engine.readBookAsPlayer(character(20, 75, 24), scholar(), book, progress, 0, 80);
        assertEquals(20, result.character().stamina());
        assertEquals(74, result.character().characterJiankang());
        assertEquals(4, result.fatigueGain());
        assertEquals(1, result.exhaustionDamage());
        assertThrows(IllegalArgumentException.class,
                () -> engine.readBookAsPlayer(character(20, 75, 3), scholar(), book, progress, 0, 80));
        var noProgress = engine.readBook(character(20, 75, 24), scholar(), book, progress, 0, 1);
        assertEquals(0, noProgress.progressGain());
        assertEquals(20, noProgress.character().stamina());
        assertEquals(74, noProgress.character().characterJiankang());
    }

    @Test
    void ordinaryAiHealthRecoveryStopsAtSeventyFiveWithoutLoweringHigherHealth() {
        assertEquals(75, engine.applyDriver(character(20, 74, 50), scholar(), healthPatch(10))
                .character().characterJiankang());
        assertEquals(90, engine.applyDriver(character(20, 90, 50), scholar(), healthPatch(10))
                .character().characterJiankang());
        assertEquals(80, engine.applyDriver(character(20, 90, 50), scholar(), healthPatch(-10))
                .character().characterJiankang());
    }

    @Test
    void practiceGrowthHasNoIntelligenceOrBookOrderBonus() {
        var bookReward = new CharacterEngine.ReadingReward(20, 0, 0, 0, 0, 0, 0, 31, 0, 0);
        var beforeBooks = character(20, 75, 54);
        var afterBooks = engine.applyReadingAttributes(beforeBooks, bookReward);
        var practiceFirst = scholar();
        var booksFirst = engine.applyReadingAbilities(scholar(), bookReward);
        // 比较同样身体状态下的成长；完整回合与休息消耗由路线测试覆盖。
        for (int i = 0; i < 20; i++) {
            practiceFirst = engine.practiceWriting(beforeBooks, practiceFirst).scholar();
            booksFirst = engine.practiceWriting(afterBooks, booksFirst).scholar();
        }
        practiceFirst = engine.applyReadingAbilities(practiceFirst, bookReward);
        assertEquals(71, booksFirst.abilityWenzhang());
        assertEquals(booksFirst, practiceFirst);
        assertEquals(8, engine.practiceWriting(beforeBooks, scholar()).fatigueGain());
        assertEquals(1, engine.practiceWriting(character(20, 24, 54), scholar()).writingAbilityGain());
    }

    private CharacterEngine.CharacterState character(int fitness, int health, int stamina) {
        return new CharacterEngine.CharacterState(20, 20, 20, 20, fitness, health,
                CharacterEngine.maxStamina(fitness) - stamina);
    }

    private CharacterEngine.ScholarState scholar() {
        return new CharacterEngine.ScholarState(5, 0, 0, 0, 0);
    }

    private CharacterEngine.DriverPatch fitnessPatch(int fitness, int fatigue) {
        return new CharacterEngine.DriverPatch(null, null, null, null, BigDecimal.valueOf(fitness),
                null, null, null, null, null, BigDecimal.valueOf(fatigue), null);
    }

    private CharacterEngine.DriverPatch healthPatch(int health) {
        return new CharacterEngine.DriverPatch(null, null, null, null, null,
                null, null, null, null, null, null, BigDecimal.valueOf(health));
    }
}
