package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import mvp.ai.MemoryResolver;
import mvp.ai.MemoryResolver.MemoryResolution;
import mvp.engine.GameRuleConstant;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.GameSave;
import mvp.entity.MemoryRecord;
import mvp.mapper.CharacterMapper;
import mvp.mapper.EventRecordMapper;
import mvp.mapper.GameSaveMapper;
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
import java.util.Locale;
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
    private final MemoryResolver memoryResolver;
    private final EventRecordMapper eventRecordMapper;
    private final CharacterMapper characterMapper;
    private final GameSaveMapper gameSaveMapper;
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate indexTransaction;
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();
    private final Map<String, RepairCursor> repairCursors = new ConcurrentHashMap<>();
    private final Object[] memoryLocks = new Object[64];

    public MemoryRecordServiceImpl(MemoryResolver memoryResolver, EventRecordMapper eventRecordMapper,
                                   CharacterMapper characterMapper, GameSaveMapper gameSaveMapper,
                                   PlatformTransactionManager transactionManager) {
        this.memoryResolver = memoryResolver;
        this.eventRecordMapper = eventRecordMapper;
        this.characterMapper = characterMapper;
        this.gameSaveMapper = gameSaveMapper;
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
                GameSave save = gameSaveMapper.selectById(saveId);
                if (save == null) {
                    return "[]";
                }
                long currentTurn = save.getTotalTurnNumber();
                archiveExpired(saveId, ownerCharacterId, currentTurn);
                int remainingRepairs = REPAIR_BATCH_SIZE;
                List<MemoryRecord> records = candidates(saveId, ownerCharacterId, relatedId, currentTurn, null);
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
                return buildContext(candidates(saveId, ownerCharacterId, relatedId, currentTurn, null), budget);
            });
            return result == null ? "[]" : result;
        } catch (RuntimeException exception) {
            log.warn("人物{}本次记忆读取失败，继续使用当前事实：{}", ownerCharacterId, exception.getClass().getSimpleName());
            return "[]";
        }
    }

    private boolean eligible(EventRecord event) {
        return event != null && event.getEventSequence() != null && event.getEventSequence() > 0 && !"AI_ACTION".equals(event.getEventCode()) && (Boolean.TRUE.equals(event.getLifeMilestone())
                || "END_DIALOGUE".equals(event.getEventCode()) || "FREE_ACTION".equals(event.getEventCode()));
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
                GameSave save = gameSaveMapper.selectById(event.getSaveId());
                List<Character> involved = participants(event);
                Character owner = involved.stream().filter(character -> ownerId.equals(character.getId())).findFirst().orElse(null);
                if (save == null || owner == null) {
                    return;
                }
                long currentTurn = save.getTotalTurnNumber();
                archiveExpired(event.getSaveId(), ownerId, currentTurn);
                MemoryRecord record = lambdaQuery().eq(MemoryRecord::getSaveId, event.getSaveId())
                        .eq(MemoryRecord::getOwnerCharacterId, ownerId).eq(MemoryRecord::getSourceEventId, event.getId()).one();
                // L0和到期记忆有意保留索引；不把无正文当成生成失败。
                if (record != null && (Boolean.TRUE.equals(record.getArchived()) || readSummary(record) != null)) {
                    retryAfter.remove(key);
                    return;
                }
                boolean newRecord = record == null;
                JSONObject confirmedEvent = confirmedEvent(event, involved);
                if (newRecord) {
                    String id = UUID.nameUUIDFromBytes((event.getSaveId() + ":" + ownerId + ":" + event.getId())
                            .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                    record = new MemoryRecord().setId(id).setSaveId(event.getSaveId()).setOwnerCharacterId(ownerId)
                            .setSourceEventId(event.getId()).setSourceEventSequence(event.getEventSequence()).setOccurredTurnNumber(event.getOccurredTurnNumber())
                            .setRelatedCharacterIdJson(JSONUtil.toJsonStr(involved.stream().map(Character::getId).toList()))
                            .setSceneCode(confirmedEvent.getStr("sceneCode"))
                            .setRelatedEquipmentCodeJson(relatedEquipmentCodes(confirmedEvent).toString());
                }
                JSONObject document = readDocument(record);
                RetentionDecision decision;
                String summary;
                if (document != null) {
                    summary = document.getStr("summary");
                    decision = storedDecision(document, event, confirmedEvent);
                } else {
                    String relatedId = involved.stream().map(Character::getId).filter(id -> !ownerId.equals(id))
                            .findFirst().orElse(null);
                    JSONArray previous = JSONUtil.parseArray(buildContext(candidates(event.getSaveId(), ownerId,
                            relatedId, event.getOccurredTurnNumber(), event.getEventSequence()), MAX_CONTEXT_CHARACTERS));
                    Set<String> previousIds = new LinkedHashSet<>();
                    for (int i = 0; i < previous.size(); i++) {
                        previousIds.add(previous.getJSONObject(i).getStr("memoryId"));
                    }
                    MemoryResolution output = memoryResolver.resolve(person(owner), confirmedEvent, previous);
                    if (output == null || output.summary() == null || output.summary().isBlank()) {
                        throw new IllegalStateException("记忆摘要为空");
                    }
                    summary = truncate(output.summary().strip(), MAX_SUMMARY_CHARACTERS);
                    decision = retentionDecision(output, event, confirmedEvent, previousIds);
                }
                if (newRecord) {
                    Long expiry = "L1".equals(decision.level())
                            ? Math.addExact(event.getOccurredTurnNumber(), decision.retentionTurns()) : null;
                    record.setMemoryLevel(decision.level()).setMemoryKind(decision.kind()).setRetentionReason(decision.reason())
                            .setExpiresAtTurn(expiry).setLastReinforcedTurn(event.getOccurredTurnNumber()).setLastReinforcedSequence(event.getEventSequence())
                            .setArchived("L0".equals(decision.level()) || (expiry != null && expiry <= currentTurn));
                }
                // 正文先于索引提交；首次决策随正文保存，索引重试复用同一份决策。
                if (document == null && !Boolean.TRUE.equals(record.getArchived())) {
                    writeSummary(record, summary, decision);
                }
                MemoryRecord index = record;
                RetentionDecision retained = decision;
                try {
                    indexTransaction.executeWithoutResult(status -> {
                        // 模型调用期间其他请求可能推进回合；落索引时重新锁存档并校准期限。
                        GameSave latestSave = gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery()
                                .eq(GameSave::getId, index.getSaveId()).last("FOR UPDATE"));
                        if (latestSave == null) {
                            return;
                        }
                        long latestTurn = latestSave.getTotalTurnNumber();
                        if ("L1".equals(index.getMemoryLevel()) && index.getExpiresAtTurn() != null
                                && index.getExpiresAtTurn() <= latestTurn) {
                            index.setArchived(true);
                        }
                        if (baseMapper.selectById(index.getId()) != null) {
                            return;
                        }
                        if (!save(index)) {
                            throw new IllegalStateException("记忆索引保存失败");
                        }
                        reinforceMemories(index, retained, latestTurn);
                        retainRelatedNpcs(index, involved);
                    });
                } catch (DuplicateKeyException exception) {
                    MemoryRecord existing = lambdaQuery().eq(MemoryRecord::getOwnerCharacterId, ownerId)
                            .eq(MemoryRecord::getSourceEventId, event.getId()).one();
                    if (existing == null || (!Boolean.TRUE.equals(existing.getArchived()) && readSummary(existing) == null)) {
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

    private RetentionDecision retentionDecision(MemoryResolution output, EventRecord event,
                                                 JSONObject confirmedEvent, Set<String> previousIds) {
        String level = output.level() == null ? "L1" : output.level().strip().toUpperCase(Locale.ROOT);
        String kind = output.kind() == null ? "EXPERIENCE" : output.kind().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("L0", "L1", "L2").contains(level)) {
            level = "L1";
        }
        if (!Set.of("EXPERIENCE", "RELATIONSHIP", "COMMITMENT").contains(kind)) {
            kind = "EXPERIENCE";
        }
        int turns = output.retentionTurns() == null ? GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS : output.retentionTurns();
        if (!Set.of(GameRuleConstant.MEMORY_SHORT_RETENTION_TURNS, GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS,
                GameRuleConstant.MEMORY_LONG_RETENTION_TURNS).contains(turns)) {
            turns = GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS;
        }
        String reason = output.reason() == null || output.reason().isBlank() ? "按普通经历保留" : output.reason().strip();
        if (Boolean.TRUE.equals(event.getLifeMilestone()) || !"EXPERIENCE".equals(kind)) {
            if (!"L2".equals(level)) {
                reason = "重要节点、关系经历或明确约定需长期保留：" + reason;
            }
            level = "L2";
        } else if ("L0".equals(level) && !confirmedEvent.getJSONArray("executedTrades").isEmpty()) {
            level = "L1";
            turns = GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS;
            reason = "已执行交易至少按近期经历保留：" + reason;
        }
        List<String> reinforced = "L0".equals(level) || output.reinforcedMemoryIds() == null ? List.of()
                : output.reinforcedMemoryIds().stream().filter(MemoryRecordServiceImpl::uuid32)
                        .filter(previousIds::contains).distinct().limit(MAX_CONTEXT_MEMORIES).toList();
        return new RetentionDecision(level, kind, "L1".equals(level) ? turns : 0, truncate(reason, 300), reinforced);
    }

    private RetentionDecision storedDecision(JSONObject document, EventRecord event, JSONObject confirmedEvent) {
        JSONObject stored = document.getJSONObject("retentionDecision");
        if (stored == null) {
            return new RetentionDecision("L2", "EXPERIENCE", 0, "历史记忆沿用长期保留", List.of());
        }
        JSONArray ids = stored.getJSONArray("reinforcedMemoryIds");
        List<String> reinforced = ids == null ? List.of() : ids.toList(String.class);
        return retentionDecision(new MemoryResolution(document.getStr("summary"), stored.getStr("level"),
                stored.getStr("kind"), stored.getInt("retentionTurns"), stored.getStr("reason"), reinforced),
                event, confirmedEvent, new LinkedHashSet<>(reinforced));
    }

    private void archiveExpired(String saveId, String ownerId, long currentTurn) {
        indexTransaction.executeWithoutResult(status -> lambdaUpdate().eq(MemoryRecord::getSaveId, saveId)
                .eq(MemoryRecord::getOwnerCharacterId, ownerId).eq(MemoryRecord::getMemoryLevel, "L1")
                .eq(MemoryRecord::getArchived, false).le(MemoryRecord::getExpiresAtTurn, currentTurn)
                .set(MemoryRecord::getArchived, true).update());
    }

    /** 与新索引同事务执行；同一事件重放不续期，读取不强化，已归档记录不复活。 */
    private void reinforceMemories(MemoryRecord source, RetentionDecision decision, long currentTurn) {
        if (Boolean.TRUE.equals(source.getArchived())) {
            return;
        }
        for (String id : decision.reinforcedMemoryIds()) {
            var update = lambdaUpdate().eq(MemoryRecord::getId, id).eq(MemoryRecord::getSaveId, source.getSaveId())
                    .eq(MemoryRecord::getOwnerCharacterId, source.getOwnerCharacterId())
                    .eq(MemoryRecord::getMemoryLevel, "L1").eq(MemoryRecord::getArchived, false)
                    .lt(MemoryRecord::getSourceEventSequence, source.getSourceEventSequence())
                    .gt(MemoryRecord::getExpiresAtTurn, currentTurn)
                    .setSql("last_reinforced_turn = GREATEST(last_reinforced_turn, {0})", source.getOccurredTurnNumber())
                    .setSql("last_reinforced_sequence = GREATEST(last_reinforced_sequence, {0})", source.getSourceEventSequence());
            if ("L2".equals(decision.level())) {
                update.set(MemoryRecord::getMemoryLevel, "L2").set(MemoryRecord::getExpiresAtTurn, null)
                        .set(MemoryRecord::getRetentionReason, truncate("新经历强化为长期记忆：" + decision.reason(), 300));
            } else {
                update.setSql("expires_at_turn = GREATEST(expires_at_turn, {0})",
                        Math.addExact(source.getOccurredTurnNumber(), decision.retentionTurns()));
            }
            update.update();
        }
    }

    /** 首次确认长期关系时保留原身份；补齐可恢复已到期人物，但不复活已归档记忆。 */
    private void retainRelatedNpcs(MemoryRecord memory, List<Character> involved) {
        if (Boolean.TRUE.equals(memory.getArchived()) || !"L2".equals(memory.getMemoryLevel())
                || !Set.of("RELATIONSHIP", "COMMITMENT").contains(memory.getMemoryKind())) {
            return;
        }
        List<String> ids = involved.stream().filter(character -> character.getType() == 0 && character.getNpcCode() == null)
                .map(Character::getId).toList();
        if (!ids.isEmpty()) {
            characterMapper.update(null, Wrappers.<Character>update().eq("save_id", memory.getSaveId()).in("id", ids)
                    .isNull("npc_code").eq("type", 0).ne("retention_level", "L2")
                    .set("retention_level", "L2").set("expires_at_turn", null).set("archived", false)
                    .set("retention_reason", truncate("已确认关系或约定：" + memory.getRetentionReason(), 300)));
        }
    }

    private JSONObject confirmedEvent(EventRecord event, List<Character> involved) {
        JSONObject result = new JSONObject().set("id", event.getId()).set("eventCode", event.getEventCode())
                .set("occurredTurnNumber", event.getOccurredTurnNumber()).set("sourceEventSequence", event.getEventSequence())
                .set("participants", involved.stream().map(this::person).toList());
        Set<String> participantIds = new LinkedHashSet<>(involved.stream().map(Character::getId).toList());
        JSONObject settlement = JSONUtil.parseObj(event.getSettlementResultJson());
        if ("END_DIALOGUE".equals(event.getEventCode())) {
            JSONObject dialogue = settlement.getJSONObject("dialogue");
            result.set("sceneCode", boundedCode(dialogue.getStr("sceneCode")))
                    .set("actorId", dialogue.getStr("actorId")).set("counterpartId", dialogue.getStr("counterpartId"));
            JSONArray history = JSONUtil.parseArray(dialogue.getStr("messagesJson"));
            JSONArray statements = new JSONArray();
            JSONArray trades = new JSONArray();
            JSONArray npcs = new JSONArray();
            JSONArray sceneItems = new JSONArray();
            // 对话正常最多5轮。兼容旧数据时保留最后10条；言论与真实执行交易分开。
            for (int index = Math.max(0, history.size() - 10); index < history.size(); index++) {
                JSONObject message = history.getJSONObject(index);
                String speaker = message.getStr("speaker");
                if (("actor".equals(speaker) && !Boolean.TRUE.equals(message.getBool("manualEnd"))) || "counterpart".equals(speaker)) {
                    statements.add(new JSONObject().set("speakerId", dialogue.getStr("actor".equals(speaker) ? "actorId" : "counterpartId"))
                            .set("text", truncate(message.getStr("text"), 1200)));
                }
                trades.addAll(confirmedTrades(message.getJSONArray("executedTrades")));
                npcs.addAll(confirmedNpcs(message.getJSONArray("resolvedNpcs"), participantIds));
                sceneItems.addAll(confirmedSceneItems(message.getJSONArray("sceneItems")));
            }
            result.set("statements", statements).set("executedTrades", trades)
                    .set("resolvedNpcs", npcs).set("sceneItems", sceneItems);
        } else {
            boolean freeAction = "FREE_ACTION".equals(event.getEventCode());
            result.set("resolvedNpcs", confirmedNpcs(settlement.getJSONArray("resolvedNpcs"), participantIds))
                    .set("sceneItems", confirmedSceneItems(settlement.getJSONArray("sceneItems")));
            result.set("eventSummary", truncate(freeAction ? settlement.getStr("summary") : event.getEventSummary(), 2000));
            result.set("summaryIsNarrative", freeAction || "FREE_ACTION_MILESTONE".equals(event.getEventCode()));
            result.set("confirmedResult", select(settlement, "bookCode", "currentProgress", "progressGain", "score", "diceRoll"));
            JSONObject command = freeAction ? JSONUtil.parseObj(event.getRequestPayloadJson()).getJSONObject("command") : null;
            result.set("sceneCode", boundedCode(command == null ? settlement.getStr("sceneCode") : command.getStr("sceneCode")))
                    .set("executedTrades", confirmedTrades(settlement.getJSONArray(freeAction ? "trades" : "executedTrades")));
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
                trades.add(select(executed.getJSONObject(index), "actorId", "supplierId", "supplierNpcCode",
                        "equipmentCode", "equipmentName", "quantity", "cost"));
            }
        }
        return trades;
    }

    private JSONArray confirmedNpcs(JSONArray resolved, Set<String> participantIds) {
        JSONArray result = new JSONArray();
        if (resolved != null) {
            for (int index = 0; index < resolved.size(); index++) {
                JSONObject npc = resolved.getJSONObject(index);
                if (npc != null && participantIds.contains(npc.getStr("id"))) {
                    result.add(select(npc, "id", "name", "currentSceneCode", "retentionLevel"));
                }
            }
        }
        return result;
    }

    /** 场景物件只是已确认在场，持有与交易必须另看executedTrades和当前背包。 */
    private JSONArray confirmedSceneItems(JSONArray resolved) {
        JSONArray result = new JSONArray();
        if (resolved != null) {
            for (int index = 0; index < resolved.size(); index++) {
                JSONObject item = resolved.getJSONObject(index);
                if (item != null && uuid32(item.getStr("id"))) {
                    result.add(select(item, "id", "itemCode", "itemName", "description", "retentionLevel", "expiresAtTurn", "quantity"));
                }
            }
        }
        return result;
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
        JSONArray sceneItems = event.getJSONArray("sceneItems");
        if (sceneItems != null) {
            for (int index = 0; index < sceneItems.size(); index++) {
                String code = boundedCode(sceneItems.getJSONObject(index).getStr("itemCode"));
                if (code != null) {
                    codes.add(code);
                }
            }
        }
        return new JSONArray(codes);
    }

    private LambdaQueryWrapper<MemoryRecord> activeMemories(String saveId, String ownerId, long currentTurn, Long beforeSequence) {
        return Wrappers.<MemoryRecord>lambdaQuery().eq(MemoryRecord::getSaveId, saveId)
                .eq(MemoryRecord::getOwnerCharacterId, ownerId).eq(MemoryRecord::getArchived, false)
                .ne(MemoryRecord::getMemoryLevel, "L0").le(MemoryRecord::getOccurredTurnNumber, currentTurn)
                .lt(beforeSequence != null, MemoryRecord::getSourceEventSequence, beforeSequence)
                .and(expiry -> expiry.isNull(MemoryRecord::getExpiresAtTurn).or().gt(MemoryRecord::getExpiresAtTurn, currentTurn));
    }

    private void addCandidates(Map<String, MemoryRecord> records, LambdaQueryWrapper<MemoryRecord> query, int limit) {
        list(query.orderByDesc(MemoryRecord::getLastReinforcedSequence).orderByDesc(MemoryRecord::getSourceEventSequence)
                .orderByDesc(MemoryRecord::getId).last("LIMIT " + limit)).forEach(record -> records.putIfAbsent(record.getId(), record));
    }

    private List<MemoryRecord> candidates(String saveId, String ownerId, String relatedId, long currentTurn, Long beforeSequence) {
        Map<String, MemoryRecord> records = new LinkedHashMap<>();
        // 为关系和约定保留少量位置，其余仍带入有关近事；长期记忆不因闲聊变多而完全挤出。
        if (relatedId != null) {
            addCandidates(records, activeMemories(saveId, ownerId, currentTurn, beforeSequence)
                    .apply("JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0}))", relatedId)
                    .in(MemoryRecord::getMemoryKind, List.of("RELATIONSHIP", "COMMITMENT")), 3);
        }
        addCandidates(records, activeMemories(saveId, ownerId, currentTurn, beforeSequence).eq(MemoryRecord::getMemoryLevel, "L2"), 2);
        if (relatedId != null) {
            addCandidates(records, activeMemories(saveId, ownerId, currentTurn, beforeSequence)
                    .apply("JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0}))", relatedId), CANDIDATE_LIMIT);
        }
        addCandidates(records, activeMemories(saveId, ownerId, currentTurn, beforeSequence), CANDIDATE_LIMIT);
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
                .and(condition -> condition.eq(EventRecord::getLifeMilestone, true).or()
                        .in(EventRecord::getEventCode, List.of("END_DIALOGUE", "FREE_ACTION")))
                .apply("(JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0})) OR (event_code = 'END_DIALOGUE' AND "
                        + "(JSON_UNQUOTE(JSON_EXTRACT(settlement_result_json, '$.dialogue.actorId')) = {0} OR "
                        + "JSON_UNQUOTE(JSON_EXTRACT(settlement_result_json, '$.dialogue.counterpartId')) = {0})))", ownerId)
                .apply("NOT EXISTS (SELECT 1 FROM memory_record m WHERE m.source_event_id = event_record.id "
                        + "AND m.owner_character_id = {0})", ownerId);
        if (cursor != null) {
            query.lt(EventRecord::getEventSequence, cursor.sequence());
        }
        List<EventRecord> missing = eventRecordMapper.selectList(query.orderByDesc(EventRecord::getEventSequence).last("LIMIT " + CANDIDATE_LIMIT));
        if (missing.isEmpty()) {
            repairCursors.remove(key);
            return;
        }
        for (EventRecord event : missing) {
            repairCursors.put(key, new RepairCursor(event.getEventSequence()));
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
                    .set("turnNumber", record.getOccurredTurnNumber()).set("sourceEventSequence", record.getSourceEventSequence()).set("level", record.getMemoryLevel())
                    .set("kind", record.getMemoryKind()).set("summary", summary);
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
        JSONObject document = readDocument(record);
        return document == null ? null : document.getStr("summary");
    }

    private JSONObject readDocument(MemoryRecord record) {
        try {
            Path file = memoryFile(record);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768) {
                return null;
            }
            JSONObject json = JSONUtil.parseObj(Files.readString(file, StandardCharsets.UTF_8));
            if (!Objects.equals(json.getInt("version"), 1) || !Objects.equals(json.getStr("id"), record.getId())
                    || !Objects.equals(json.getStr("saveId"), record.getSaveId())
                    || !Objects.equals(json.getStr("ownerCharacterId"), record.getOwnerCharacterId())
                    || !Objects.equals(json.getStr("sourceEventId"), record.getSourceEventId())
                    || (json.getLong("sourceEventSequence") != null
                        && !Objects.equals(json.getLong("sourceEventSequence"), record.getSourceEventSequence()))) {
                return null;
            }
            String summary = json.getStr("summary");
            return summary == null || summary.isBlank() || codePoints(summary) > MAX_SUMMARY_CHARACTERS ? null : json;
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private void writeSummary(MemoryRecord record, String summary, RetentionDecision decision) throws IOException {
        Path file = memoryFile(record);
        Files.createDirectories(file.getParent());
        // 创建目录后再次解析真实路径，拒绝符号链接/目录联接把正文导向memory目录之外。
        if (!file.getParent().toRealPath().startsWith(MEMORY_ROOT.toRealPath())) {
            throw new IOException("记忆目录超出memory根目录");
        }
        JSONObject document = new JSONObject().set("version", 1).set("id", record.getId()).set("saveId", record.getSaveId())
                .set("ownerCharacterId", record.getOwnerCharacterId()).set("sourceEventId", record.getSourceEventId())
                .set("occurredTurnNumber", record.getOccurredTurnNumber()).set("sourceEventSequence", record.getSourceEventSequence()).set("summary", summary)
                .set("retentionDecision", new JSONObject().set("level", decision.level()).set("kind", decision.kind())
                        .set("retentionTurns", decision.retentionTurns()).set("reason", decision.reason())
                        .set("reinforcedMemoryIds", decision.reinforcedMemoryIds()));
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

    private record RetentionDecision(String level, String kind, int retentionTurns,
                                     String reason, List<String> reinforcedMemoryIds) {
    }

    private record RepairCursor(long sequence) {
    }
}
