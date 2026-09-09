package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import mvp.ai.GameClient;
import mvp.engine.GameRuleConstant;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.MemoryRecord;
import mvp.mapper.CharacterMapper;
import mvp.mapper.EventRecordMapper;
import mvp.mapper.MemoryRecordMapper;
import mvp.service.MemoryRecordService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MemoryRecordServiceImpl extends ServiceImpl<MemoryRecordMapper, MemoryRecord> implements MemoryRecordService {
    private static final int MAX_CONTEXT_CHARACTERS = GameRuleConstant.MEMORY_CONTEXT_MAX_CHARACTERS;
    private static final int MAX_CONTEXT_MEMORIES = 8;
    private static final int MAX_SUMMARY_CHARACTERS = 1000;
    private static final int REPAIR_BATCH_SIZE = 2;
    private static final int CANDIDATE_LIMIT = 32;
    private static final long RETRY_DELAY_SECONDS = 60;
    private static final Path MEMORY_ROOT = Path.of("memory").toAbsolutePath().normalize();
    private final GameClient gameClient;
    private final EventRecordMapper eventRecordMapper;
    private final CharacterMapper characterMapper;
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate indexTransaction;
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();
    private final Map<String, RepairCursor> repairCursors = new ConcurrentHashMap<>();
    private final Object[] memoryLocks = new Object[64];

    public MemoryRecordServiceImpl(GameClient gameClient, EventRecordMapper eventRecordMapper,
                                   CharacterMapper characterMapper, PlatformTransactionManager transactionManager) {
        this.gameClient = gameClient;
        this.eventRecordMapper = eventRecordMapper;
        this.characterMapper = characterMapper;
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.indexTransaction = new TransactionTemplate(transactionManager);
        this.indexTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int index = 0; index < memoryLocks.length; index++) {
            memoryLocks[index] = new Object();
        }
    }

    @Override
    public void rememberAfterCommit(EventRecord event) {
        if (!eligible(event) || !uuid32(event.getId()) || !uuid32(event.getSaveId())) {
            return;
        }
        String eventId = event.getId();
        Runnable remember = () -> {
            try {
                withoutTransaction.executeWithoutResult(status -> {
                    EventRecord committed = eventRecordMapper.selectById(eventId);
                    if (eligible(committed)) {
                        for (Character owner : participants(committed)) {
                            rememberSafely(committed, owner.getId());
                        }
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("事件{}已提交，记忆生成待后续回忆补齐：{}", eventId, exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    remember.run();
                }
            });
        } else {
            remember.run();
        }
    }

    @Override
    public String recall(String saveId, String ownerCharacterId, String relatedCharacterId, int maxCharacters) {
        int budget = Math.min(maxCharacters, MAX_CONTEXT_CHARACTERS);
        if (budget < 2) {
            return "";
        }
        if (!uuid32(saveId) || !uuid32(ownerCharacterId)) {
            return "[]";
        }
        String relatedId = uuid32(relatedCharacterId) ? relatedCharacterId : null;
        try {
            String result = withoutTransaction.execute(status -> {
                Character owner = characterMapper.selectById(ownerCharacterId);
                if (owner == null || !Objects.equals(saveId, owner.getSaveId())) {
                    return "[]";
                }
                int remainingRepairs = REPAIR_BATCH_SIZE;
                List<MemoryRecord> records = candidates(saveId, ownerCharacterId, relatedId);
                for (MemoryRecord record : records) {
                    if (remainingRepairs == 0) {
                        break;
                    }
                    if (readSummary(record) == null && canRetry(record.getSourceEventId(), ownerCharacterId)) {
                        remainingRepairs--;
                        rememberSafely(eventRecordMapper.selectById(record.getSourceEventId()), ownerCharacterId);
                    }
                }
                backfillMissing(saveId, ownerCharacterId, remainingRepairs);
                return buildContext(candidates(saveId, ownerCharacterId, relatedId), budget);
            });
            return result == null ? "[]" : result;
        } catch (RuntimeException exception) {
            log.warn("人物{}本次记忆读取失败，继续使用当前事实：{}", ownerCharacterId, exception.getClass().getSimpleName());
            return "[]";
        }
    }

    private boolean eligible(EventRecord event) {
        return event != null && !"AI_ACTION".equals(event.getEventCode()) && (Boolean.TRUE.equals(event.getLifeMilestone())
                || "END_DIALOGUE".equals(event.getEventCode()));
    }

    /** 只认结算回执中的双方或人生节点明确记录的相关人物，绝不扩大知情范围。 */
    private List<Character> participants(EventRecord event) {
        Set<String> ids = new LinkedHashSet<>();
        if ("END_DIALOGUE".equals(event.getEventCode())) {
            JSONObject dialogue = JSONUtil.parseObj(event.getSettlementResultJson()).getJSONObject("dialogue");
            if (dialogue == null || !Boolean.TRUE.equals(dialogue.getBool("ended"))) {
                return List.of();
            }
            ids.add(dialogue.getStr("actorId"));
            ids.add(dialogue.getStr("counterpartId"));
        } else {
            JSONUtil.parseArray(event.getRelatedCharacterIdJson()).forEach(value -> ids.add(String.valueOf(value)));
        }
        List<Character> result = new ArrayList<>();
        for (String id : ids) {
            if (uuid32(id)) {
                Character character = characterMapper.selectById(id);
                if (character != null && Objects.equals(event.getSaveId(), character.getSaveId())) {
                    result.add(character);
                }
            }
        }
        return result;
    }

    private void rememberSafely(EventRecord event, String ownerId) {
        if (!eligible(event) || !uuid32(event.getId()) || !uuid32(event.getSaveId()) || !uuid32(ownerId)) {
            return;
        }
        String key = event.getId() + ":" + ownerId;
        synchronized (memoryLocks[Math.floorMod(key.hashCode(), memoryLocks.length)]) {
            if (!canRetry(event.getId(), ownerId)) {
                return;
            }
            try {
                List<Character> involved = participants(event);
                Character owner = involved.stream().filter(character -> ownerId.equals(character.getId())).findFirst().orElse(null);
                if (owner == null) {
                    return;
                }
                MemoryRecord record = lambdaQuery().eq(MemoryRecord::getSaveId, event.getSaveId())
                        .eq(MemoryRecord::getOwnerCharacterId, ownerId).eq(MemoryRecord::getSourceEventId, event.getId()).one();
                if (record != null && readSummary(record) != null) {
                    return;
                }
                JSONObject confirmedEvent = confirmedEvent(event, involved);
                if (record == null) {
                    String id = UUID.nameUUIDFromBytes((event.getSaveId() + ":" + ownerId + ":" + event.getId())
                            .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                    record = new MemoryRecord().setId(id).setSaveId(event.getSaveId()).setOwnerCharacterId(ownerId)
                            .setSourceEventId(event.getId()).setOccurredTurnNumber(event.getOccurredTurnNumber())
                            .setRelatedCharacterIdJson(JSONUtil.toJsonStr(involved.stream().map(Character::getId).toList()))
                            .setSceneCode(confirmedEvent.getStr("sceneCode"))
                            .setRelatedEquipmentCodeJson(relatedEquipmentCodes(confirmedEvent).toString());
                }
                // 文件先于索引提交。索引提交失败时，确定性ID允许下次复用已经落盘的正文。
                String summary = readSummary(record);
                if (summary == null) {
                    JSONObject input = new JSONObject().set("owner", person(owner)).set("confirmedEvent", confirmedEvent)
                            .set("maxSummaryCharacters", MAX_SUMMARY_CHARACTERS);
                    MemorySummaryOutput output = gameClient.chat("PROMPT_MEMORY_SUMMARY", input.toString(), MemorySummaryOutput.class);
                    if (output.summary() == null || output.summary().isBlank()) {
                        throw new IllegalStateException("记忆摘要为空");
                    }
                    writeSummary(record, truncate(output.summary().strip(), MAX_SUMMARY_CHARACTERS));
                }
                MemoryRecord index = record;
                try {
                    indexTransaction.executeWithoutResult(status -> {
                        if (baseMapper.selectById(index.getId()) == null && !save(index)) {
                            throw new IllegalStateException("记忆索引保存失败");
                        }
                    });
                } catch (DuplicateKeyException exception) {
                    // 同一事件和拥有者的唯一索引是跨请求/进程重复生成的最后保护。
                    MemoryRecord existing = lambdaQuery().eq(MemoryRecord::getOwnerCharacterId, ownerId)
                            .eq(MemoryRecord::getSourceEventId, event.getId()).one();
                    if (existing == null || readSummary(existing) == null) {
                        throw exception;
                    }
                }
                retryAfter.remove(key);
            } catch (RuntimeException | IOException exception) {
                retryAfter.put(key, Instant.now().plusSeconds(RETRY_DELAY_SECONDS));
                log.warn("事件{}的人物{}记忆待补齐：{}", event.getId(), ownerId, exception.getClass().getSimpleName());
            }
        }
    }

    private JSONObject confirmedEvent(EventRecord event, List<Character> involved) {
        JSONObject result = new JSONObject().set("id", event.getId()).set("eventCode", event.getEventCode())
                .set("occurredTurnNumber", event.getOccurredTurnNumber())
                .set("participants", involved.stream().map(this::person).toList());
        JSONObject settlement = JSONUtil.parseObj(event.getSettlementResultJson());
        if ("END_DIALOGUE".equals(event.getEventCode())) {
            JSONObject dialogue = settlement.getJSONObject("dialogue");
            result.set("sceneCode", boundedCode(dialogue.getStr("sceneCode")))
                    .set("actorId", dialogue.getStr("actorId")).set("counterpartId", dialogue.getStr("counterpartId"));
            JSONArray history = JSONUtil.parseArray(dialogue.getStr("messagesJson"));
            JSONArray statements = new JSONArray();
            JSONArray trades = new JSONArray();
            // 对话正常最多5轮。兼容旧数据时保留最后10条；言论与真实执行交易分开。
            for (int index = Math.max(0, history.size() - 10); index < history.size(); index++) {
                JSONObject message = history.getJSONObject(index);
                String speaker = message.getStr("speaker");
                if ("actor".equals(speaker) || "counterpart".equals(speaker)) {
                    statements.add(new JSONObject().set("speakerId", dialogue.getStr("actor".equals(speaker) ? "actorId" : "counterpartId"))
                            .set("text", truncate(message.getStr("text"), 1200)));
                }
                trades.addAll(confirmedTrades(message.getJSONArray("executedTrades")));
            }
            result.set("statements", statements).set("executedTrades", trades);
        } else {
            result.set("eventSummary", truncate(event.getEventSummary(), 2000));
            result.set("summaryIsNarrative", "FREE_ACTION_MILESTONE".equals(event.getEventCode()));
            result.set("confirmedResult", select(settlement, "bookCode", "currentProgress", "progressGain", "score", "diceRoll"));
            result.set("sceneCode", boundedCode(settlement.getStr("sceneCode")))
                    .set("executedTrades", confirmedTrades(settlement.getJSONArray("executedTrades")));
            JSONObject exam = settlement.getJSONObject("exam");
            if (exam != null) {
                result.set("exam", select(exam, "examType", "status", "finalScore", "passThreshold"));
            }
            // 不传人物数值快照、思维泡泡、请求参数或模型尚未执行的计划。
        }
        return result;
    }

    private JSONObject person(Character character) {
        return new JSONObject().set("id", character.getId()).set("name", character.getName());
    }

    private JSONObject select(JSONObject source, String... fields) {
        JSONObject result = new JSONObject();
        for (String field : fields) {
            Object value = source.get(field);
            if (value instanceof String string) {
                result.set(field, truncate(string, 200));
            } else if (value instanceof Number || value instanceof Boolean) {
                result.set(field, value);
            }
        }
        return result;
    }

    private JSONArray confirmedTrades(JSONArray executed) {
        JSONArray trades = new JSONArray();
        if (executed != null) {
            for (int index = 0; index < executed.size(); index++) {
                trades.add(select(executed.getJSONObject(index), "actorId", "equipmentCode", "equipmentName", "quantity", "cost"));
            }
        }
        return trades;
    }

    private JSONArray relatedEquipmentCodes(JSONObject event) {
        Set<String> codes = new LinkedHashSet<>();
        JSONObject facts = event.getJSONObject("confirmedResult");
        if (facts != null && boundedCode(facts.getStr("bookCode")) != null) {
            codes.add(facts.getStr("bookCode"));
        }
        JSONArray trades = event.getJSONArray("executedTrades");
        if (trades != null) {
            for (int index = 0; index < trades.size(); index++) {
                String code = boundedCode(trades.getJSONObject(index).getStr("equipmentCode"));
                if (code != null) {
                    codes.add(code);
                }
            }
        }
        return new JSONArray(codes);
    }

    private List<MemoryRecord> candidates(String saveId, String ownerId, String relatedId) {
        Map<String, MemoryRecord> records = new LinkedHashMap<>();
        if (relatedId != null) {
            lambdaQuery().eq(MemoryRecord::getSaveId, saveId).eq(MemoryRecord::getOwnerCharacterId, ownerId)
                    .apply("JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0}))", relatedId)
                    .orderByDesc(MemoryRecord::getOccurredTurnNumber).orderByDesc(MemoryRecord::getId)
                    .last("LIMIT " + CANDIDATE_LIMIT).list().forEach(record -> records.put(record.getId(), record));
        }
        lambdaQuery().eq(MemoryRecord::getSaveId, saveId).eq(MemoryRecord::getOwnerCharacterId, ownerId)
                .orderByDesc(MemoryRecord::getOccurredTurnNumber).orderByDesc(MemoryRecord::getId)
                .last("LIMIT " + CANDIDATE_LIMIT).list().forEach(record -> records.putIfAbsent(record.getId(), record));
        return new ArrayList<>(records.values());
    }

    /** 用游标轮流检查遗漏，失败的单条事件不会永远挡住后面的事件。 */
    private void backfillMissing(String saveId, String ownerId, int attempts) {
        if (attempts <= 0) {
            return;
        }
        String key = saveId + ":" + ownerId;
        RepairCursor cursor = repairCursors.get(key);
        LambdaQueryWrapper<EventRecord> query = Wrappers.<EventRecord>lambdaQuery().eq(EventRecord::getSaveId, saveId)
                .ne(EventRecord::getEventCode, "AI_ACTION")
                .and(condition -> condition.eq(EventRecord::getLifeMilestone, true).or().eq(EventRecord::getEventCode, "END_DIALOGUE"))
                .apply("(JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0})) OR (event_code = 'END_DIALOGUE' AND "
                        + "(JSON_UNQUOTE(JSON_EXTRACT(settlement_result_json, '$.dialogue.actorId')) = {0} OR "
                        + "JSON_UNQUOTE(JSON_EXTRACT(settlement_result_json, '$.dialogue.counterpartId')) = {0})))", ownerId)
                .apply("NOT EXISTS (SELECT 1 FROM memory_record m WHERE m.source_event_id = event_record.id "
                        + "AND m.owner_character_id = {0})", ownerId);
        if (cursor != null) {
            query.and(condition -> condition.lt(EventRecord::getOccurredTurnNumber, cursor.turn())
                    .or(sameTurn -> sameTurn.eq(EventRecord::getOccurredTurnNumber, cursor.turn()).lt(EventRecord::getId, cursor.id())));
        }
        List<EventRecord> missing = eventRecordMapper.selectList(query.orderByDesc(EventRecord::getOccurredTurnNumber)
                .orderByDesc(EventRecord::getId).last("LIMIT " + CANDIDATE_LIMIT));
        if (missing.isEmpty()) {
            repairCursors.remove(key);
            return;
        }
        for (EventRecord event : missing) {
            repairCursors.put(key, new RepairCursor(event.getOccurredTurnNumber(), event.getId()));
            if (canRetry(event.getId(), ownerId)) {
                rememberSafely(event, ownerId);
                if (--attempts == 0) {
                    break;
                }
            }
        }
    }

    private boolean canRetry(String eventId, String ownerId) {
        Instant next = retryAfter.get(eventId + ":" + ownerId);
        return next == null || !Instant.now().isBefore(next);
    }

    private String buildContext(List<MemoryRecord> records, int budget) {
        JSONArray context = new JSONArray();
        for (MemoryRecord record : records) {
            if (context.size() >= MAX_CONTEXT_MEMORIES) {
                break;
            }
            String summary = readSummary(record);
            if (summary == null) {
                continue;
            }
            JSONObject item = new JSONObject().set("memoryId", record.getId()).set("sourceEventId", record.getSourceEventId())
                    .set("turnNumber", record.getOccurredTurnNumber()).set("summary", summary);
            context.add(item);
            if (codePoints(context.toString()) <= budget) {
                continue;
            }
            // 只缩短字符串字段，再重新序列化；绝不截断JSON或拆开增补平面的汉字。
            int low = 0;
            int high = codePoints(summary);
            while (low < high) {
                int middle = (low + high + 1) / 2;
                item.set("summary", truncate(summary, middle));
                if (codePoints(context.toString()) <= budget) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            item.set("summary", truncate(summary, low));
            if (low == 0 || codePoints(context.toString()) > budget) {
                context.remove(context.size() - 1);
            }
            break;
        }
        return context.toString();
    }

    private String readSummary(MemoryRecord record) {
        try {
            Path file = memoryFile(record);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768) {
                return null;
            }
            JSONObject json = JSONUtil.parseObj(Files.readString(file, StandardCharsets.UTF_8));
            if (!Objects.equals(json.getInt("version"), 1) || !Objects.equals(json.getStr("id"), record.getId())
                    || !Objects.equals(json.getStr("saveId"), record.getSaveId())
                    || !Objects.equals(json.getStr("ownerCharacterId"), record.getOwnerCharacterId())
                    || !Objects.equals(json.getStr("sourceEventId"), record.getSourceEventId())) {
                return null;
            }
            String summary = json.getStr("summary");
            return summary == null || summary.isBlank() || codePoints(summary) > MAX_SUMMARY_CHARACTERS ? null : summary;
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private void writeSummary(MemoryRecord record, String summary) throws IOException {
        Path file = memoryFile(record);
        Files.createDirectories(file.getParent());
        // 创建目录后再次解析真实路径，拒绝符号链接/目录联接把正文导向memory目录之外。
        if (!file.getParent().toRealPath().startsWith(MEMORY_ROOT.toRealPath())) {
            throw new IOException("记忆目录超出memory根目录");
        }
        JSONObject document = new JSONObject().set("version", 1).set("id", record.getId()).set("saveId", record.getSaveId())
                .set("ownerCharacterId", record.getOwnerCharacterId()).set("sourceEventId", record.getSourceEventId())
                .set("occurredTurnNumber", record.getOccurredTurnNumber()).set("summary", summary);
        Path pending = file.resolveSibling(file.getFileName() + ".pending");
        if (Files.isSymbolicLink(pending) || Files.isSymbolicLink(file)) {
            throw new IOException("记忆文件不允许符号链接");
        }
        Files.writeString(pending, document.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        // 不支持原子移动时保留pending供下次重写，并把本次标为失败，不发布半份JSON。
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path memoryFile(MemoryRecord record) throws IOException {
        if (!uuid32(record.getSaveId()) || !uuid32(record.getId()) || !uuid32(record.getOwnerCharacterId())
                || !uuid32(record.getSourceEventId())) {
            throw new IOException("非法记忆标识");
        }
        Path file = MEMORY_ROOT.resolve(record.getSaveId()).resolve(record.getId() + ".json").normalize();
        if (!file.startsWith(MEMORY_ROOT)) {
            throw new IOException("记忆文件超出memory根目录");
        }
        if (Files.exists(file.getParent()) && !file.getParent().toRealPath().startsWith(MEMORY_ROOT.toRealPath())) {
            throw new IOException("记忆目录超出memory根目录");
        }
        return file;
    }

    private static boolean uuid32(String value) {
        return value != null && value.matches("[0-9a-fA-F]{32}");
    }

    private static String boundedCode(String value) {
        return value == null || value.isBlank() || codePoints(value) > 64 ? null : value;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return codePoints(value) <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    public record MemorySummaryOutput(String summary) {
    }

    private record RepairCursor(long turn, String id) {
    }
}
