package mvp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import mvp.ai.FreeActionWorkflow;
import mvp.engine.CharacterEngine;
import mvp.engine.TurnEngine;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.ExamRecord;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.mapper.DialogueRecordMapper;
import mvp.service.*;
import mvp.utils.ClasspathJsonLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameActionServiceTest {
    private GameSaveServiceImpl service;
    private CharacterService characters;
    private EventRecordService events;
    private BookService books;
    private ExamRecordService exams;
    private GameSave save;
    private Character player;

    @BeforeEach
    void setup() {
        save = new GameSave().setId("save").setBirthYear(1541).setCurrentYear(1547)
                .setCurrentMonth(1).setTurnInMonth(1).setAge(6).setTotalTurnNumber(0L)
                .setStatus("STUDYING").setGrowthStage("CHILDHOOD");
        player = new Character().setId("player").setSaveId("save").setType(1).setEnabled(true)
                .setBirthday("1541-01-01").setCharacterZhili(20).setCharacterDaode(20)
                .setCharacterZhengzhi(20).setCharacterJiaoji(20).setCharacterTineng(20)
                .setCharacterJiankang(75).setCharacterPilao(40).setSickTurnsRemaining(0);
        var scholar = new CareerProfileShusheng().setCharacterId("player").setAbilityShizi(5)
                .setAbilityJingyi(0).setAbilityWenzhang(0).setAbilityCelun(0).setAbilityWenxue(0);
        characters = mock(CharacterService.class);
        doReturn(chain(player)).when(characters).lambdaQuery();
        var careers = mock(CareerProfileShushengService.class);
        doReturn(chain(scholar)).when(careers).lambdaQuery();
        var families = mock(FamilyBackgroundService.class);
        doReturn(chain(new FamilyBackground())).when(families).lambdaQuery();
        events = mock(EventRecordService.class);
        doReturn(chain(null)).when(events).lambdaQuery();
        books = mock(BookService.class);
        when(books.totalKnowledge(anyString())).thenReturn(BigDecimal.ZERO);
        exams = mock(ExamRecordService.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = spy(new GameSaveServiceImpl(new CharacterEngine(), mock(DialogueRecordMapper.class),
                new TurnEngine(), characters, families, careers, mock(RegionService.class), books,
                mock(EquipmentRecordService.class), mock(FreeActionWorkflow.class),
                transactions, exams, events,
                mock(MemoryRecordService.class), new ClasspathJsonLoader()));
        doReturn(chain(save)).when(service).lambdaQuery();
        doReturn(true).when(service).updateById(any(GameSave.class));
        ReflectionTestUtils.invokeMethod(service, "loadActionRules");
    }

    @Test
    void majorActionsShareOneSlotAndEndTurnDoesNotHeal() {
        service.executeFixedAction("save", action("rest", "REST", "SCENE_JIA_WOSHI", 0));
        assertEquals(34, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        assertEquals(0L, save.getTotalTurnNumber());
        assertEquals(0L, player.getMajorActionTurn());
        var blocked = assertThrows(ResponseStatusException.class, () -> service.executeFixedAction("save",
                action("practice", "PRACTICE_WRITING", "SCENE_SISHU_JIANGTANG", 0)));
        assertEquals(409, blocked.getStatusCode().value());
        service.endTurn("save", new GameSaveService.EndTurnCommand("next", 0L));
        assertEquals(1L, save.getTotalTurnNumber());
        assertEquals(34, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        service.executeFixedAction("save", action("rest2", "REST", "SCENE_JIA_WOSHI", 1));
        assertEquals(54, player.getStamina());
        assertEquals(1L, player.getMajorActionTurn());
    }

    @Test
    void insufficientStaminaDoesNotSpendTheSlotOrWriteAnEvent() {
        player.setCharacterPilao(51);
        var blocked = assertThrows(ResponseStatusException.class, () -> service.executeFixedAction("save",
                action("practice", "PRACTICE_WRITING", "SCENE_SISHU_JIANGTANG", 0)));
        assertEquals(409, blocked.getStatusCode().value());
        assertEquals(3, player.getStamina());
        assertNull(player.getMajorActionTurn());
        verify(characters, never()).updateById(any(Character.class));
        verify(events, never()).recordOperation(anyString(), anyString(), anyString(), any(), anyString(), anyLong(), any());
    }

    @Test
    void fixedActionReplayReturnsOriginalWithoutChargingOrUsingSlot() {
        JSONObject cached = new JSONObject().set("feedback", "cached");
        when(events.replay(eq("save"), eq("repeat"), any())).thenReturn(cached);
        assertSame(cached, service.executeFixedAction("save", action("repeat", "REST", "SCENE_JIA_WOSHI", 0)));
        assertEquals(14, player.getStamina());
        assertNull(player.getMajorActionTurn());
    }

    @Test
    void readingQuestionOnlyRequiresBookCostAndDoesNotSpendStaminaOrSlot() {
        BookService.LibraryBook book = mock(BookService.LibraryBook.class);
        when(book.bookCode()).thenReturn("BOOK_TEST");
        when(book.equipmentId()).thenReturn("book");
        when(book.bookName()).thenReturn("测试书");
        when(book.fatigueCost()).thenReturn(12);
        when(books.requirePlayerReadingBook(any(), anyString(), any(), any(), eq("BOOK_TEST"))).thenReturn(book);
        when(books.generatePlayerReadingQuestion(eq(book), anyString())).thenReturn("阅读题目");
        var command = new GameSaveService.PlayerReadingQuestionCommand("SCENE_SISHU_JIANGTANG", 0L);

        JSONObject result = service.preparePlayerReading("save", "BOOK_TEST", command);
        assertEquals("阅读题目", result.getStr("question"));
        assertEquals(14, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        assertNull(player.getMajorActionTurn());
        when(events.replay(eq("save"), eq(result.getStr("questionId")), any())).thenReturn(result);
        assertSame(result, service.preparePlayerReading("save", "BOOK_TEST", command));
        verify(books, times(1)).generatePlayerReadingQuestion(any(), anyString());
        verify(characters, never()).updateById(any(Character.class));
    }

    @Test
    void examThoughtIsFreeAtZeroStaminaAndCachedWithoutCallingAgain() {
        player.setCharacterPilao(player.getMaxStamina());
        ExamRecord exam = new ExamRecord().setId("exam").setStatus("READY");
        when(exams.loadForCharacter(any(), eq("player"), eq("exam"))).thenReturn(exam);
        when(exams.generateThought(eq(exam), anyString())).thenReturn("作答思路");
        when(exams.saveThought(any(), eq("player"), eq(exam), eq("作答思路")))
                .thenAnswer(call -> exam.setAiThoughtBubble("作答思路"));

        assertSame(exam, service.prepareExamThought("save", "exam"));
        assertSame(exam, service.prepareExamThought("save", "exam"));
        assertEquals(0, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        verify(exams, times(1)).generateThought(any(), anyString());
        verify(characters, never()).updateById(any(Character.class));
    }

    @Test
    void examSubmissionChargesItsFivePointBaseCostOnlyOnce() {
        ExamRecord exam = new ExamRecord().setId("exam").setStatus("READY")
                .setExamType(TurnEngine.EXAM_MENGXUE);
        when(exams.loadForCharacter(any(), eq("player"), eq("exam"))).thenReturn(exam);
        var resolution = mock(ExamRecordService.ExamResolution.class);
        when(exams.resolveAuto(eq(exam), anyString())).thenReturn(resolution);
        when(exams.settleResolved(any(), eq("player"), eq(exam), eq(resolution))).thenAnswer(call -> {
            boolean newlySettled = "READY".equals(exam.getStatus());
            exam.setStatus("COMPLETED_PASS");
            return new ExamRecordService.ExamSettlement(exam, newlySettled);
        });

        assertTrue(service.completeAutoExam("save", "exam").newlySettled());
        assertEquals(9, player.getStamina());
        assertEquals(74, player.getCharacterJiankang());
        assertFalse(service.completeAutoExam("save", "exam").newlySettled());
        assertEquals(9, player.getStamina());
        assertEquals(74, player.getCharacterJiankang());
        verify(characters, times(1)).updateById(any(Character.class));
    }

    @Test
    void zeroHealthCanEnterRecoveryByEndingTurn() {
        player.setCharacterJiankang(0).setCharacterPilao(54);
        service.endTurn("save", new GameSaveService.EndTurnCommand("recover", 0L));
        assertEquals(4L, save.getTotalTurnNumber());
        assertEquals(40, player.getCharacterJiankang());
        assertEquals(0, player.getSickTurnsRemaining());
        assertEquals(18, player.getCharacterTineng());
        assertEquals(player.getMaxStamina(), player.getStamina());
    }

    @Test
    void entityJsonIncludesComputedStaminaAndPreservesItOnRoundTrip() {
        JSONObject json = JSONUtil.parseObj(player);
        assertEquals(54, json.getInt("maxStamina"));
        assertEquals(14, json.getInt("stamina"));
        assertEquals(14, JSONUtil.toBean(json, Character.class).getStamina());
    }

    private GameSaveService.FixedActionCommand action(String request, String code, String scene, long turn) {
        return new GameSaveService.FixedActionCommand(request, code, scene, null, turn);
    }

    @SuppressWarnings("unchecked")
    private static <T> LambdaQueryChainWrapper<T> chain(T one) {
        LambdaQueryChainWrapper<T> query = mock(LambdaQueryChainWrapper.class, call -> switch (call.getMethod().getName()) { case "eq", "last", "orderByAsc", "in" -> call.getMock(); default -> RETURNS_DEFAULTS.answer(call); });
        when(query.one()).thenReturn(one);
        when(query.list()).thenReturn(List.of());
        return query;
    }
}
