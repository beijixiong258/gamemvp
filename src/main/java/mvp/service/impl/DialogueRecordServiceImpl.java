package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.ai.FreeActionResolver;
import mvp.engine.CharacterEngine;
import mvp.engine.GameRuleConstant;
import mvp.entity.Character;
import mvp.entity.DialogueRecord;
import mvp.entity.GameSave;
import mvp.mapper.DialogueRecordMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.DialogueRecordService;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EventRecordService;
import mvp.service.GameSaveService;
import mvp.service.MemoryRecordService;
import mvp.utils.ClasspathJsonLoader;
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
    private final ClasspathJsonLoader jsonLoader;
    private final FreeActionResolver resolver;
    private final CharacterEngine characterEngine;
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
        JSONObject scene = JSONUtil.parseObj(context.contextSummary()).getJSONObject("scene");
        if (!scene.getJSONArray("availableActionCode").contains("NPC_DIALOGUE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景不能发起对话");
        }
        Character counterpart = characterService.getById(command.counterpartId());
        if (counterpart == null || !Objects.equals(saveId, counterpart.getSaveId())
                || !Boolean.TRUE.equals(counterpart.getEnabled()) || Objects.equals(actorId, counterpart.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话对象不存在或不可用");
        }
        if (counterpart.getNpcCode() != null) {
            boolean present = jsonLoader.load("game/scene.json", JSONObject.class).getJSONArray("scene")
                    .toList(JSONObject.class).stream().anyMatch(candidate ->
                            Objects.equals(candidate.getStr("sceneCode"), command.sceneCode())
                                    && candidate.getJSONArray("availableNpcCode").contains(counterpart.getNpcCode()));
            if (!present) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话对象不在当前场景");
            }
        }
        DialogueRecord dialogue = new DialogueRecord().setSaveId(saveId).setActorId(context.actorId())
                .setCounterpartId(counterpart.getId()).setSceneCode(command.sceneCode())
                .setStartedTurnNumber(context.turnNumber()).setVersion(0).setEnded(false).setMessagesJson("[]");
        save(dialogue);
        JSONObject result = JSONUtil.parseObj(dialogue);
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload, "START_DIALOGUE",
                context.turnNumber(), result);
        return result;
    }

    @Override
    public JSONObject respond(String saveId, String dialogueId, DialogueCommand command) {
        if (command == null || command.requestId() == null || command.requestId().length() > 100
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
        DialogueRecord before = loadDialogue(saveId, dialogueId);
        requireOpenVersion(before, command);
        GameSaveService.ActionContext snapshot = gameSaveService.prepareAction(saveId, before.getActorId(), before.getSceneCode());
        Character counterpart = characterService.getById(before.getCounterpartId());
        if (counterpart == null || !Boolean.TRUE.equals(counterpart.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对话对象已不可用");
        }
        int dialogueRound = before.getVersion() + 1;
        String memories = memoryRecordService.recall(saveId, counterpart.getId(), before.getActorId(),
                GameRuleConstant.MEMORY_CONTEXT_MAX_CHARACTERS);
        String input = new JSONObject().set("facts", JSONUtil.parseObj(snapshot.contextSummary())).set("counterpart", counterpart)
                .set("history", JSONUtil.parseArray(before.getMessagesJson())).set("currentText", command.text())
                .set("manualEnd", command.endDialogue()).set("dialogueRound", dialogueRound)
                .set("maxDialogueRounds", GameRuleConstant.MAX_DIALOGUE_ROUNDS).set("memoryContext", JSONUtil.parseArray(memories)).toString();
        FreeActionResolver.DialogueResolution resolution = resolver.resolveDialogue(input);
        boolean end = command.endDialogue() || resolution.endDialogue();
        if (resolution.reply() == null || resolution.reply().isBlank()
                || (end && resolution.driverPatch() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI返回内容不完整，请检查网络或账户余额后重试");
        }
        if (dialogueRound >= GameRuleConstant.MAX_DIALOGUE_ROUNDS && !end) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI未按要求结束本场对话，请重试");
        }
        CharacterEngine.DriverResult settlement = end
                ? characterEngine.applyDriver(snapshot.character(), snapshot.scholar(), resolution.driverPatch()) : null;
        // 手动结束只总结历史；不得把历史中的购买意图再次执行。
        List<AcquisitionIntent> acquisitions = command.endDialogue() || resolution.acquisitions() == null ? List.of()
                : resolution.acquisitions();
        return new TransactionTemplate(transactionManager).execute(status -> {
            lockSave(saveId);
            JSONObject replay = eventRecordService.replay(saveId, command.requestId(), payload);
            if (replay != null) {
                return replay;
            }
            DialogueRecord current = lambdaQuery().eq(DialogueRecord::getId, dialogueId).last("FOR UPDATE").one();
            requireOpenVersion(current, command);
            JSONObject applied = gameSaveService.settleAiAction(snapshot, command.requestId() + "/settlement",
                    payload, settlement, acquisitions, false, resolution.reply(), false);
            JSONArray history = JSONUtil.parseArray(current.getMessagesJson());
            history.add(new JSONObject().set("speaker", "actor").set("text", command.text() == null ? "" : command.text())
                    .set("manualEnd", command.endDialogue()));
            history.add(new JSONObject().set("speaker", "counterpart").set("text", resolution.reply())
                    .set("executedTrades", applied.getJSONArray("trades")));
            current.setMessagesJson(history.toString()).setVersion(current.getVersion() + 1).setEnded(end);
            updateById(current);
            JSONObject result = new JSONObject().set("dialogue", current).set("reply", resolution.reply()).set("applied", applied);
            eventRecordService.recordOperation(saveId, current.getActorId(), command.requestId(), payload,
                    end ? "END_DIALOGUE" : "DIALOGUE_MESSAGE", snapshot.turnNumber(), result);
            return result;
        });
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
