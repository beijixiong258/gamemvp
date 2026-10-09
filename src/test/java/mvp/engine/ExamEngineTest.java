package mvp.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExamEngineTest {
    private final CharacterEngine characters = new CharacterEngine();
    private final ExamEngine exams = new ExamEngine();

    @Test
    void allDiceAreMonotoneAndExtremeRollsOverrideContent() {
        int previous = -25;
        int zeroLuck = 0;
        for (int dice = 1; dice <= 100; dice++) {
            int luck = exams.luckOffset(dice);
            assertTrue(luck >= previous && luck >= -25 && luck <= 25);
            assertEquals(-luck, exams.luckOffset(101 - dice));
            if (luck == 0) zeroLuck++;
            previous = luck;
        }
        assertEquals(26, zeroLuck);
        assertFalse(exams.settlePlayer(100, 0, BigDecimal.valueOf(100), 1, -25, 33).passed());
        assertTrue(exams.settlePlayer(0, -5, BigDecimal.ZERO, 100, 25, 33).passed());
        assertEquals(76, passes(36, 0, 33));
        assertEquals(73, passes(35, 0, 33));
        assertThrows(IllegalArgumentException.class, () -> exams.luckOffset(0));
        assertThrows(IllegalArgumentException.class, () -> exams.luckOffset(101));
    }

    @Test
    void examConditionUsesRemainingStaminaWithInclusiveOverworkBoundary() {
        assertEquals(0, exams.stateOffset(character(20, 100, 21)));
        assertEquals(0, exams.stateOffset(character(20, 50, 21)));
        assertEquals(-3, exams.stateOffset(character(20, 100, 20)));
        assertEquals(-3, exams.stateOffset(character(20, 70, 20)));
        assertEquals(-2, exams.stateOffset(character(20, 25, 21)));
        assertEquals(-5, exams.stateOffset(character(20, 0, 20)));
        assertEquals(exams.stateOffset(character(20, 100, 21)),
                exams.stateOffset(character(100, 100, 21)));
        assertEquals(100, exams.knowledgeScore(BigDecimal.valueOf(305)));
    }

    @Test
    void playerAnswerDominatesOrdinaryDiceWithoutLosingExtremeRollRules() {
        for (int dice = 2; dice <= 99; dice++) {
            int luck = exams.luckOffset(dice);
            assertTrue(exams.settlePlayer(0, 0, BigDecimal.valueOf(80), dice, luck, 33).passed());
            assertFalse(exams.settlePlayer(100, 0, BigDecimal.valueOf(20), dice, luck, 33).passed());
            var result = exams.settlePlayer(36, 0, BigDecimal.valueOf(60), dice, luck, 33);
            assertEquals(result.finalScore() - (36 + luck), result.effectiveContentModifier());
        }
        assertEquals(42, exams.settlePlayer(0, 0, BigDecimal.valueOf(80), 50, 0, 33).finalScore());
        assertEquals(14, exams.settlePlayer(100, 0, BigDecimal.valueOf(20), 50, 0, 33).finalScore());
    }

    @Test
    void onlyIntelligenceAndScholarAbilitiesContributeToConfiguredBaseScore() throws IOException {
        var question = resource("exam.json").getJSONArray("exam").getJSONObject(3)
                .getJSONArray("question").getJSONObject(0);
        var weights = weights(question);
        var scholar = new CharacterEngine.ScholarState(25, 30, 71, 23, 20);
        int baseline = exams.baseAbilityScore(new CharacterEngine.CharacterState(40, 0, 0, 0, 0, 75, 0),
                scholar, 0, weights);
        assertEquals(36, baseline);
        assertEquals(baseline, exams.baseAbilityScore(new CharacterEngine.CharacterState(40, 100, 100, 100, 100, 75, 0),
                scholar, 100, weights));
    }

    @Test
    void configuredNormalRouteHasSeventySixCountyDicePassesWithoutAiGrowth() throws IOException {
        Route normal = route(7, 20, false);
        assertEquals(7, normal.completedBooks());
        assertEquals(20, normal.practiceCount());
        assertEquals(71, normal.scholar().abilityWenzhang());
        assertEquals(76, normal.snapshots().get(3).passingDice());
        // 这是普通阅读骰固定50的参照策略；仅穷举考试骰，不代表实测玩家通过率。
        printRoute("免费7书+20次练习", normal);
        for (var sample : List.of(
                new NamedRoute("只读免费7书", route(7, 0, false)),
                new NamedRoute("前3书+20次练习", route(3, 20, false)),
                new NamedRoute("前6书+20次练习", route(6, 20, false)),
                new NamedRoute("免费7书+100次练习", route(7, 100, false)),
                new NamedRoute("原10书理论路线+20次练习（不计购买预算）", route(10, 20, false)))) {
            printRoute(sample.name(), sample.route());
        }
        assertTrue(route(7, 100, false).snapshots().get(3).passingDice() > 76);
        assertTrue(route(10, 20, false).snapshots().get(3).passingDice() > 76);
    }

    @Test
    void freeCurriculumSurvivesOneTwoPointGeneralAttributeLoss() throws IOException {
        Route injured = route(7, 20, true);
        assertEquals(7, injured.completedBooks());
        assertEquals(38, injured.character().characterZhili());
        // 十二岁前只有前六本能读；名家文抄保留十二岁年龄限制。
        assertEquals(6, injured.snapshots().get(1).completedBooks());
        printRoute("一次通用属性-2后免费7书+20练习", injured);
    }

    private Route route(int bookCount, int practiceLimit, boolean initialInjury) throws IOException {
        // 新增选配书不自动混入原免费教材基准，避免目录排序改变校准含义。
        // 原10书路线假定全部持有，不模拟购买预算：三本付费书合计36600文，远超开局2000文。
        List<String> referenceCodes = List.of("BOOK_SANZIJING", "BOOK_QIANZIWEN", "BOOK_LUNYU", "BOOK_MENGZI",
                "BOOK_DAXUE", "BOOK_SHIJING", "BOOK_MINGJIAWENCHAO", "BOOK_JINGSHI_CELUN", "BOOK_BAGU_POTI", "BOOK_GPT1_WEIGHTS");
        JSONArray definitions = resource("book.json").getJSONArray("book");
        JSONArray books = new JSONArray();
        for (String code : referenceCodes) {
            JSONObject definition = definitions.toList(JSONObject.class).stream()
                    .filter(book -> code.equals(book.getStr("equipmentCode"))).findFirst().orElseThrow();
            books.add(definition);
        }
        JSONArray examRules = resource("exam.json").getJSONArray("exam");
        var start = characters.startLife("TEST");
        CharacterEngine.CharacterState character = start.character();
        CharacterEngine.ScholarState scholar = start.scholar();
        if (initialInjury) {
            character = characters.enterIllness(character);
            for (int i = 0; i < GameRuleConstant.SICK_TURNS; i++)
                character = characters.recoverIllnessTurn(character, i == GameRuleConstant.SICK_TURNS - 1);
        }
        Map<String, CharacterEngine.BookProgress> progress = new LinkedHashMap<>();
        List<Snapshot> snapshots = new ArrayList<>();
        int practiced = 0;
        int readings = 0;
        int rests = 0;
        for (int turn = 1; turn <= 360; turn++) {
            int age = GameRuleConstant.SCHOOL_START_AGE + (turn - 1) / 36;
            JSONObject selected = null;
            for (int i = 0; i < bookCount; i++) {
                JSONObject book = books.getJSONObject(i);
                var current = progress.getOrDefault(book.getStr("equipmentCode"), emptyProgress());
                if (!current.completed() && meets(book, age, character, scholar, progress)) {
                    selected = book;
                    break;
                }
            }
            int cost = selected != null ? selected.getInt("fatigueCost")
                    : practiced < practiceLimit ? GameRuleConstant.PRACTICE_STAMINA_COST : 0;
            if (cost > 0 && character.stamina() - cost <= GameRuleConstant.OVERWORK_STAMINA_THRESHOLD) {
                character = characters.rest(character).character();
                rests++;
            } else if (selected != null) {
                String code = selected.getStr("equipmentCode");
                var result = characters.readBook(character, scholar, bookRule(selected),
                        progress.getOrDefault(code, emptyProgress()), turn, 50);
                character = result.character();
                scholar = result.scholar();
                progress.put(code, result.progress());
                readings++;
            } else if (practiced < practiceLimit) {
                var result = characters.practiceWriting(character, scholar);
                character = result.character();
                scholar = result.scholar();
                practiced++;
            } else if (character.stamina() < character.maxStamina() || character.characterJiankang() < GameRuleConstant.REST_HEALTH_CAP) {
                character = characters.rest(character).character();
                rests++;
            }
            if (turn == 72 || turn == 216 || turn == 324 || turn == 360) {
                // 与业务一致：入场休整补满体力，再冻结本场准备分。
                character = characters.recoverStamina(character, character.maxStamina());
                JSONObject question = examRules.getJSONObject(snapshots.size()).getJSONArray("question").getJSONObject(0);
                BigDecimal knowledge = BigDecimal.ZERO;
                for (int i = 0; i < bookCount; i++) {
                    JSONObject book = books.getJSONObject(i);
                    int current = progress.getOrDefault(book.getStr("equipmentCode"), emptyProgress()).currentProgress();
                    knowledge = knowledge.add(characters.knowledgeContribution(book.getInt("totalKnowledge"), current));
                }
                var preparation = exams.prepare(character, scholar, knowledge, weights(question));
                int threshold = question.getInt("passThreshold");
                snapshots.add(new Snapshot(preparation.baseAbilityScore(), preparation.stateOffset(), threshold,
                        passes(preparation.baseAbilityScore(), preparation.stateOffset(), threshold), completed(progress)));
                // 参照路线直接交卷，不调用可选思路；成功交卷扣一次考试固定体力成本。
                character = characters.consumeStamina(character, GameRuleConstant.EXAM_STAMINA_COST);
            }
        }
        return new Route(character, scholar, completed(progress), practiced, readings, rests, snapshots);
    }

    private boolean meets(JSONObject book, int age, CharacterEngine.CharacterState character,
                          CharacterEngine.ScholarState scholar, Map<String, CharacterEngine.BookProgress> progress) {
        for (Object entry : book.getJSONArray("readingRequirement")) {
            JSONObject requirement = (JSONObject) entry;
            String target = requirement.getStr("target");
            int actual = switch (requirement.getStr("type")) {
                case "MIN_AGE" -> age;
                case "MIN_GENERAL_ATTRIBUTE" -> switch (target) {
                    case "characterZhili" -> character.characterZhili();
                    case "characterZhengzhi" -> character.characterZhengzhi();
                    default -> throw new IllegalArgumentException(target);
                };
                case "MIN_CAREER_ABILITY" -> switch (target) {
                    case "abilityShizi" -> scholar.abilityShizi();
                    case "abilityWenzhang" -> scholar.abilityWenzhang();
                    default -> throw new IllegalArgumentException(target);
                };
                case "BOOK_PROGRESS" -> progress.getOrDefault(target, emptyProgress()).currentProgress();
                default -> throw new IllegalArgumentException(requirement.toString());
            };
            if (actual < requirement.getInt("value")) return false;
        }
        return true;
    }

    private CharacterEngine.BookRule bookRule(JSONObject book) {
        JSONObject r = book.getJSONObject("readingReward");
        return new CharacterEngine.BookRule(new CharacterEngine.ReadingReward(
                r.getInt("characterZhili"), r.getInt("characterDaode"), r.getInt("characterZhengzhi"),
                r.getInt("characterJiaoji"), r.getInt("characterTineng"), r.getInt("abilityShizi"),
                r.getInt("abilityJingyi"), r.getInt("abilityWenzhang"), r.getInt("abilityCelun"),
                r.getInt("abilityWenxue")), book.getInt("fatigueCost"));
    }

    private ExamEngine.ExamWeights weights(JSONObject question) {
        JSONObject g = question.getJSONObject("generalAbilityWeight");
        JSONObject c = question.getJSONObject("careerAbilityWeight");
        var weights = new ExamEngine.ExamWeights(g.getBigDecimal("characterZhili"), g.getBigDecimal("characterDaode"),
                g.getBigDecimal("characterZhengzhi"), g.getBigDecimal("characterJiaoji"), g.getBigDecimal("characterTineng"),
                c.getBigDecimal("abilityShizi"), c.getBigDecimal("abilityJingyi"), c.getBigDecimal("abilityWenzhang"),
                c.getBigDecimal("abilityCelun"), c.getBigDecimal("abilityWenxue"), question.getBigDecimal("knowledgeWeight"));
        BigDecimal sum = question.getBigDecimal("knowledgeWeight");
        for (String key : g.keySet()) sum = sum.add(g.getBigDecimal(key));
        for (String key : c.keySet()) sum = sum.add(c.getBigDecimal(key));
        assertEquals(0, BigDecimal.ONE.compareTo(sum));
        assertEquals(0, new BigDecimal("0.10").compareTo(g.getBigDecimal("characterZhili")));
        for (String key : g.keySet())
            if (!"characterZhili".equals(key)) assertEquals(0, BigDecimal.ZERO.compareTo(g.getBigDecimal(key)));
        assertEquals(0, BigDecimal.ZERO.compareTo(question.getBigDecimal("knowledgeWeight")));
        return weights;
    }

    private int passes(int base, int state, int threshold) {
        int passed = 0;
        for (int dice = 1; dice <= 100; dice++)
            if (exams.settleAuto(base, state, dice, exams.luckOffset(dice), threshold).passed()) passed++;
        return passed;
    }

    private JSONObject resource(String filename) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/game/" + filename)) {
            assertNotNull(input, filename);
            return JSONUtil.parseObj(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private CharacterEngine.CharacterState character(int fitness, int health, int stamina) {
        return new CharacterEngine.CharacterState(20, 20, 20, 20, fitness, health,
                CharacterEngine.maxStamina(fitness) - stamina);
    }

    private CharacterEngine.BookProgress emptyProgress() {
        return new CharacterEngine.BookProgress(0, 0, false, null, CharacterEngine.ReadingReward.ZERO);
    }

    private int completed(Map<String, CharacterEngine.BookProgress> progress) {
        return (int) progress.values().stream().filter(CharacterEngine.BookProgress::completed).count();
    }

    private void printRoute(String name, Route route) {
        System.out.printf("%s: 读满%d本, 读%d次/练%d次/休%d次, 文章%d, 节点=%s%n", name,
                route.completedBooks(), route.readings(), route.practiceCount(), route.rests(),
                route.scholar().abilityWenzhang(), route.snapshots());
    }

    private record Snapshot(int base, int state, int threshold, int passingDice, int completedBooks) { }
    private record Route(CharacterEngine.CharacterState character, CharacterEngine.ScholarState scholar,
                         int completedBooks, int practiceCount, int readings, int rests, List<Snapshot> snapshots) { }
    private record NamedRoute(String name, Route route) { }
}
