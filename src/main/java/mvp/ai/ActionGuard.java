package mvp.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import mvp.ai.FreeActionResolver.*;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.service.EquipmentRecordService.ScenePropTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** 模型之外的上下文投影与行动契约；不代替智能体判断人物动机。 */
@Slf4j
final class ActionGuard {
    private ActionGuard() { }

    static JSONObject prepare(JSONObject input, boolean dialogue) {
        JSONObject facts = JSONUtil.parseObj(input.getJSONObject("facts").toString());
        JSONObject actor = facts.getJSONObject("actor");
        JSONObject counterpart = input.getJSONObject("counterpart");
        JSONObject sources = new JSONObject();
        JSONObject context = new JSONObject().set("mode", dialogue ? "DIALOGUE" : "FREE_ACTION")
                .set("currentText", input.getStr("currentText", ""))
                .set("manualEnd", dialogue && Boolean.TRUE.equals(input.getBool("manualEnd")));
        // 私聊不把发起者的内心、钱包、家庭与能力数值交给回应者。
        facts.set("actor", identity(actor));
        if (!dialogue) {
            facts.getJSONObject("actor").putAll(select(actor, "wallet", "characterZhili", "characterDaode",
                    "characterZhengzhi", "characterJiaoji", "characterTineng", "characterJiankang", "characterPilao"));
        } else {
            facts.remove("scholar");
            facts.remove("playerFamilyBackground");
            facts.remove("backpack");
            facts.remove("books");
        }
        if (!dialogue && actor.getInt("type", 0) != 1) {
            facts.remove("playerFamilyBackground");
        }
        JSONArray npcs = new JSONArray();
        for (JSONObject npc : objects(facts.getJSONArray("npcs"))) {
            npcs.add(identity(npc));
            source(sources, "npc:" + npc.getStr("id"), "OBSERVED_IDENTITY", "facts.npcs", npc.getStr("id"));
        }
        facts.set("npcs", npcs);
        JSONArray items = new JSONArray();
        for (JSONObject item : objects(facts.getJSONArray("sceneItems"))) {
            // 名称/说明不能证明材料、货币价值或行政权限；旧存档也不例外。
            items.add(select(item, "id", "itemCode", "itemName", "description", "sceneCode", "quantity")
                    .set("economicValue", 0).set("useEffect", "NONE").set("textAuthority", "FLAVOR_ONLY"));
            source(sources, "item:" + item.getStr("id"), "SCENE_ITEM", "facts.sceneItems", null);
        }
        facts.set("sceneItems", items);
        JSONArray templates = new JSONArray();
        for (ScenePropTemplate template : ScenePropTemplate.values()) {
            templates.add(new JSONObject().set("code", template.name()).set("name", template.itemName())
                    .set("description", template.description()).set("economicValue", 0));
            source(sources, "prop:" + template.name(), "ALLOWED_PROP_RULE", "facts.scenePropTemplates", null);
        }
        facts.set("scenePropTemplates", templates);
        JSONArray offers = new JSONArray();
        for (JSONObject offer : objects(facts.getJSONArray("supplies"))) {
            JSONObject equipment = offer.getJSONObject("equipment");
            if (dialogue && !Objects.equals(equipment.getStr("supplierNpcCode"), counterpart.getStr("npcCode"))) {
                continue;
            }
            offers.add(offer);
            source(sources, "supply:" + equipment.getStr("equipmentCode"), "SUPPLY_OFFER", "facts.supplies", null);
        }
        facts.set("supplies", offers);
        JSONArray memories = dialogue ? input.getJSONArray("memoryContext") : facts.getJSONArray("memoryContext");
        facts.remove("memoryContext");
        context.set("memoryContext", memories == null ? new JSONArray() : memories);
        for (JSONObject memory : objects(memories)) {
            source(sources, "memory:" + memory.getStr("memoryId"), "RECOLLECTION", "memoryContext",
                    dialogue ? counterpart.getStr("id") : actor.getStr("id"));
        }
        if (dialogue) {
            context.set("counterpart", identity(counterpart)
                    .set("personalitySummary", counterpart.getStr("personalitySummary"))
                    .set("untrustedDescription", counterpart.getStr("currentState")));
            source(sources, "fact:counterpart", "SELF_CONTEXT", "counterpart", counterpart.getStr("id"));
            context.set("dialogueRound", input.getInt("dialogueRound")).set("maxDialogueRounds", input.getInt("maxDialogueRounds"));
            context.set("maxReplyCharacters", input.getInt("maxReplyCharacters", 1600));
            JSONArray history = new JSONArray();
            for (JSONObject message : objects(input.getJSONArray("history"))) {
                boolean manualEnd = Boolean.TRUE.equals(message.getBool("manualEnd"));
                JSONObject entry = select(message, "speaker", "speakerName")
                        .set("text", manualEnd ? "" : message.getStr("text", ""));
                int index = history.size();
                JSONArray receipts = new JSONArray();
                for (JSONObject receipt : objects(message.getJSONArray("executedTrades"))) {
                    source(sources, "historyTrade:" + index + ":" + receipts.size(), "COMMITTED_TRADE",
                            "history[" + index + "].executedTrades[" + receipts.size() + "]", null);
                    receipts.add(select(receipt, "actorId", "supplierId", "supplierNpcCode",
                            "equipmentCode", "equipmentName", "quantity", "cost"));
                }
                JSONArray identities = new JSONArray();
                for (JSONObject person : objects(message.getJSONArray("resolvedNpcs"))) {
                    identities.add(identity(person));
                }
                entry.set("executedTrades", receipts).set("resolvedNpcs", identities);
                JSONArray observedItems = new JSONArray();
                for (JSONObject item : objects(message.getJSONArray("sceneItems"))) {
                    observedItems.add(select(item, "id", "itemName", "sceneCode", "quantity")
                            .set("economicValue", 0).set("textAuthority", "FLAVOR_ONLY"));
                }
                entry.set("sceneItems", observedItems);
                history.add(entry);
                if (!manualEnd) {
                    source(sources, "history:" + index, "STATEMENT", "history[" + index + "].text",
                            "actor".equals(message.getStr("speaker")) ? actor.getStr("id") : counterpart.getStr("id"));
                }
            }
            context.set("history", history);
        }
        if (!Boolean.TRUE.equals(context.getBool("manualEnd"))) {
            source(sources, "current", "STATEMENT", "currentText", actor.getStr("id"));
        }
        for (String key : List.of("calendar", "actor", "actorAge", "scholar", "scene", "books", "backpack")) {
            if (facts.containsKey(key)) {
                source(sources, "fact:" + key, "CURRENT_STATE", "facts." + key, null);
            }
        }
        return context.set("facts", facts).set("sources", sources);
    }

