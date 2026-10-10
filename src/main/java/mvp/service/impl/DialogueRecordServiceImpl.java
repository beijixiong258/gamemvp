package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.ai.FreeActionResolver;
import mvp.ai.FreeActionWorkflow;
import mvp.engine.CharacterEngine;
import mvp.engine.GameRuleConstant;
import mvp.entity.Character;
import mvp.entity.DialogueRecord;
import mvp.entity.GameSave;
import mvp.mapper.DialogueRecordMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.CharacterService.NpcIntent;
import mvp.service.DialogueRecordService;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EquipmentRecordService.SceneItemChange;
import mvp.service.EventRecordService;
import mvp.service.GameSaveService;
import mvp.service.MemoryRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DialogueRecordServiceImpl extends ServiceImpl<DialogueRecordMapper, DialogueRecord> implements DialogueRecordService {
    private final GameSaveService gameSaveService;
    private final GameSaveMapper gameSaveMapper;
    private final CharacterService characterService;
    private final EventRecordService eventRecordService;
    private final MemoryRecordService memoryRecordService;
    private final FreeActionWorkflow workflow;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional
    public JSONObject start(String saveId, String actorId, StartDialogueCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少对话参数");
        }
        lockSave(saveId);
        JSONObject payload = new JSONObject().set("operation", "START_DIALOGUE").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        GameSaveService.ActionContext context = gameSaveService.prepareAction(saveId, actorId, command.sceneCode());
        if (lambdaQuery().eq(DialogueRecord::getSaveId, saveId).eq(DialogueRecord::getEnded, false).exists()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先结束或离开当前对话");
        }
        JSONObject scene = JSONUtil.parseObj(context.contextSummary()).getJSONObject("scene");
        if (!scene.getJSONArray("availableActionCode").contains("NPC_DIALOGUE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景不能发起对话");
        }
        Character counterpart = characterService.getById(command.counterpartId());
        if (counterpart == null || !Objects.equals(saveId, counterpart.getSaveId())
                || !Boolean.TRUE.equals(counterpart.getEnabled()) || Objects.equals(actorId, counterpart.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话对象不存在或不可用");
        }
        if (!characterService.isPresent(counterpart, command.sceneCode(), context.turnNumber())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话对象不在当前场景或已经离开");
        }
        DialogueRecord dialogue = new DialogueRecord().setSaveId(saveId).setActorId(context.actorId())
                .setCounterpartId(counterpart.getId()).setSceneCode(command.sceneCode())
                .setStartedTurnNumber(context.turnNumber()).setVersion(0).setEnded(false).setMessagesJson("[]");
        save(dialogue);
        JSONObject result = JSONUtil.parseObj(dialogue);
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload, "START_DIALOGUE",
                context.turnNumber(), result, List.of(counterpart.getId()));
        return result;
    }

    @Override
    public JSONObject respond(String saveId, String dialogueId, DialogueCommand command) {
        if (command == null || command.requestId() == null || command.requestId().isBlank() || command.requestId().length() > 100
                || (!command.endDialogue() && (command.text() == null || command.text().isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写消息及不超过100字符的稳定请求编号");
        }
        if (command.text() != null && command.text().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话消息不能超过4000字符");
        }
        JSONObject payload = new JSONObject().set("operation", "DIALOGUE_MESSAGE").set("dialogueId", dialogueId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        if (command.endDialogue()) {
            return new TransactionTemplate(transactionManager).execute(status ->
                    closeDialogue(saveId, dialogueId, command, payload));
        }
        DialogueRecord before = loadDialogue(saveId, dialogueId);
        requireOpenVersion(before, command);
        GameSaveService.ActionContext observed = gameSaveService.prepareAction(saveId, before.getActorId(), before.getSceneCode());
        int paidCost = before.getStaminaCost();
        int minimumCost = Math.max(0, before.staminaCostAfter(command.text().length() + 1) - paidCost);
        int replyBudget = Math.min(1600, (paidCost + observed.character().stamina()) * GameRuleConstant.DIALOGUE_CHARACTERS_PER_STAMINA
                - before.getStaminaCharacters() - command.text().length());
        if (observed.character().stamina() <= 0 || observed.character().stamina() < minimumCost || replyBudget <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "你累了，先休息吧。当前体力不足以支付本轮交谈，请休息或缩短发言");
        }
        if (!Objects.equals(before.getStartedTurnNumber(), observed.turnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对话不能跨游戏回合继续，请离开本场对话");
        }
        Character counterpart = characterService.getById(before.getCounterpartId());
        if (counterpart == null || !Objects.equals(saveId, counterpart.getSaveId())
                || !characterService.isPresent(counterpart, before.getSceneCode(), observed.turnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对话对象已不可用");
        }
        // 私人对话只提供已经参与的对方身份，不能借结算让同场第三人获得谈话经历。
        JSONObject facts = JSONUtil.parseObj(observed.contextSummary()).set("npcs", List.of(counterpart));
        GameSaveService.ActionContext snapshot = new GameSaveService.ActionContext(observed.saveId(), observed.actorId(),
                observed.turnNumber(), observed.sceneCode(), observed.character(), observed.scholar(), facts.toString(),
                observed.stateSnapshot());
        int dialogueRound = before.getVersion() + 1;
        String memories = memoryRecordService.recall(saveId, counterpart.getId(), before.getActorId(),
                GameRuleConstant.MEMORY_CONTEXT_MAX_CHARACTERS);
        String submittedText = command.text();
        String input = new JSONObject().set("facts", facts).set("counterpart", counterpart)
                .set("history", JSONUtil.parseArray(before.getMessagesJson())).set("currentText", submittedText)
                .set("manualEnd", false).set("dialogueRound", dialogueRound).set("maxReplyCharacters", replyBudget)
                .set("maxDialogueRounds", GameRuleConstant.MAX_DIALOGUE_ROUNDS).set("memoryContext", JSONUtil.parseArray(memories)).toString();
        FreeActionWorkflow.DialogueResult resolved = workflow.executeDialogue(input, snapshot.character(), snapshot.scholar());
        FreeActionResolver.Resolution resolution = resolved.resolution();
        boolean end = resolution.endDialogue();
        if (resolution.narrative() == null || resolution.narrative().isBlank()
                || resolution.driverPatch() == null || resolved.settlement() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI返回内容不完整，本轮未结算，请重试");
        }
        if (dialogueRound >= GameRuleConstant.MAX_DIALOGUE_ROUNDS && !end) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI未按要求结束本场对话，请重试");
        }
        CharacterEngine.DriverResult settlement = resolved.settlement();
        List<AcquisitionIntent> acquisitions = resolution.acquisitions() == null ? List.of()
                : resolution.acquisitions();
        List<NpcIntent> npcChanges = resolution.npcChanges() == null ? List.of()
                : resolution.npcChanges();
        List<SceneItemChange> sceneItemChanges = resolution.sceneItemChanges() == null ? List.of()
                : resolution.sceneItemChanges();
        if (npcChanges.stream().anyMatch(intent -> intent == null || !counterpart.getId().equals(intent.characterId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "私人对话只能更新当前对方，不能创建或通知第三人");
        }
        if (acquisitions.stream().anyMatch(intent -> intent == null || counterpart.getNpcCode() == null
                || !counterpart.getNpcCode().equals(intent.supplierNpcCode()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "对话中的物品只能由当前对方按其固定目录提供");
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            lockSave(saveId);
            JSONObject replay = eventRecordService.replay(saveId, command.requestId(), payload);
            if (replay != null) {
                return replay;
            }
            DialogueRecord current = lambdaQuery().eq(DialogueRecord::getId, dialogueId).last("FOR UPDATE").one();
            requireOpenVersion(current, command);
            int roundCharacters = submittedText.length() + resolution.narrative().length();
            int roundCost = Math.max(0, current.staminaCostAfter(roundCharacters) - current.getStaminaCost());
            JSONObject applied = gameSaveService.settleAiAction(snapshot, command.requestId() + "/settlement",
                    payload, settlement, acquisitions, npcChanges, sceneItemChanges, false, resolution.narrative(), false, roundCost);
            JSONArray history = JSONUtil.parseArray(current.getMessagesJson());
            history.add(new JSONObject().set("speaker", "actor")
                    .set("speakerName", facts.getJSONObject("actor").getStr("name")).set("text", submittedText));
            history.add(new JSONObject().set("speaker", "counterpart")
                    .set("speakerName", counterpart.getName()).set("text", resolution.narrative())
                    .set("staminaCharacters", roundCharacters).set("staminaCost", roundCost)
                    .set("numericChanges", applied.getJSONObject("numericChanges"))
                    .set("executedTrades", applied.getJSONArray("trades"))
                    .set("resolvedNpcs", applied.getJSONArray("resolvedNpcs"))
                    .set("sceneItems", applied.getJSONArray("sceneItems")));
            current.setMessagesJson(history.toString()).setVersion(current.getVersion() + 1);
            JSONObject actor = applied.getJSONObject("detail").getJSONObject("character");
            if (end || actor.getInt("stamina") <= 0
                    || actor.getInt("characterJiankang") <= 0 || actor.getInt("sickTurnsRemaining", 0) > 0) {
                return finishDialogue(saveId, current, command.requestId(), payload, resolution.narrative(), applied);
            }
            updateById(current);
            applied.getJSONObject("detail").set("activeDialogue", current);
            JSONObject result = new JSONObject().set("dialogue", current).set("reply", resolution.narrative())
                    .set("staminaCost", roundCost).set("applied", applied);
            eventRecordService.recordOperation(saveId, current.getActorId(), command.requestId(), payload,
                    "DIALOGUE_MESSAGE",
                    applied.getJSONObject("detail").getJSONObject("save").getLong("totalTurnNumber"),
                    result, List.of(current.getCounterpartId()));
            return result;
        });
    }

    @Override
    @Transactional
    public JSONObject abandon(String saveId, String dialogueId, AbandonDialogueCommand command) {
        if (command == null || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 100 || command.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供稳定请求编号和对话版本");
        }
        JSONObject payload = new JSONObject().set("operation", "ABANDON_DIALOGUE")
                .set("dialogueId", dialogueId).set("command", command);
        return closeDialogue(saveId, dialogueId,
                new DialogueCommand(command.requestId(), null, command.expectedVersion(), true), payload);
    }

    private JSONObject closeDialogue(String saveId, String dialogueId, DialogueCommand command, JSONObject payload) {
        lockSave(saveId);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        DialogueRecord current = loadDialogue(saveId, dialogueId);
        requireOpenVersion(current, command);
        current.setVersion(current.getVersion() + 1);
        return finishDialogue(saveId, current, command.requestId(), payload, null, null);
    }

    /** 各轮已即时扣款和成长，结束只关闭记录，不另收体力或生成回复。调用方已锁存档并检查重放。 */
    private JSONObject finishDialogue(String saveId, DialogueRecord current, String requestId,
                                      JSONObject payload, String reply, JSONObject applied) {
        current.setEnded(true);
        updateById(current);
        JSONObject result = new JSONObject().set("dialogue", current)
                .set("staminaCost", applied == null ? 0 : applied.getInt("staminaCost", 0))
                .set("feedback", "对话已结束。本场已实时扣除" + current.getStaminaCost()
                        + "点体力，结束不再扣费；各轮数值变化与交易已保存。");
        if (reply != null) {
            result.set("reply", reply);
        }
        if (applied != null) {
            JSONObject detail = applied.getJSONObject("detail").set("activeDialogue", null);
            result.set("applied", applied).set("detail", detail);
        }
        eventRecordService.recordOperation(saveId, current.getActorId(), requestId, payload,
                JSONUtil.parseArray(current.getMessagesJson()).isEmpty() ? "ABANDON_DIALOGUE" : "END_DIALOGUE",
                gameSaveMapper.selectById(saveId).getTotalTurnNumber(), result, List.of(current.getCounterpartId()));
        return result;
    }

    @Override
    public DialogueRecord loadDialogue(String saveId, String dialogueId) {
        DialogueRecord dialogue = getById(dialogueId);
        if (dialogue == null || !Objects.equals(saveId, dialogue.getSaveId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该对话");
        }
        return dialogue;
    }

    private void requireOpenVersion(DialogueRecord dialogue, DialogueCommand command) {
        if (dialogue == null || Boolean.TRUE.equals(dialogue.getEnded())
                || !Objects.equals(dialogue.getVersion(), command.expectedVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对话已结束或版本变化，请读取最新对话");
        }
    }

    private void lockSave(String saveId) {
        if (gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery().eq(GameSave::getId, saveId).last("FOR UPDATE")) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
    }
}
