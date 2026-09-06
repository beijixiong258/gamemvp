package mvp.ai;

import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.CharacterEngine;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
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

@Component
public class FreeActionWorkflow {

    private static final String RESOLVE_ACTION = "resolve_action";
    private static final String SETTLE_DRIVER = "settle_driver";

    private final FreeActionResolver resolver;
    private final CharacterEngine characterEngine;
    private final CompiledGraph<FreeActionState> graph;

    public FreeActionWorkflow(FreeActionResolver resolver, CharacterEngine characterEngine) {
        this.resolver = resolver;
        this.characterEngine = characterEngine;
        this.graph = buildGraph();
    }

    /**
     * 执行一次自由行动智能体流程。
     *
     * @param command 玩家原文、上下文及行动前数值快照
     * @return AI事件语义与Java引擎确定的结算结果
     */
    public FreeActionResult execute(FreeActionCommand command) {
        Map<String, Object> input = new HashMap<>();
        input.put(FreeActionState.PLAYER_TEXT, command.playerText());
        input.put(FreeActionState.SCENE_CODE, command.sceneCode());
        input.put(
                FreeActionState.CONTEXT_SUMMARY,
                command.contextSummary() == null ? "" : command.contextSummary()
        );
        input.put(FreeActionState.CHARACTER, command.character());
        input.put(FreeActionState.SCHOLAR, command.scholar());

        FreeActionState finalState;
        try {
            finalState = graph.invoke(input)
                    .orElseThrow(() -> new IllegalStateException("自由行动流程没有返回最终状态"));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI调用失败，请检查网络或模型账户余额后重试", exception);
        }
        FreeActionResolver.FreeActionResolution resolution = finalState.resolution();
        return new FreeActionResult(
                finalState.settlement(),
                resolution.eventSummary(),
                resolution.lifeMilestone(),
                resolution.acquisitions() == null ? List.of() : resolution.acquisitions()
        );
    }

    /**
     * 创建并编译自由行动图，固定执行Resolver解析和Java引擎结算两个节点。
     *
     * @return 可重复调用的自由行动编排图
     */
    private CompiledGraph<FreeActionState> buildGraph() {
        try {
            return new StateGraph<>(FreeActionState::new)
                    .addNode(RESOLVE_ACTION, node_async(this::resolveAction))
                    .addNode(SETTLE_DRIVER, node_async(this::settleDriver))
                    .addEdge(START, RESOLVE_ACTION)
                    .addEdge(RESOLVE_ACTION, SETTLE_DRIVER)
                    .addEdge(SETTLE_DRIVER, END)
                    .compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("自由行动流程图配置错误", exception);
        }
    }

    /**
     * 调用自由行动Resolver，把结构化输出写入图状态。
     *
     * @param state 当前图状态
     * @return 只包含Resolver输出的局部状态更新
     */
    private Map<String, Object> resolveAction(FreeActionState state) {
        FreeActionResolver.FreeActionResolution resolution = resolver.resolve(
                state.playerText(),
                state.sceneCode(),
                state.contextSummary(),
                state.character(),
                state.scholar()
        );
        return Map.of(FreeActionState.RESOLUTION, resolution);
    }

    /**
     * 把Resolver驱动量交给人物引擎结算，并写入最终数值结果。
     *
     * @param state 已包含Resolver输出的图状态
     * @return 只包含引擎结算结果的局部状态更新
     */
    private Map<String, Object> settleDriver(FreeActionState state) {
        DriverResult settlement = characterEngine.applyDriver(
                state.character(),
                state.scholar(),
                state.driverPatch()
        );
        return Map.of(FreeActionState.SETTLEMENT, settlement);
    }

    public record FreeActionCommand(
            String playerText,
            String sceneCode,
            String contextSummary,
            CharacterState character,
            ScholarState scholar
    ) {
    }

    public record FreeActionResult(
            DriverResult settlement,
            String eventSummary,
            boolean lifeMilestone,
            List<AcquisitionIntent> acquisitions
    ) {
    }
}
