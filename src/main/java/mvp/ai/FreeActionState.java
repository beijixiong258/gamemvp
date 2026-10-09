package mvp.ai;

import cn.hutool.json.JSONObject;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public final class FreeActionState extends AgentState {
    static final String CONTEXT = "context";
    static final String CHARACTER = "character";
    static final String SCHOLAR = "scholar";
    static final String INTERPRETATION = "interpretation";
    static final String RESOLUTION = "resolution";
    static final String REVIEW = "review";
    static final String SETTLEMENT = "settlement";

    public FreeActionState(Map<String, Object> data) { super(data); }

    JSONObject context() { return this.<JSONObject>value(CONTEXT).orElseThrow(); }
    CharacterState character() { return this.<CharacterState>value(CHARACTER).orElseThrow(); }
    ScholarState scholar() { return this.<ScholarState>value(SCHOLAR).orElseThrow(); }
    FreeActionResolver.Interpretation interpretation() {
        return this.<FreeActionResolver.Interpretation>value(INTERPRETATION).orElseThrow();
    }
    FreeActionResolver.Resolution resolution() {
        return this.<FreeActionResolver.Resolution>value(RESOLUTION).orElseThrow();
    }
    FreeActionResolver.Review review() { return this.<FreeActionResolver.Review>value(REVIEW).orElseThrow(); }
    DriverResult settlement() { return this.<DriverResult>value(SETTLEMENT).orElse(null); }
}
