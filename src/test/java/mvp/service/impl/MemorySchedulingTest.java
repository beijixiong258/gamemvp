package mvp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import mvp.ai.MemoryResolver;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.GameSave;
import mvp.entity.MemoryRecord;
import mvp.mapper.CharacterMapper;
import mvp.mapper.EventRecordMapper;
import mvp.mapper.GameSaveMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySchedulingTest {
    private static final String SAVE = "11111111111111111111111111111111";
    private static final String OWNER = "22222222222222222222222222222222";
    private static final String EVENT = "33333333333333333333333333333333";
    private final List<Runnable> queued = new ArrayList<>();
    private MemoryRecordServiceImpl service;
    private EventRecordMapper events;
    private MemoryResolver resolver;
    private TaskExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        events = mock(EventRecordMapper.class);
        resolver = mock(MemoryResolver.class);
        executor = mock(TaskExecutor.class);
        doAnswer(call -> { queued.add(call.getArgument(0)); return null; }).when(executor).execute(any());
        var characters = mock(CharacterMapper.class);
        when(characters.selectById(OWNER)).thenReturn(new Character().setId(OWNER).setSaveId(SAVE));
        var saves = mock(GameSaveMapper.class);
        when(saves.selectById(SAVE)).thenReturn(new GameSave().setId(SAVE).setTotalTurnNumber(0L));
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = spy(new MemoryRecordServiceImpl(resolver, events, characters, saves, transactions, executor));
        doReturn(List.of()).when(service).list(any(Wrapper.class));
        LambdaUpdateChainWrapper<MemoryRecord> update = mock(LambdaUpdateChainWrapper.class, call ->
                switch (call.getMethod().getName()) {
                    case "eq", "le", "set" -> call.getMock();
                    default -> RETURNS_DEFAULTS.answer(call);
                });
        doReturn(update).when(service).lambdaUpdate();
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void commitOnlyQueuesWorkAndDoesNotWaitForMemoryOrReadUncommittedEvents() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service.rememberAfterCommit(event());
        assertTrue(queued.isEmpty());
        verifyNoInteractions(events, resolver);

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertEquals(1, queued.size());
        verifyNoInteractions(events, resolver);
        TransactionSynchronizationManager.clear();
        queued.get(0).run();
        verify(events).selectById(EVENT);
    }

    @Test
    void rollbackDoesNotQueueMemory() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service.rememberAfterCommit(event());
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertTrue(queued.isEmpty());
        verifyNoInteractions(events, resolver);
    }

    @Test
    void recallReturnsAvailableContextWithoutWaitingForRepairsAndDeduplicatesPendingWork() {
        assertEquals("[]", service.recall(SAVE, OWNER, null, 1000));
        assertEquals("[]", service.recall(SAVE, OWNER, null, 1000));
        assertEquals(1, queued.size());
        verifyNoInteractions(events, resolver);
    }

    @Test
    void pendingEventIsNotQueuedTwiceAndWorkerFailureAllowsLaterRepair() {
        service.rememberAfterCommit(event());
        service.rememberAfterCommit(event());
        assertEquals(1, queued.size());
        when(events.selectById(EVENT)).thenThrow(new IllegalStateException("temporary read failure"));
        assertDoesNotThrow(() -> queued.get(0).run());
        service.rememberAfterCommit(event());
        assertEquals(2, queued.size());
    }

    @Test
    void queueRejectionDoesNotFailCommittedOperationOrLeaveStuckPendingKey() {
        doThrow(new RejectedExecutionException("busy")).when(executor).execute(any());
        assertDoesNotThrow(() -> service.rememberAfterCommit(event()));
        assertDoesNotThrow(() -> service.rememberAfterCommit(event()));
        verify(executor, times(2)).execute(any());
        verifyNoInteractions(events, resolver);
    }

    private EventRecord event() {
        return new EventRecord().setId(EVENT).setSaveId(SAVE).setEventSequence(1L)
                .setEventCode("FREE_ACTION").setOccurredTurnNumber(0L).setRelatedCharacterIdJson("[]");
    }
}
