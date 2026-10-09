package mvp.service.impl;

import mvp.ai.GameClient;
import mvp.engine.ExamEngine;
import mvp.engine.TurnEngine;
import mvp.entity.ExamRecord;
import mvp.utils.ClasspathJsonLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExamAnswerScoringTest {
    private GameClient client;
    private ExamRecordServiceImpl service;

    @BeforeEach
    void setup() {
        client = mock(GameClient.class);
        service = new ExamRecordServiceImpl(new ClasspathJsonLoader(), new ExamEngine(), new TurnEngine(), client);
    }

    @Test
    void actualAnswerScoreDrivesResultAndIsPreservedInFeedback() {
        when(client.chat(eq("PROMPT_EXAM_ANSWER"), anyString(), eq(ExamRecordServiceImpl.NarrativeOutput.class)))
                .thenReturn(new ExamRecordServiceImpl.NarrativeOutput(null, "结算完成。"));
        evaluation(BigDecimal.valueOf(80));
        var strongAnswer = service.resolvePlayer(ready(0), "读书使人明理，并将所学用于日常待人处事。", "{}");
        assertTrue(strongAnswer.result().passed());
        assertEquals(42, strongAnswer.result().finalScore());
        assertTrue(strongAnswer.content().contains("答卷评分：80/100"));
        assertEquals(42, strongAnswer.result().effectiveContentModifier());

        evaluation(BigDecimal.valueOf(20));
        var weakAnswer = service.resolvePlayer(ready(100), "无关题目的回答。", "{}");
        assertFalse(weakAnswer.result().passed());
        assertEquals(14, weakAnswer.result().finalScore());
        assertEquals(-86, weakAnswer.result().effectiveContentModifier());
        assertTrue(weakAnswer.content().contains("答卷评分：20/100"));
    }

    @Test
    void invalidAiScoresCannotReachNarrationOrSettlement() {
        for (BigDecimal score : Arrays.asList(null, BigDecimal.valueOf(-1), BigDecimal.valueOf(101), new BigDecimal("60.5"))) {
            evaluation(score);
            var failure = assertThrows(ResponseStatusException.class,
                    () -> service.resolvePlayer(ready(36), "玩家原文", "{}"));
            assertEquals(502, failure.getStatusCode().value());
        }
        when(client.chat(eq("PROMPT_EXAM_EVALUATION"), anyString(), eq(ExamRecordServiceImpl.EvaluationOutput.class)))
                .thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> service.resolvePlayer(ready(36), "玩家原文", "{}"));
        verify(client, never()).chat(eq("PROMPT_EXAM_ANSWER"), anyString(), eq(ExamRecordServiceImpl.NarrativeOutput.class));
    }

    @Test
    void completedManualAnswerReplaysWithoutCallingAiAndCannotBeReplaced() {
        var completed = ready(0).setStatus("COMPLETED_PASS").setPlayerChoice("PLAYER")
                .setPlayerInput("原答卷").setFinalScore(42).setAiPlayerContentModifier(42)
                .setAiContent("答卷评分：80/100。原评价");
        var replay = service.resolvePlayer(completed, "原答卷", "{}");
        assertEquals(42, replay.result().finalScore());
        assertTrue(replay.content().contains("80/100"));
        var rejected = assertThrows(ResponseStatusException.class,
                () -> service.resolvePlayer(completed, "另一份答卷", "{}"));
        assertEquals(409, rejected.getStatusCode().value());
        verifyNoInteractions(client);
    }

    private void evaluation(BigDecimal score) {
        when(client.chat(eq("PROMPT_EXAM_EVALUATION"), anyString(), eq(ExamRecordServiceImpl.EvaluationOutput.class)))
                .thenReturn(new ExamRecordServiceImpl.EvaluationOutput(score, "结合了题目与所学。"));
    }

    private ExamRecord ready(int base) {
        return new ExamRecord().setId("exam").setExamType(TurnEngine.EXAM_XIANSHI)
                .setStatus("READY").setQuestionText("人为何要读书？")
                .setPassThreshold(33).setBaseAbilityScore(base).setStateOffset(0)
                .setDiceRoll(50).setLuckOffset(0).setKnowledgeTotal(BigDecimal.ZERO).setTurnNumber(360L);
    }
}
