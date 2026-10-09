package mvp.engine;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BookCatalogTest {
    private static final List<String> ATTRIBUTES = List.of("characterZhili", "characterDaode", "characterZhengzhi",
            "characterJiaoji", "characterTineng");
    private static final List<String> ABILITIES = List.of("abilityShizi", "abilityJingyi", "abilityWenzhang",
            "abilityCelun", "abilityWenxue");
    private static final List<String> NORMAL_RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC");
    private static final Set<String> REWARD_FIELDS = Set.of("characterZhili", "characterDaode", "characterZhengzhi",
            "characterJiaoji", "characterTineng", "abilityShizi", "abilityJingyi", "abilityWenzhang", "abilityCelun", "abilityWenxue");

    @Test
    void fifteenUniqueBooksMatchEquipmentWithSevenFreeAndEightPaid() throws IOException {
        Catalog catalog = catalog();
        assertEquals(15, catalog.books().size());
        Set<String> equipmentBooks = catalog.equipment().entrySet().stream()
                .filter(entry -> "BOOK".equals(entry.getValue().getStr("equipmentType")))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        assertEquals(catalog.books().keySet(), equipmentBooks);
        int free = 0;
        int paid = 0;
        for (String code : catalog.books().keySet()) {
            JSONObject equipment = catalog.equipment().get(code);
            int price = integer(equipment, "price", code);
            assertTrue(price >= 0, code);
            if (price == 0) {
                free++;
                assertEquals("NPC_DOUBAO", equipment.getStr("supplierNpcCode"), code);
            } else {
                paid++;
                assertEquals("NPC_SHANGREN", equipment.getStr("supplierNpcCode"), code);
            }
        }
        assertEquals(7, free);
        assertEquals(8, paid);
    }

    @Test
    void allBookCostsRewardsWeightsAndRequirementsAreWellFormed() throws IOException {
        Catalog catalog = catalog();
        for (var entry : catalog.books().entrySet()) {
            String code = entry.getKey();
            JSONObject book = entry.getValue();
            assertTrue(integer(book, "fatigueCost", code) > 0, code);
            assertTrue(integer(book, "totalKnowledge", code) > 0, code);
            JSONObject reward = assertInstanceOf(JSONObject.class, book.get("readingReward"), code);
            assertEquals(REWARD_FIELDS, reward.keySet(), code);
            for (String field : REWARD_FIELDS) {
                int value = integer(reward, field, code);
                assertTrue(value >= 0 && value <= 100, code + ":" + field);
            }
            assertTrue(growth(book) > 0, code);
            int weightSum = 0;
            for (String ability : ABILITIES) {
                int weight = integer(book, ability + "Weight", code);
                assertTrue(weight >= 0 && weight <= 100, code + ":" + ability);
                weightSum += weight;
            }
            assertEquals(100, weightSum, code);
            JSONArray requirements = assertInstanceOf(JSONArray.class, book.get("readingRequirement"), code);
            assertFalse(requirements.isEmpty(), code);
            for (Object raw : requirements) {
                JSONObject requirement = assertInstanceOf(JSONObject.class, raw, code);
                int value = integer(requirement, "value", code);
                assertTrue(value >= 0, code);
                String target = requirement.getStr("target");
                switch (requirement.getStr("type")) {
                    case "MIN_AGE" -> assertTrue(value <= 16, code);
                    case "MIN_GENERAL_ATTRIBUTE" -> {
                        assertTrue(ATTRIBUTES.contains(target), code);
                        assertTrue(value <= 100, code);
                    }
                    case "MIN_CAREER_ABILITY" -> {
                        assertTrue(ABILITIES.contains(target), code);
                        assertTrue(value <= 100, code);
                    }
                    case "BOOK_PROGRESS" -> {
                        assertTrue(catalog.books().containsKey(target), code);
                        assertTrue(value <= 100, code);
                    }
                    default -> fail("未知阅读条件：" + code + ":" + requirement);
                }
            }
        }
    }

    @Test
    void ordinaryRaritiesIncreaseAverageGrowthAndPaidPricePerGrowth() throws IOException {
        Catalog catalog = catalog();
        Tier previous = null;
        for (String rarity : NORMAL_RARITIES) {
            long growthTotal = 0;
            long paidGrowth = 0;
            long priceTotal = 0;
            int count = 0;
            int paidCount = 0;
            for (var entry : catalog.books().entrySet()) {
                JSONObject equipment = catalog.equipment().get(entry.getKey());
                if (!rarity.equals(equipment.getStr("rarityCode"))) continue;
                int growth = growth(entry.getValue());
                int price = integer(equipment, "price", entry.getKey());
                growthTotal += growth;
                count++;
                if (price > 0) {
                    priceTotal += price;
                    paidGrowth += growth;
                    paidCount++;
                }
            }
            assertTrue(count > 0 && paidCount > 0, rarity);
            Tier current = new Tier(growthTotal, count, priceTotal, paidGrowth, paidCount);
            if (previous != null) {
                // 比较分档均值和付费组总价/总成长，不要求每本专长不同的书逐对严格排序。
                assertTrue(current.growth() * previous.count() > previous.growth() * current.count(), rarity + "平均成长");
                assertTrue(current.price() * previous.paidCount() > previous.price() * current.paidCount(), rarity + "平均价格");
                assertTrue(current.price() * previous.paidGrowth() > previous.price() * current.paidGrowth(), rarity + "每点成长价格");
            }
            previous = current;
        }
    }

    @Test
    void legendaryWeightsBookIsAnExplicitExceptionToOrdinaryRarityGrowth() throws IOException {
        Catalog catalog = catalog();
        JSONObject book = catalog.books().get("BOOK_GPT1_WEIGHTS");
        JSONObject equipment = catalog.equipment().get("BOOK_GPT1_WEIGHTS");
        assertNotNull(book);
        assertNotNull(equipment);
        assertEquals("LEGENDARY", equipment.getStr("rarityCode"));
        JSONObject reward = book.getJSONObject("readingReward");
        Map<String, Integer> nonzero = Map.of("characterZhili", 9, "abilityWenzhang", 10, "abilityCelun", 15, "abilityWenxue", 3);
        for (String field : REWARD_FIELDS)
            assertEquals(nonzero.getOrDefault(field, 0).intValue(), integer(reward, field, "BOOK_GPT1_WEIGHTS"), field);
        assertEquals(37, growth(book));
        assertFalse(book.getBool("playerReadingEnabled"));
        assertTrue(catalog.equipment().values().stream().filter(item -> "EPIC".equals(item.getStr("rarityCode")))
                .allMatch(item -> item.getInt("price") < equipment.getInt("price")));
    }

    @Test
    void teaRestorationUsesIntegerTenStaminaAndNoHealthCostWithinConfiguredLimit() throws IOException {
        JSONObject tea = catalog().equipment().get("ITEM_QINGCHA");
        assertNotNull(tea);
        assertEquals("CONSUMABLE", tea.getStr("equipmentType"));
        JSONObject restoration = assertInstanceOf(JSONObject.class, tea.get("restoration"));
        int recovery = integer(restoration, "staminaRecovery", "ITEM_QINGCHA");
        assertEquals(10, recovery);
        assertTrue(recovery <= GameRuleConstant.CONSUMABLE_STAMINA_RECOVERY_LIMIT);
        assertEquals(20, GameRuleConstant.CONSUMABLE_STAMINA_RECOVERY_LIMIT);
        assertEquals(0, integer(restoration, "healthCost", "ITEM_QINGCHA"));
    }

    private Catalog catalog() throws IOException {
        return new Catalog(index(resource("book.json").getJSONArray("book")),
                index(resource("equipment.json").getJSONArray("equipment")));
    }

    private Map<String, JSONObject> index(JSONArray definitions) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (Object raw : definitions) {
            JSONObject definition = assertInstanceOf(JSONObject.class, raw);
            String code = definition.getStr("equipmentCode");
            assertNotNull(code);
            assertFalse(code.isBlank());
            assertNull(result.put(code, definition), "重复equipmentCode：" + code);
        }
        return result;
    }

    private int growth(JSONObject book) {
        int total = 0;
        JSONObject reward = book.getJSONObject("readingReward");
        for (String field : REWARD_FIELDS) total += integer(reward, field, book.getStr("equipmentCode"));
        return total;
    }

    private int integer(JSONObject object, String field, String code) {
        Number value = assertInstanceOf(Number.class, object.get(field), code + ":" + field);
        return assertDoesNotThrow(() -> new BigDecimal(value.toString()).intValueExact(), code + ":" + field);
    }

    private JSONObject resource(String filename) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/game/" + filename)) {
            assertNotNull(input, filename);
            return JSONUtil.parseObj(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private record Catalog(Map<String, JSONObject> books, Map<String, JSONObject> equipment) { }
    private record Tier(long growth, int count, long price, long paidGrowth, int paidCount) { }
}
