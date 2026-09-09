package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import mvp.ai.GameClient;
import mvp.entity.Character;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.EventRecordService;
import mvp.service.RegionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FamilyBackgroundServiceImplTest {

    private static final String SAVE_ID = "save-1";
    private static final String PLAYER_ID = "player-1";
    private static final String BACKGROUND_ID = "background-1";
    private static final String OPERATION = "CHILDHOOD_BACKGROUND";
    private static final String REQUEST_ID = OPERATION + "/" + SAVE_ID;

    private GameSaveMapper gameSaveMapper;
    private CharacterService characterService;
    private EventRecordService eventRecordService;
    private GameClient gameClient;
    private RecordingTransactionManager transactionManager;
    private FamilyBackgroundServiceImpl service;
    private LambdaQueryChainWrapper<FamilyBackground> backgroundQuery;
    private FamilyBackground background;
    private GameSave gameSave;
    private Character player;

    @BeforeEach
    void setUp() {
        gameSaveMapper = mock(GameSaveMapper.class);
        characterService = mock(CharacterService.class);
        RegionService regionService = mock(RegionService.class);
        eventRecordService = mock(EventRecordService.class);
        gameClient = mock(GameClient.class);
        transactionManager = new RecordingTransactionManager();
        service = spy(new FamilyBackgroundServiceImpl(gameSaveMapper, characterService,
                regionService, eventRecordService, gameClient, transactionManager));

        background = initialBackground();
        gameSave = new GameSave().setId(SAVE_ID).setBirthYear(1000).setCurrentYear(1008)
                .setCurrentMonth(3).setAge(8).setTotalTurnNumber(40L).setStatus("PLAYING");
        player = new Character().setId(PLAYER_ID).setSaveId(SAVE_ID).setType(1)
                .setName("张明").setBirthRegionId("region-1").setWallet(73)
                .setCharacterZhili(25).setCharacterJiankang(90);
        Character father = new Character().setId("father-1").setNpcCode("NPC_FUQIN").setName("张父");
        Character mother = new Character().setId("mother-1").setNpcCode("NPC_MUQIN").setName("李母");

        backgroundQuery = queryMock();
        doReturn(backgroundQuery).when(service).lambdaQuery();
        when(backgroundQuery.one()).thenReturn(background);
        LambdaQueryChainWrapper<Character> characterQuery = queryMock();
        when(characterService.lambdaQuery()).thenReturn(characterQuery);
        when(characterQuery.one()).thenReturn(player);
        when(characterQuery.list()).thenReturn(List.of(father, mother));
        when(gameSaveMapper.selectOne(any())).thenReturn(gameSave);
        when(regionService.getById("region-1"))
                .thenReturn(new Region().setId("region-1").setRegionName("江宁县"));
    }

    @Test
    void generatedBackgroundIsReturnedWithoutCallingAiOrStartingWriteTransaction() {
        background.setBackgroundSummary("已经保存的童年经历。");
        when(eventRecordService.replay(eq(SAVE_ID), eq(REQUEST_ID), any())).thenReturn(generatedMarker());

        assertSame(background, service.prepareNarrative(SAVE_ID));

        verifyNoInteractions(gameClient);
        verify(service, never()).updateById(any(FamilyBackground.class));
        verify(eventRecordService, never()).recordOperation(anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), any());
        assertEquals(List.of(true), transactionManager.readOnlyTransactions);
        assertEquals(1, transactionManager.commits);
    }

    @ParameterizedTest
    @MethodSource("invalidNarratives")
    void invalidAiNarrativeIsRejectedBeforeAnyWrite(String narrative) {
        givenAiNarrative(narrative);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.prepareNarrative(SAVE_ID));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        assertEquals("普通农户之家。", background.getBackgroundSummary());
        verify(service, never()).updateById(any(FamilyBackground.class));
        verify(eventRecordService, never()).recordOperation(anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), any());
        assertEquals(List.of(true), transactionManager.readOnlyTransactions);
    }

    @Test
    void narrativeAndMarkerUseSameWriteTransactionWithoutChangingInitialValuesOrGameProgress() {
        givenAiNarrative("  六岁之前，张明随父母在江宁县生活。\n");
        List<Object> writingTransactions = new ArrayList<>();
        doAnswer(invocation -> {
            assertWriteTransactionActive();
            writingTransactions.add(transactionManager.currentTransaction);
            assertSame(background, invocation.getArgument(0));
            assertEquals("六岁之前，张明随父母在江宁县生活。", background.getBackgroundSummary());
            return true;
        }).when(service).updateById(any(FamilyBackground.class));
        doAnswer(invocation -> {
            assertWriteTransactionActive();
            writingTransactions.add(transactionManager.currentTransaction);
            assertEquals(Map.of("familyBackgroundId", BACKGROUND_ID, "generated", true),
                    invocation.getArgument(6));
            return null;
        }).when(eventRecordService).recordOperation(eq(SAVE_ID), eq(PLAYER_ID), eq(REQUEST_ID),
                eq(Map.of("operation", OPERATION, "saveId", SAVE_ID)), eq(OPERATION), eq(0L), any());

        assertSame(background, service.prepareNarrative(SAVE_ID));

        assertEquals(2, writingTransactions.size());
        assertSame(writingTransactions.get(0), writingTransactions.get(1));
        assertEquals(List.of(true, false), transactionManager.readOnlyTransactions);
        assertEquals(2, transactionManager.commits);
        assertEquals(0, transactionManager.rollbacks);
        assertEquals(120, background.getInitialWealth());
        assertEquals(1000, gameSave.getBirthYear());
        assertEquals(1008, gameSave.getCurrentYear());
        assertEquals(8, gameSave.getAge());
        assertEquals(40L, gameSave.getTotalTurnNumber());
        assertEquals(73, player.getWallet());
        assertEquals(25, player.getCharacterZhili());
        assertEquals(90, player.getCharacterJiankang());
        verify(characterService, never()).updateById(any(Character.class));
        verify(gameSaveMapper, never()).updateById(any(GameSave.class));
    }

    @Test
    void concurrentGenerationKeepsTheBackgroundAlreadyCommittedByAnotherRequest() {
        givenAiNarrative("较晚完成的模型结果。");
        FamilyBackground concurrentBackground = initialBackground().setBackgroundSummary("另一请求已保存的童年经历。");
        when(backgroundQuery.one()).thenReturn(background, concurrentBackground);
        when(eventRecordService.replay(eq(SAVE_ID), eq(REQUEST_ID), any()))
                .thenReturn(null, generatedMarker());

        FamilyBackground result = service.prepareNarrative(SAVE_ID);

        assertSame(concurrentBackground, result);
        assertEquals("另一请求已保存的童年经历。", result.getBackgroundSummary());
        verify(gameClient).chat(eq("PROMPT_CHILDHOOD_BACKGROUND"), anyString(),
                eq(FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput.class));
        verify(service, never()).updateById(any(FamilyBackground.class));
        verify(eventRecordService, never()).recordOperation(anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), any());
        assertEquals(List.of(true, false), transactionManager.readOnlyTransactions);
    }

    @Test
    void failingToRecordGeneratedMarkerRollsBackTheNarrativeWriteTransaction() {
        givenAiNarrative("待保存的童年经历。");
        doReturn(true).when(service).updateById(any(FamilyBackground.class));
        IllegalStateException markerFailure = new IllegalStateException("模拟事件保存失败");
        doThrow(markerFailure).when(eventRecordService).recordOperation(anyString(), anyString(), anyString(),
                any(), anyString(), anyLong(), any());

        assertSame(markerFailure, assertThrows(IllegalStateException.class,
                () -> service.prepareNarrative(SAVE_ID)));

        verify(service).updateById(background);
        assertEquals(List.of(true, false), transactionManager.readOnlyTransactions);
        assertEquals(1, transactionManager.commits);
        assertEquals(1, transactionManager.rollbacks);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static Stream<String> invalidNarratives() {
        return Stream.of(null, "", " \t\r\n", "字".repeat(1_001));
    }

    @SuppressWarnings("unchecked")
    private static <T> LambdaQueryChainWrapper<T> queryMock() {
        // 泛型链方法擦除后可能返回 Object，Mockito 的 RETURNS_SELF 不会为它们返回自身。
        return mock(LambdaQueryChainWrapper.class, invocation -> switch (invocation.getMethod().getName()) {
            case "eq", "in", "orderByAsc" -> invocation.getMock();
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static FamilyBackground initialBackground() {
        return new FamilyBackground().setId(BACKGROUND_ID).setSaveId(SAVE_ID)
                .setInitialWealth(120).setBackgroundSummary("普通农户之家。");
    }

    private static JSONObject generatedMarker() {
        return new JSONObject().set("familyBackgroundId", BACKGROUND_ID).set("generated", true);
    }

    private void givenAiNarrative(String narrative) {
        when(gameClient.chat(eq("PROMPT_CHILDHOOD_BACKGROUND"), anyString(),
                eq(FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "模型调用不应持有数据库事务");
                    return new FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput(narrative);
                });
    }

    private void assertWriteTransactionActive() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertNotNull(transactionManager.currentTransaction);
    }

    /** 只验证服务的事务边界和回滚请求，不模拟数据库实际提交或持久化回滚。 */
    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<Boolean> readOnlyTransactions = new ArrayList<>();
        private Object currentTransaction;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            currentTransaction = transaction;
            readOnlyTransactions.add(definition.isReadOnly());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            currentTransaction = null;
        }
    }
}