    static void validateInterpretation(JSONObject context, Interpretation result) {
        require(result != null && result.statements() != null && result.acquisitionRequests() != null, "INTERPRETATION_SHAPE");
        require(result.statements().size() <= 16 && result.acquisitionRequests().size() <= 8, "INTERPRETATION_SIZE");
        JSONObject sources = context.getJSONObject("sources");
        for (Statement statement : result.statements()) {
            require(statement != null && statement.kind() != null && text(statement.meaning(), 600), "STATEMENT_SHAPE");
            JSONObject source = sources.getJSONObject(statement.sourceId());
            require(source != null && "STATEMENT".equals(source.getStr("kind")), "STATEMENT_SOURCE");
        }
        Set<String> orders = new HashSet<>();
        for (RequestedAcquisition order : result.acquisitionRequests()) {
            require(order != null && "current".equals(order.sourceId()) && sources.containsKey("current")
                    && order.quantity() >= 1 && order.quantity() <= 100, "ORDER_SOURCE");
            require(result.statements().stream().anyMatch(s -> "current".equals(s.sourceId())
                    && s.kind() == StatementKind.ACTION), "ORDER_NOT_ACTION");
            require(text(order.supplierNpcCode(), 100) && text(order.equipmentCode(), 100)
                    && orders.add(order.supplierNpcCode() + ":" + order.equipmentCode()), "ORDER_DUPLICATE");
        }
    }

