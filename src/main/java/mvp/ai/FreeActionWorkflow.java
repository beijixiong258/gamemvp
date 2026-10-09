package mvp.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import mvp.engine.CharacterEngine;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.service.CharacterService.NpcIntent;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EquipmentRecordService.SceneItemChange;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 自由行动与私人对话共用固定流程；全部模型调用及数值预计算均在业务提交事务外。 */
@Component
public class FreeActionWorkflow {
    private final FreeActionResolver resolver;
    private final CharacterEngine characterEngine;
    private final CompiledGraph<FreeActionState> graph;

    public FreeActionWorkflow(FreeActionResolver resolver, CharacterEngine characterEngine) {
        this.resolver = resolver;
        this.characterEngine = characterEngine;
        this.graph = buildGraph();
    }

    public FreeActionResult execute(FreeActionCommand command) {
        JSONObject input = new JSONObject().set("currentText", command.playerText())
                .set("facts", JSONUtil.parseObj(command.contextSummary()));
        FreeActionState state = invoke(input, false, command.character(), command.scholar());
        FreeActionResolver.Resolution result = state.resolution();
        return new FreeActionResult(state.settlement(), result.narrative(), result.lifeMilestone(),
                result.acquisitions(), result.npcChanges(), result.sceneItemChanges());
    }

    public DialogueResult executeDialogue(String input, CharacterState character, ScholarState scholar) {
        FreeActionState state = invoke(JSONUtil.parseObj(input), true, character, scholar);
        return new DialogueResult(state.resolution(), state.settlement());
    }

    private FreeActionState invoke(JSONObject input, boolean dialogue, CharacterState character, ScholarState scholar) {
        Map<String, Object> values = new HashMap<>();
        values.put(FreeActionState.CONTEXT, ActionGuard.prepare(input, dialogue));
        values.put(FreeActionState.CHARACTER, character);
        values.put(FreeActionState.SCHOLAR, scholar);
        try {
            return graph.invoke(values).orElseThrow(() -> new IllegalStateException("智能体流程未返回结果"));
        } catch (RuntimeException exception) {
            // 图运行器可能包装节点异常，保留拒绝/冲突原因，不谎报为余额不足。
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseStatusException response) {
                    throw response;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI行动处理失败，本次未结算，请稍后重试", exception);
        }
    }

    private CompiledGraph<FreeActionState> buildGraph() {
        try {
            return new StateGraph<FreeActionState>(FreeActionState::new)
                    .addNode("interpret", node_async(state -> {
                        var result = resolver.interpret(state.context());
                        ActionGuard.validateInterpretation(state.context(), result);
                        return Map.of(FreeActionState.INTERPRETATION, result);
                    }))
                    .addNode("resolve", node_async(state -> {
                        var result = resolver.resolve(state.context(), state.interpretation());
                        ActionGuard.validateResolution(state.context(), state.interpretation(), result);
                        return Map.of(FreeActionState.RESOLUTION, result);
                    }))
                    .addNode("review", node_async(state -> {
                        var review = resolver.review(state.context(), state.interpretation(), state.resolution());
                        ActionGuard.requireApproved(review);
                        return Map.of(FreeActionState.REVIEW, review);
                    }))
                    .addNode("settle_driver", node_async(state -> {
                        ActionGuard.requireApproved(state.review());
                        ActionGuard.validateResolution(state.context(), state.interpretation(), state.resolution());
                        if ("DIALOGUE".equals(state.context().getStr("mode")) && !state.resolution().endDialogue()) {
                            return Map.of();
                        }
                        return Map.of(FreeActionState.SETTLEMENT, characterEngine.applyDriver(
                                state.character(), state.scholar(), state.resolution().driverPatch()));
                    }))
                    .addEdge(START, "interpret").addEdge("interpret", "resolve")
                    .addEdge("resolve", "review").addEdge("review", "settle_driver")
                    .addEdge("settle_driver", END).compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("智能体流程图配置错误", exception);
        }
    }

    public record FreeActionCommand(String playerText, String contextSummary,
                                    CharacterState character, ScholarState scholar) { }
    public record FreeActionResult(DriverResult settlement, String eventSummary, boolean lifeMilestone,
                                   List<AcquisitionIntent> acquisitions, List<NpcIntent> npcChanges,
                                   List<SceneItemChange> sceneItemChanges) { }
    public record DialogueResult(FreeActionResolver.Resolution resolution, DriverResult settlement) { }
}
