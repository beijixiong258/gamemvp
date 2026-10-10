package mvp.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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

    // 图的默认序列化器把 Map（包括 JSONObject）还原为 HashMap；在边界恢复 JSON 视图。
    JSONObject context() { return JSONUtil.parseObj(value(CONTEXT).orElseThrow()); }
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
