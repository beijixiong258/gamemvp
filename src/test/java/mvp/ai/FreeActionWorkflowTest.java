package mvp.ai;

import cn.hutool.json.JSONObject;
import mvp.ai.FreeActionResolver.*;
import mvp.engine.CharacterEngine;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.engine.CharacterEngine.ScholarState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreeActionWorkflowTest {
    private final CharacterState character = new CharacterState(20, 20, 20, 20, 20, 75, 0);
    private final ScholarState scholar = new ScholarState(5, 0, 0, 0, 0);
    private final CharacterEngine engine = new CharacterEngine();
    private final DriverPatch patch = new DriverPatch(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private final Interpretation interpretation = new Interpretation(
            List.of(new Statement("current", StatementKind.QUESTION, "询问字义")), List.of());

    @Test
    void dialogueSurvivesRealGraphSerializationThroughAllFourNodes() {
        var resolver = resolver(true);
        var workflow = new FreeActionWorkflow(resolver, engine);
        var input = new JSONObject().set("facts", facts()).set("counterpart", npc())
                .set("currentText", "先生，这个字是什么意思？").set("history", List.of(
                        new JSONObject().set("speaker", "actor").set("text", "先生好")))
                .set("dialogueRound", 1).set("maxDialogueRounds", 5).set("maxReplyCharacters", 100);
        var result = workflow.executeDialogue(input.toString(), character, scholar);
        assertTrue(result.resolution().endDialogue());
        assertEquals("这个字表示守信，今日记住这一点即可。", result.resolution().narrative());
        assertEquals(engine.applyDriver(character, scholar, patch), result.settlement());
        verify(resolver).interpret(any(JSONObject.class));
        verify(resolver).resolve(any(JSONObject.class), eq(interpretation));
        verify(resolver).review(any(JSONObject.class), eq(interpretation), eq(result.resolution()));
    }

    @Test
    void freeActionSurvivesTheSameGraphAndPreservesTypedSettlement() {
        var workflow = new FreeActionWorkflow(resolver(false), engine);
        var result = workflow.execute(new FreeActionWorkflow.FreeActionCommand(
                "我对照书本辨认这个字。", facts().toString(), character, scholar));
        assertEquals(engine.applyDriver(character, scholar, patch), result.settlement());
        assertFalse(result.lifeMilestone());
        assertTrue(result.acquisitions().isEmpty());
    }

    @Test
    void graphKeepsBusinessRejectionInsteadOfTurningItIntoACastFailure() {
        var resolver = mock(FreeActionResolver.class);
        var expected = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI结果未通过检查，本次未结算");
        when(resolver.interpret(any())).thenThrow(expected);
        var workflow = new FreeActionWorkflow(resolver, engine);
        var failure = assertThrows(ResponseStatusException.class, () -> workflow.execute(
                new FreeActionWorkflow.FreeActionCommand("询问字义", facts().toString(), character, scholar)));
        assertSame(expected, failure);
        verify(resolver, never()).resolve(any(), any());
    }

    private FreeActionResolver resolver(boolean dialogue) {
        var resolver = mock(FreeActionResolver.class);
        when(resolver.interpret(any())).thenAnswer(call -> {
            JSONObject context = call.getArgument(0);
            assertEquals(dialogue ? "DIALOGUE" : "FREE_ACTION", context.getStr("mode"));
            assertEquals("player", context.getJSONObject("facts").getJSONObject("actor").getStr("id"));
            assertEquals("teacher", context.getJSONObject("facts").getJSONArray("npcs").getJSONObject(0).getStr("id"));
            assertEquals("player", context.getJSONObject("sources").getJSONObject("current").getStr("speakerId"));
            if (dialogue) assertEquals("先生好", context.getJSONArray("history").getJSONObject(0).getStr("text"));
            return interpretation;
        });
        var resolution = new Resolution(patch, "这个字表示守信，今日记住这一点即可。", dialogue, false,
                List.of(), List.of(), List.of(), List.of(
                        new EffectEvidence(EffectKind.NARRATIVE, 0, List.of("current")),
                        new EffectEvidence(EffectKind.DRIVERS, 0, List.of("current"))));
        when(resolver.resolve(any(), eq(interpretation))).thenReturn(resolution);
        when(resolver.review(any(), eq(interpretation), eq(resolution)))
                .thenReturn(new Review(true, List.of(), "原文支持本轮收获"));
        return resolver;
    }

    private JSONObject facts() {
        return new JSONObject().set("actor", new JSONObject().set("id", "player").set("type", 1))
                .set("npcs", List.of(npc())).set("scene", new JSONObject().set("sceneCode", "SCENE_SISHU_JIANGTANG"));
    }

    private JSONObject npc() {
        return new JSONObject().set("id", "teacher").set("npcCode", "NPC_TEACHER").set("name", "塾师");
    }
}
