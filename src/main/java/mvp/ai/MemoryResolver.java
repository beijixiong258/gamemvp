package mvp.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import mvp.engine.GameRuleConstant;
import org.springframework.stereotype.Component;

import java.util.List;

/** 根据已提交的经历评估记忆；保存、到期和身份边界由MemoryRecordService处理。 */
@Component
public class MemoryResolver {
    private final GameClient gameClient;

    public MemoryResolver(GameClient gameClient) {
        this.gameClient = gameClient;
    }

    public MemoryResolution resolve(JSONObject owner, JSONObject confirmedEvent, JSONArray previousMemories) {
        JSONObject input = new JSONObject().set("owner", owner).set("confirmedEvent", confirmedEvent)
                .set("previousMemories", previousMemories).set("maxSummaryCharacters", 1000)
                .set("retentionTurnOptions", List.of(GameRuleConstant.MEMORY_SHORT_RETENTION_TURNS,
                        GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS, GameRuleConstant.MEMORY_LONG_RETENTION_TURNS));
        return gameClient.chat("PROMPT_MEMORY_SUMMARY", input.toString(), MemoryResolution.class);
    }

    public record MemoryResolution(String summary, String level, String kind, Integer retentionTurns,
                                   String reason, List<String> reinforcedMemoryIds) {
    }
}