    static void validateResolution(JSONObject context, Interpretation interpretation, Resolution result) {
        require(result != null && text(result.narrative(), 1600) && result.driverPatch() != null
                && result.acquisitions() != null && result.npcChanges() != null
                && result.sceneItemChanges() != null && result.evidence() != null, "RESOLUTION_SHAPE");
        require(result.acquisitions().size() <= 8 && result.npcChanges().size() <= 4
                && result.sceneItemChanges().size() <= 8 && result.evidence().size() <= 23, "RESOLUTION_SIZE");
        boolean changed = validateDriver(result.driverPatch());
        boolean dialogue = "DIALOGUE".equals(context.getStr("mode"));
        boolean manual = Boolean.TRUE.equals(context.getBool("manualEnd"));
        if (dialogue) {
            require(!result.lifeMilestone(), "DIALOGUE_MILESTONE");
            require(result.narrative().length() <= context.getInt("maxReplyCharacters", 1600), "DIALOGUE_REPLY_LENGTH");
            require(context.getInt("dialogueRound") < context.getInt("maxDialogueRounds") || result.endDialogue(), "DIALOGUE_END");
        } else {
            require(!result.endDialogue(), "FREE_ACTION_END");
        }
        if (manual) {
            require(result.endDialogue() && !changed && result.acquisitions().isEmpty() && result.npcChanges().isEmpty()
                    && result.sceneItemChanges().isEmpty(), "MANUAL_END_EFFECT");
        }
        Map<String, Set<String>> evidence = new HashMap<>();
        JSONObject sources = context.getJSONObject("sources");
        for (EffectEvidence entry : result.evidence()) {
            require(entry != null && entry.effect() != null && entry.index() >= 0 && entry.sourceIds() != null
                    && !entry.sourceIds().isEmpty() && entry.sourceIds().size() <= 12, "EVIDENCE_SHAPE");
            Set<String> ids = new HashSet<>(entry.sourceIds());
            require(!ids.contains(null) && ids.stream().allMatch(sources::containsKey), "FORGED_SOURCE");
            require(evidence.put(entry.effect() + ":" + entry.index(), ids) == null, "DUPLICATE_EVIDENCE");
        }
        take(evidence, EffectKind.NARRATIVE, 0);
        if (changed) {
            Set<String> ids = take(evidence, EffectKind.DRIVERS, 0);
            require(ids.contains("current"), "DRIVER_SOURCE");
        }
        if (result.lifeMilestone()) {
            require(take(evidence, EffectKind.MILESTONE, 0).contains("current"), "MILESTONE_SOURCE");
        }
        JSONObject facts = context.getJSONObject("facts");
        Set<String> ordered = new HashSet<>();
        for (int i = 0; i < result.acquisitions().size(); i++) {
            var intent = result.acquisitions().get(i);
            require(intent != null && interpretation.acquisitionRequests().stream().anyMatch(order ->
                    Objects.equals(order.supplierNpcCode(), intent.supplierNpcCode())
                            && Objects.equals(order.equipmentCode(), intent.equipmentCode())
                            && order.quantity() == intent.quantity()), "ORDER_MISMATCH");
            require(ordered.add(intent.equipmentCode()), "DUPLICATE_ORDER");
            Set<String> ids = take(evidence, EffectKind.ACQUISITION, i);
            require(ids.contains("current") && ids.contains("supply:" + intent.equipmentCode()), "ACQUISITION_SOURCE");
            require(objects(facts.getJSONArray("supplies")).stream().anyMatch(offer -> {
                JSONObject equipment = offer.getJSONObject("equipment");
                return Objects.equals(intent.equipmentCode(), equipment.getStr("equipmentCode"))
                        && Objects.equals(intent.supplierNpcCode(), equipment.getStr("supplierNpcCode"))
                        && Boolean.TRUE.equals(offer.getBool("canAcquire"));
            }), "SUPPLY_NOT_ALLOWED");
        }
        int newNpcs = 0;
        Set<String> npcs = new HashSet<>();
        for (int i = 0; i < result.npcChanges().size(); i++) {
            var intent = result.npcChanges().get(i);
            require(intent != null, "NPC_SHAPE");
            Set<String> ids = take(evidence, EffectKind.NPC, i);
            require(ids.contains("current"), "NPC_SOURCE");
            if (blank(intent.characterId())) {
                require(!dialogue && ++newNpcs <= 2, "NEW_NPC_SCOPE");
            } else {
                require(sources.containsKey("npc:" + intent.characterId()) && ids.contains("npc:" + intent.characterId())
                        && npcs.add(intent.characterId()), "NPC_SCOPE");
                if (dialogue) {
                    require(Objects.equals(context.getJSONObject("counterpart").getStr("id"), intent.characterId()), "PRIVATE_PARTICIPANT");
                }
            }
        }
        int newItems = 0;
        Set<String> items = new HashSet<>();
        for (int i = 0; i < result.sceneItemChanges().size(); i++) {
            var intent = result.sceneItemChanges().get(i);
            require(intent != null, "ITEM_SHAPE");
            Set<String> ids = take(evidence, EffectKind.SCENE_ITEM, i);
            require(ids.contains("current"), "ITEM_SOURCE");
            if (blank(intent.itemId())) {
                require(++newItems <= 2 && intent.templateCode() != null, "NEW_ITEM_SCOPE");
                ScenePropTemplate template;
                try { template = ScenePropTemplate.valueOf(intent.templateCode()); }
                catch (IllegalArgumentException exception) { throw rejected("ITEM_TEMPLATE"); }
                require(ids.contains("prop:" + template.name())
                        && Objects.equals(intent.itemName(), template.itemName())
                        && Objects.equals(intent.description(), template.description()), "ITEM_DEFINITION");
            } else {
                require(sources.containsKey("item:" + intent.itemId()) && ids.contains("item:" + intent.itemId())
                        && items.add(intent.itemId()), "ITEM_SCOPE");
            }
        }
        require(evidence.isEmpty(), "UNUSED_EVIDENCE");
    }

