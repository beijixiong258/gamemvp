package mvp.ai;

import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public final class FreeActionState extends AgentState {

    static final String PLAYER_TEXT = "playerText";
    static final String CONTEXT_SUMMARY = "contextSummary";
    static final String CHARACTER = "character";
    static final String SCHOLAR = "scholar";
    static final String RESOLUTION = "resolution";
    static final String SETTLEMENT = "settlement";

    public FreeActionState(Map<String, Object> data) {
        super(data);
    }

    String playerText() {
        return this.<String>value(PLAYER_TEXT).orElseThrow();
    }

    String contextSummary() {
        return this.<String>value(CONTEXT_SUMMARY).orElse("");
    }

    CharacterState character() {
        return this.<CharacterState>value(CHARACTER).orElseThrow();
    }

    ScholarState scholar() {
        return this.<ScholarState>value(SCHOLAR).orElseThrow();
    }

    FreeActionResolver.FreeActionResolution resolution() {
        return this.<FreeActionResolver.FreeActionResolution>value(RESOLUTION).orElseThrow();
    }

    DriverPatch driverPatch() {
        return resolution().driverPatch();
    }

    DriverResult settlement() {
        return this.<DriverResult>value(SETTLEMENT).orElseThrow();
    }
}
