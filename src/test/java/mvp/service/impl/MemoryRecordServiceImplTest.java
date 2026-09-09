package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import mvp.ai.GameClient;
import mvp.entity.EventRecord;
import mvp.entity.MemoryRecord;
import mvp.mapper.CharacterMapper;
import mvp.mapper.EventRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryRecordServiceImplTest {
    private static final Path MEMORY_ROOT = Path.of("memory").toAbsolutePath().normalize();
    private static final Path TEST_TRASH = Path.of("C:\\Users\\user\\Desktop\\智能体回收站\\垃圾桶")
            .resolve("mvp-memory-tests-" + UUID.randomUUID());

    private EventRecordMapper eventMapper;
    private GameClient gameClient;
    private MemoryRecordServiceImpl service;
    private String saveId;
    private String ownerId;
    private Path saveDirectory;

    @BeforeEach
    void setUp() {
        eventMapper = mock(EventRecordMapper.class);
        gameClient = mock(GameClient.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new MemoryRecordServiceImpl(gameClient, eventMapper, mock(CharacterMapper.class), transactionManager);
        saveId = uuid32();
        ownerId = uuid32();
        saveDirectory = MEMORY_ROOT.resolve(saveId).normalize();
    }

    @AfterEach
    void moveOnlyThisTestsFilesToTrash() throws IOException {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (Files.exists(saveDirectory)) {
            // 本机约定：测试夹具移入指定垃圾桶，禁止永久删除，也不碰其他存档。
            assertEquals(MEMORY_ROOT, saveDirectory.getParent());
            assertEquals(saveId, saveDirectory.getFileName().toString());
            assertTrue(saveId.matches("[0-9a-f]{32}"));
            assertFalse(Files.isSymbolicLink(saveDirectory));
            assertTrue(TEST_TRASH.isAbsolute());
            Files.createDirectories(TEST_TRASH);
            Files.move(saveDirectory, TEST_TRASH.resolve(saveId));
        }
    }

    @Test
    void contextIncludesAtMostEightMemoriesInCandidateOrder() throws IOException {
        List<MemoryRecord> records = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            MemoryRecord record = record(index);
            publishFixture(record, "在书院与先生谈起读书。" + index);
            records.add(record);
        }

        JSONArray context = JSONUtil.parseArray(buildContext(records, 5000));

        assertEquals(8, context.size());
        for (int index = 0; index < context.size(); index++) {
            assertEquals(records.get(index).getId(), context.getJSONObject(index).getStr("memoryId"));
        }
    }

    @Test
    void completeJsonStaysWithinFiveThousandCodePointsWithoutBreakingEscapesOrRareCharacters() throws IOException {
        String summary = "𠀀说：\"书\\页\"\n\t".repeat(90);
        assertTrue(summary.codePointCount(0, summary.length()) <= 1000);
        List<MemoryRecord> records = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            MemoryRecord record = record(index);
            publishFixture(record, summary);
            records.add(record);
        }

        String json = buildContext(records, 5000);
        int length = json.codePointCount(0, json.length());
        JSONArray context = JSONUtil.parseArray(json);

        assertTrue(length <= 5000, "预算包含数组、标识、字段名和转义字符");
        assertTrue(length > 4900, "应保留预算内可用的记忆内容");
        assertTrue(context.size() > 1 && context.size() <= 8);
        assertEquals(json, context.toString(), "截断后的输出仍能完整解析并重新序列化");
        assertTrue(context.getJSONObject(context.size() - 1).getStr("summary").length() < summary.length());
        for (int index = 0; index < context.size(); index++) {
            String actual = context.getJSONObject(index).getStr("summary");
            assertFalse(actual.isEmpty());
            assertTrue(summary.startsWith(actual), "压缩应保留原摘要的完整 Unicode 前缀");
            assertWellFormedSurrogates(actual);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 20, 150, 200, 700})
    void smallerBudgetsStillReturnACompleteJsonArray(int budget) throws IOException {
        MemoryRecord record = record(1);
        publishFixture(record, "𠀀\"\\\n".repeat(150));

        String json = buildContext(List.of(record), budget);

        assertTrue(json.codePointCount(0, json.length()) <= budget);
        JSONArray context = assertDoesNotThrow(() -> JSONUtil.parseArray(json));
        assertTrue(context.size() <= 1);
        if (!context.isEmpty()) {
            assertWellFormedSurrogates(context.getJSONObject(0).getStr("summary"));
        }
    }

    @Test
    void pendingFileIsInvisibleUntilPublished() throws IOException {
        MemoryRecord record = record(1);
        Files.createDirectories(saveDirectory);
        Path published = file(record);
        Path pending = published.resolveSibling(published.getFileName() + ".pending");
        Files.writeString(pending, document(record, "尚未发布的记忆").toString(), StandardCharsets.UTF_8);

        assertNull(readSummary(record));
        assertEquals("[]", buildContext(List.of(record), 5000));

        Files.move(pending, published);

        assertEquals("尚未发布的记忆", readSummary(record));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "saveId", "ownerCharacterId", "sourceEventId", "version"})
    void fileMustMatchTheRequestedMemoryIdentityAndFormatVersion(String field) throws IOException {
        MemoryRecord record = record(1);
        JSONObject document = document(record, "不能借用其他人物或事件的记忆");
        document.set(field, "version".equals(field) ? 2 : uuid32());
        writeFixture(record, document.toString());

        assertNull(readSummary(record));
        assertEquals("[]", buildContext(List.of(record), 5000));
    }

    @Test
    void malformedBlankAndOversizedMemoryFilesAreSkipped() throws IOException {
        MemoryRecord record = record(1);
        writeFixture(record, "{broken-json");
        assertNull(readSummary(record));

        publishFixture(record, " \n\t");
        assertNull(readSummary(record));

        publishFixture(record, "𠀀".repeat(1001));
        assertNull(readSummary(record), "摘要上限按 Unicode 码点计算");

        writeFixture(record, document(record, "有效摘要").set("padding", "x".repeat(32768)).toString());
        assertNull(readSummary(record), "超过文件大小上限的文档不应载入");
    }

    @Test
    void writerPublishesReadableJsonAndLeavesNoPendingFile() {
        MemoryRecord record = record(1);
        String summary = "𠀀回忆先生说：\"明日带书来。\"\n书页标记：\\一";

        ReflectionTestUtils.invokeMethod(service, "writeSummary", record, summary);

        assertTrue(Files.isRegularFile(file(record)));
        assertFalse(Files.exists(file(record).resolveSibling(record.getId() + ".json.pending")));
        assertEquals(summary, readSummary(record));
    }

    @Test
    void committedEventIsReadOnlyAfterTheTransactionCommits() {
        EventRecord event = new EventRecord().setId(uuid32()).setSaveId(saveId)
                .setEventCode("END_DIALOGUE").setLifeMilestone(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.rememberAfterCommit(event);

        verifyNoInteractions(eventMapper, gameClient);
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.get(0).afterCommit();
        verify(eventMapper).selectById(event.getId());
        verifyNoInteractions(gameClient);
    }

    @Test
    void rolledBackEventDoesNotGenerateMemory() {
        EventRecord event = new EventRecord().setId(uuid32()).setSaveId(saveId)
                .setEventCode("END_DIALOGUE").setLifeMilestone(false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.rememberAfterCommit(event);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(eventMapper, gameClient);
    }

    private MemoryRecord record(long turnNumber) {
        return new MemoryRecord().setId(uuid32()).setSaveId(saveId).setOwnerCharacterId(ownerId)
                .setSourceEventId(uuid32()).setOccurredTurnNumber(turnNumber);
    }

    private Path file(MemoryRecord record) {
        return saveDirectory.resolve(record.getId() + ".json");
    }

    private JSONObject document(MemoryRecord record, String summary) {
        return new JSONObject().set("version", 1).set("id", record.getId()).set("saveId", record.getSaveId())
                .set("ownerCharacterId", record.getOwnerCharacterId()).set("sourceEventId", record.getSourceEventId())
                .set("occurredTurnNumber", record.getOccurredTurnNumber()).set("summary", summary);
    }

    private void publishFixture(MemoryRecord record, String summary) throws IOException {
        writeFixture(record, document(record, summary).toString());
    }

    private void writeFixture(MemoryRecord record, String content) throws IOException {
        Files.createDirectories(saveDirectory);
        Files.writeString(file(record), content, StandardCharsets.UTF_8);
    }

    private String buildContext(List<MemoryRecord> records, int budget) {
        return ReflectionTestUtils.invokeMethod(service, "buildContext", records, budget);
    }

    private String readSummary(MemoryRecord record) {
        return ReflectionTestUtils.invokeMethod(service, "readSummary", record);
    }

    private static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertWellFormedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (java.lang.Character.isHighSurrogate(character)) {
                assertTrue(index + 1 < value.length() && java.lang.Character.isLowSurrogate(value.charAt(index + 1)),
                        "生僻汉字不能留下孤立的高代理项");
                index++;
            } else {
                assertFalse(java.lang.Character.isLowSurrogate(character), "不能留下孤立的低代理项");
            }
        }
    }
}