    static void requireApproved(Review review) {
        if (review == null || !review.approved() || review.violations() == null
                || !review.violations().isEmpty() || !text(review.reason(), 600)) {
            log.warn("AI行动复核拒绝：{}", review);
            throw rejected("SEMANTIC_REVIEW");
        }
    }

    private static boolean validateDriver(DriverPatch patch) {
        BigDecimal[] values = {patch.attributeIntelligenceGain(), patch.attributeMoralityGain(),
                patch.attributePoliticsGain(), patch.attributeSocialGain(), patch.attributeFitnessGain(),
                patch.abilityShiziGain(), patch.abilityJingyiGain(), patch.abilityWenzhangGain(),
                patch.abilityCelunGain(), patch.abilityWenxueGain(), patch.fatigueOffset(), patch.healthOffset()};
        boolean changed = false;
        for (int i = 0; i < values.length; i++) {
            int bound = i < 5 ? 3 : i < 10 ? 5 : i == 10 ? 0 : 10;
            require(values[i] != null && values[i].abs().compareTo(BigDecimal.valueOf(bound)) <= 0, "DRIVER_BOUND");
            changed |= values[i].signum() != 0;
        }
        return changed;
    }

    private static Set<String> take(Map<String, Set<String>> evidence, EffectKind effect, int index) {
        Set<String> ids = evidence.remove(effect + ":" + index);
        require(ids != null, "MISSING_EVIDENCE");
        return ids;
    }

    private static void source(JSONObject sources, String id, String kind, String path, String speakerId) {
        sources.set(id, new JSONObject().set("kind", kind).set("path", path).set("speakerId", speakerId));
    }

    private static JSONObject identity(JSONObject person) {
        return select(person, "id", "name", "type", "npcCode", "officialPosition", "officialRank", "degree", "currentSceneCode");
    }

    private static JSONObject select(JSONObject source, String... fields) {
        JSONObject result = new JSONObject();
        for (String field : fields) {
            if (source.containsKey(field)) { result.set(field, source.get(field)); }
        }
        return result;
    }

    private static List<JSONObject> objects(JSONArray array) {
        return array == null ? List.of() : array.toList(JSONObject.class);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean text(String value, int max) {
        return !blank(value) && value.codePointCount(0, value.length()) <= max;
    }
    private static void require(boolean condition, String code) { if (!condition) { throw rejected(code); } }
    private static ResponseStatusException rejected(String code) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "AI结果未通过事实与权限校验，本次未结算（" + code + "）");
    }
}
