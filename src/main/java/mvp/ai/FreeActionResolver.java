package mvp.ai;

import cn.hutool.json.JSONObject;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.service.CharacterService.NpcIntent;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EquipmentRecordService.SceneItemChange;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/** 三个独立、无工具写权限的模型步骤。上一步输出始终是待核验数据。 */
@Component
public class FreeActionResolver {
    private final GameClient gameClient;

    public FreeActionResolver(GameClient gameClient) {
        this.gameClient = gameClient;
    }

    Interpretation interpret(JSONObject context) {
        return gameClient.chatChecked("PROMPT_ACTION_INTERPRET", context.toString(), Interpretation.class);
    }

    Resolution resolve(JSONObject context, Interpretation interpretation) {
        String prompt = "DIALOGUE".equals(context.getStr("mode")) ? "PROMPT_NPC_DIALOGUE" : "PROMPT_FREE_ACTION";
        return gameClient.chatChecked(prompt, new JSONObject().set("context", context)
                .set("interpretation", interpretation).toString(), Resolution.class);
    }

    Review review(JSONObject context, Interpretation interpretation, Resolution resolution) {
        return gameClient.chatChecked("PROMPT_ACTION_REVIEW", new JSONObject().set("context", context)
                .set("interpretation", interpretation).set("candidate", resolution).toString(), Review.class);
    }

    public enum StatementKind { ACTION, QUESTION, CLAIM, HYPOTHESIS, PROMISE }
    public enum EffectKind { NARRATIVE, DRIVERS, ACQUISITION, NPC, SCENE_ITEM, MILESTONE }
    public enum Violation {
        INJECTION, UNSUPPORTED_FACT, PROMISE_AS_EXECUTION, GUESS_AS_FACT, FUTURE_KNOWLEDGE,
        HIDDEN_KNOWLEDGE, UNAUTHORIZED_EFFECT, EVIDENCE_MISMATCH, MEMORY_PROMOTION
    }

    public record Statement(String sourceId, StatementKind kind, String meaning) implements Serializable { }
    public record RequestedAcquisition(String sourceId, String supplierNpcCode, String equipmentCode,
                                       int quantity) implements Serializable { }
    public record Interpretation(List<Statement> statements,
                                 List<RequestedAcquisition> acquisitionRequests) implements Serializable { }
    public record EffectEvidence(EffectKind effect, int index, List<String> sourceIds) implements Serializable { }
    public record Review(boolean approved, List<Violation> violations, String reason) implements Serializable { }

    public record Resolution(DriverPatch driverPatch, String narrative, boolean endDialogue,
                             boolean lifeMilestone, List<AcquisitionIntent> acquisitions,
                             List<NpcIntent> npcChanges, List<SceneItemChange> sceneItemChanges,
                             List<EffectEvidence> evidence) implements Serializable { }
}
