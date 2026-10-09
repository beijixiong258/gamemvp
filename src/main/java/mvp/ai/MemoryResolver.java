package mvp.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import mvp.engine.GameRuleConstant;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 摘要与复核均在事件提交后运行；失败只留下记忆缺口，不重放游戏效果。 */
@Component
public class MemoryResolver {
    private final GameClient gameClient;

    public MemoryResolver(GameClient gameClient) {
        this.gameClient = gameClient;
    }

    public MemoryResolution resolve(JSONObject owner, JSONObject confirmedEvent, JSONArray previousMemories) {
        JSONObject sources = new JSONObject().set("event", "事件发生及参与者，不证明言论内容为真");
        for (String field : List.of("statements", "executedTrades", "resolvedNpcs", "sceneItems")) {
            JSONArray entries = confirmedEvent.getJSONArray(field);
            for (int i = 0; entries != null && i < entries.size(); i++) {
                sources.set(field + ":" + i, "statements".equals(field) ? "UTTERANCE_NOT_FACT" : "COMMITTED_RECEIPT");
            }
        }
        for (String field : List.of("confirmedResult", "exam", "eventSummary")) {
            if (confirmedEvent.containsKey(field)) {
                sources.set(field, "eventSummary".equals(field) ? "MODEL_NARRATIVE" : "COMMITTED_RECEIPT");
            }
        }
        JSONObject input = new JSONObject().set("owner", owner).set("confirmedEvent", confirmedEvent)
                .set("sources", sources).set("previousMemories", previousMemories).set("maxSummaryCharacters", 1000)
                .set("retentionTurnOptions", List.of(GameRuleConstant.MEMORY_SHORT_RETENTION_TURNS,
                        GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS, GameRuleConstant.MEMORY_LONG_RETENTION_TURNS));
        MemoryResolution result = gameClient.chatChecked("PROMPT_MEMORY_SUMMARY", input.toString(), MemoryResolution.class);
        if (result.summary() == null || result.summary().isBlank()
                || result.summary().codePointCount(0, result.summary().length()) > 1000
                || result.evidenceIds() == null || result.evidenceIds().isEmpty() || result.evidenceIds().size() > 12
                || result.evidenceIds().stream().anyMatch(id -> id == null || !sources.containsKey(id))
                || result.reinforcedMemoryIds() == null || result.reinforcedMemoryIds().size() > 8
                || result.level() == null || !Set.of("L0", "L1", "L2").contains(result.level())
                || result.kind() == null || !Set.of("EXPERIENCE", "RELATIONSHIP", "COMMITMENT").contains(result.kind())) {
            throw new IllegalStateException("记忆结构或证据不合法");
        }
        Set<String> previousIds = new HashSet<>();
        for (int i = 0; i < previousMemories.size(); i++) {
            previousIds.add(previousMemories.getJSONObject(i).getStr("memoryId"));
        }
        if (!previousIds.containsAll(result.reinforcedMemoryIds())) {
            throw new IllegalStateException("记忆强化引用了未提供的经历");
        }
        ActionGuard.requireApproved(gameClient.chatChecked("PROMPT_MEMORY_REVIEW",
                input.set("candidate", result).toString(), FreeActionResolver.Review.class));
        return result;
    }

    public record MemoryResolution(String summary, String level, String kind, Integer retentionTurns,
                                   String reason, List<String> reinforcedMemoryIds, List<String> evidenceIds) { }
}
