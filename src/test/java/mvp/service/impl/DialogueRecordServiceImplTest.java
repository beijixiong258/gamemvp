package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import mvp.ai.FreeActionResolver;
import mvp.ai.FreeActionResolver.DialogueResolution;
import mvp.engine.CharacterEngine;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.entity.Character;
import mvp.entity.DialogueRecord;
import mvp.entity.GameSave;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.DialogueRecordService.DialogueCommand;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EventRecordService;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.ActionContext;
import mvp.service.MemoryRecordService;
import mvp.utils.ClasspathJsonLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DialogueRecordServiceImplTest {
    private static final String SAVE_ID = "save-dialogue-test";
    private static final String DIALOGUE_ID = "dialogue-test";
    private static final String ACTOR_ID = "player-test";
    private static final String COUNTERPART_ID = "teacher-test";
    private static final String SCENE_CODE = "school-test";
    private static final String REQUEST_ID = "dialogue-message-test";

    private GameSaveService gameSaveService;
    private GameSaveMapper gameSaveMapper;
    private CharacterService characterService;
    private EventRecordService eventRecordService;
    private MemoryRecordService memoryRecordService;
    private FreeActionResolver resolver;
    private CharacterEngine characterEngine;
    private PlatformTransactionManager transactionManager;
    private DialogueRecordServiceImpl service;
    private ActionContext snapshot;

    @BeforeEach
    void setUp() {
        gameSaveService = mock(GameSaveService.class);
        gameSaveMapper = mock(GameSaveMapper.class);
        characterService = mock(CharacterService.class);
        eventRecordService = mock(EventRecordService.class);
        memoryRecordService = mock(MemoryRecordService.class);
        resolver = mock(FreeActionResolver.class);
        characterEngine = mock(CharacterEngine.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = spy(new DialogueRecordServiceImpl(gameSaveService, gameSaveMapper, characterService,
                eventRecordService, memoryRecordService, mock(ClasspathJsonLoader.class), resolver,
                characterEngine, transactionManager));
        snapshot = new ActionContext(SAVE_ID, ACTOR_ID, 3L, SCENE_CODE,
                new CharacterState(50, 50, 50, 50, 50, 80, 0),
                new ScholarState(10, 0, 0, 0, 0), "{\"turnNumber\":3}");
    }

    @Test
    void fifthRoundWithoutAiEndIsRejectedBeforeSettlementAndHistoryWrite() {
        DialogueRecord dialogue = prepareRound(5);
        String originalHistory = dialogue.getMessagesJson();
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution("还有什么想问的？", false, null,
                        List.of(new AcquisitionIntent("teacher", "book", 1))));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.respond(SAVE_ID, DIALOGUE_ID, command(5, false)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals(originalHistory, dialogue.getMessagesJson());
        assertEquals(4, dialogue.getVersion());
        assertFalse(dialogue.getEnded());
        verifyNoInteractions(characterEngine, transactionManager, gameSaveMapper);
        verifyNoSettlementOrHistoryWrite();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void blankAiReplyIsRejectedBeforeAnySettlement(String reply) {
        DialogueRecord dialogue = prepareRound(1);
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution(reply, false, null, List.of()));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.respond(SAVE_ID, DIALOGUE_ID, command(1, false)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals("[]", dialogue.getMessagesJson());
        assertEquals(0, dialogue.getVersion());
        verifyNoInteractions(characterEngine, transactionManager, gameSaveMapper);
        verifyNoSettlementOrHistoryWrite();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void aiCanEndEveryAllowedRoundWithOneSettlement(int round) {
        DialogueRecord dialogue = prepareRound(round);
        TransactionStatus transaction = prepareTransaction(dialogue);
        DriverPatch patch = mock(DriverPatch.class);
        DriverResult settlement = new DriverResult(snapshot.character(), snapshot.scholar(), 0);
        List<AcquisitionIntent> trades = List.of(new AcquisitionIntent("teacher", "book", 1));
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution("今日就讲到这里，下次再会。", true, patch, trades));
        when(characterEngine.applyDriver(snapshot.character(), snapshot.scholar(), patch)).thenReturn(settlement);

        JSONObject result = service.respond(SAVE_ID, DIALOGUE_ID, command(round, false));

        assertTrue(dialogue.getEnded());
        assertEquals(round, dialogue.getVersion());
        JSONArray history = JSONUtil.parseArray(dialogue.getMessagesJson());
        assertEquals(round * 2, history.size());
        assertEquals("今日就讲到这里，下次再会。", history.getJSONObject(history.size() - 1).getStr("text"));
        assertTrue(result.getJSONObject("dialogue").getBool("ended"));
        verify(characterEngine, times(1)).applyDriver(snapshot.character(), snapshot.scholar(), patch);
        verify(gameSaveService, times(1)).settleAiAction(same(snapshot), eq(REQUEST_ID + "/settlement"), any(),
                same(settlement), eq(trades), eq(false), eq("今日就讲到这里，下次再会。"), eq(false));
        verify(service, times(1)).updateById(same(dialogue));
        verify(eventRecordService, times(1)).recordOperation(eq(SAVE_ID), eq(ACTOR_ID), eq(REQUEST_ID), any(),
                eq("END_DIALOGUE"), eq(3L), same(result));
        verify(transactionManager).commit(transaction);
    }

    @Test
    void promptReceivesCurrentRoundAndOnlyCounterpartMemoryAsJsonArray() {
        DialogueRecord dialogue = prepareRound(2);
        prepareTransaction(dialogue);
        when(memoryRecordService.recall(SAVE_ID, COUNTERPART_ID, ACTOR_ID, 5000))
                .thenReturn("[{\"memoryId\":\"memory-1\",\"summary\":\"你上次问过《论语》。\"}]");
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution("接着上次的问题说吧。", false, null, List.of()));

        service.respond(SAVE_ID, DIALOGUE_ID, command(2, false));

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(resolver).resolveDialogue(input.capture());
        JSONObject facts = JSONUtil.parseObj(input.getValue());
        assertEquals(2, facts.getInt("dialogueRound"));
        assertEquals(5, facts.getInt("maxDialogueRounds"));
        JSONArray memories = assertInstanceOf(JSONArray.class, facts.get("memoryContext"));
        assertEquals(1, memories.size());
        assertEquals("你上次问过《论语》。", memories.getJSONObject(0).getStr("summary"));
        assertEquals(2, facts.getJSONArray("history").size());
        verify(memoryRecordService, times(1)).recall(SAVE_ID, COUNTERPART_ID, ACTOR_ID, 5000);
        verify(memoryRecordService, never()).recall(eq(SAVE_ID), eq(ACTOR_ID), anyString(), anyInt());
        verifyNoInteractions(characterEngine);
        verify(gameSaveService).settleAiAction(same(snapshot), eq(REQUEST_ID + "/settlement"), any(),
                isNull(), eq(List.of()), eq(false), eq("接着上次的问题说吧。"), eq(false));
        assertFalse(dialogue.getEnded());
    }

    @Test
    void retransmissionReturnsRecordedResultWithoutCallingModelOrLoadingDialogue() {
        JSONObject recorded = new JSONObject().set("reply", "已保存的回应");
        when(eventRecordService.replay(eq(SAVE_ID), eq(REQUEST_ID), any())).thenReturn(recorded);

        JSONObject result = service.respond(SAVE_ID, DIALOGUE_ID, command(5, false));

        assertSame(recorded, result);
        verify(service, never()).loadDialogue(anyString(), anyString());
        verifyNoInteractions(gameSaveService, characterService, memoryRecordService, resolver,
                characterEngine, transactionManager, gameSaveMapper);
    }

    @Test
    void endedDialogueCannotProduceSixthRound() {
        DialogueRecord dialogue = prepareRound(6).setEnded(true);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.respond(SAVE_ID, DIALOGUE_ID, command(6, false)));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(5, dialogue.getVersion());
        verifyNoInteractions(resolver, characterEngine, transactionManager, memoryRecordService);
        verifyNoSettlementOrHistoryWrite();
    }

    @Test
    void endingWithoutAttributePatchIsRejected() {
        prepareRound(5);
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution("告辞。", true, null, List.of()));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.respond(SAVE_ID, DIALOGUE_ID, command(5, false)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verifyNoInteractions(characterEngine, transactionManager, gameSaveMapper);
        verifyNoSettlementOrHistoryWrite();
    }

    @Test
    void manualEndDoesNotExecutePurchasesMentionedInHistoryAgain() {
        DialogueRecord dialogue = prepareRound(2);
        prepareTransaction(dialogue);
        DriverPatch patch = mock(DriverPatch.class);
        DriverResult settlement = new DriverResult(snapshot.character(), snapshot.scholar(), 0);
        when(resolver.resolveDialogue(anyString())).thenReturn(
                new DialogueResolution("下次再见。", false, patch,
                        List.of(new AcquisitionIntent("teacher", "book", 1))));
        when(characterEngine.applyDriver(snapshot.character(), snapshot.scholar(), patch)).thenReturn(settlement);

        service.respond(SAVE_ID, DIALOGUE_ID, new DialogueCommand(REQUEST_ID, null, 1, true));

        assertTrue(dialogue.getEnded());
        verify(gameSaveService).settleAiAction(same(snapshot), eq(REQUEST_ID + "/settlement"), any(),
                same(settlement), eq(List.of()), eq(false), eq("下次再见。"), eq(false));
        JSONArray history = JSONUtil.parseArray(dialogue.getMessagesJson());
        assertTrue(history.getJSONObject(2).getBool("manualEnd"));
        assertEquals("", history.getJSONObject(2).getStr("text"));
    }

    private DialogueRecord prepareRound(int round) {
        JSONArray history = new JSONArray();
        for (int previousRound = 1; previousRound < round; previousRound++) {
            history.add(new JSONObject().set("speaker", "actor").set("text", "此前的问题"));
            history.add(new JSONObject().set("speaker", "counterpart").set("text", "此前的回答"));
        }
        DialogueRecord dialogue = new DialogueRecord().setId(DIALOGUE_ID).setSaveId(SAVE_ID)
                .setActorId(ACTOR_ID).setCounterpartId(COUNTERPART_ID).setSceneCode(SCENE_CODE)
                .setStartedTurnNumber(3L).setVersion(round - 1).setEnded(false).setMessagesJson(history.toString());
        doReturn(dialogue).when(service).loadDialogue(SAVE_ID, DIALOGUE_ID);
        when(gameSaveService.prepareAction(SAVE_ID, ACTOR_ID, SCENE_CODE)).thenReturn(snapshot);
        when(characterService.getById(COUNTERPART_ID)).thenReturn(
                new Character().setId(COUNTERPART_ID).setSaveId(SAVE_ID).setEnabled(true));
        when(memoryRecordService.recall(SAVE_ID, COUNTERPART_ID, ACTOR_ID, 5000)).thenReturn("[]");
        return dialogue;
    }

    @SuppressWarnings("unchecked")
    private TransactionStatus prepareTransaction(DialogueRecord dialogue) {
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        when(gameSaveMapper.selectOne(any())).thenReturn(new GameSave().setId(SAVE_ID));
        LambdaQueryChainWrapper<DialogueRecord> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(query).eq(any(), any());
        doReturn(query).when(query).last(anyString());
        doReturn(query).when(service).lambdaQuery();
        when(query.one()).thenReturn(dialogue);
        doReturn(true).when(service).updateById(any(DialogueRecord.class));
        when(gameSaveService.settleAiAction(any(), anyString(), any(), any(), any(), eq(false), anyString(), eq(false)))
                .thenReturn(new JSONObject().set("trades", new JSONArray()));
        return transaction;
    }

    private DialogueCommand command(int round, boolean manualEnd) {
        return new DialogueCommand(REQUEST_ID, "先生，请指教。", round - 1, manualEnd);
    }

    private void verifyNoSettlementOrHistoryWrite() {
        verify(gameSaveService, never()).settleAiAction(any(), anyString(), any(), any(), any(), anyBoolean(),
                anyString(), anyBoolean());
        verify(service, never()).updateById(any(DialogueRecord.class));
        verify(eventRecordService, never()).recordOperation(anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), any());
    }
}
