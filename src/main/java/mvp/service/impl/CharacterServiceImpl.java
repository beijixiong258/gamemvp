package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.engine.CharacterEngine;
import mvp.engine.GameRuleConstant;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.DialogueRecord;
import mvp.entity.GameSave;
import mvp.mapper.CharacterMapper;
import mvp.mapper.DialogueRecordMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.CareerProfileShushengService;
import mvp.service.CharacterService;
import mvp.utils.ClasspathJsonLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CharacterServiceImpl extends ServiceImpl<CharacterMapper, Character> implements CharacterService {
    private final CharacterEngine characterEngine;
    private final CareerProfileShushengService scholarService;
    private final DialogueRecordMapper dialogueRecordMapper;
    private final GameSaveMapper gameSaveMapper;
    private final ClasspathJsonLoader jsonLoader;

    @Override
    public List<Character> listVisibleNpcs(String saveId, long turnNumber) {
        Set<String> protectedIds = activeDialogueIds(saveId);
        return lambdaQuery().eq(Character::getSaveId, saveId).eq(Character::getType, 0)
                .eq(Character::getEnabled, true)
                .and(query -> query.eq(Character::getArchived, false).or().isNull(Character::getArchived)
                        .or(!protectedIds.isEmpty()).in(!protectedIds.isEmpty(), Character::getId, protectedIds))
                .orderByAsc(Character::getId).list().stream()
                .filter(character -> active(character, turnNumber, protectedIds)).toList();
    }

    @Override
    public List<Character> listPresentNpcs(String saveId, String sceneCode, long turnNumber) {
        Set<String> codes = sceneNpcCodes(sceneCode);
        return listVisibleNpcs(saveId, turnNumber).stream()
                .filter(character -> inScene(character, sceneCode, codes)).toList();
    }

    @Override
    public boolean isPresent(Character character, String sceneCode, long turnNumber) {
        return character != null && character.getType() == 0
                && active(character, turnNumber, activeDialogueIds(character.getSaveId()))
                && inScene(character, sceneCode, sceneNpcCodes(sceneCode));
    }

    @Override
    @Transactional
    public void archiveExpired(String saveId, long turnNumber) {
        GameSave save = lockSave(saveId);
        if (turnNumber < 0 || turnNumber > save.getTotalTurnNumber()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人物归档回合超出当前存档时间");
        }
        Set<String> protectedIds = activeDialogueIds(saveId);
        lambdaUpdate().eq(Character::getSaveId, saveId).eq(Character::getType, 0)
                .isNull(Character::getNpcCode).in(Character::getRetentionLevel, "L0", "L1")
                .eq(Character::getArchived, false).le(Character::getExpiresAtTurn, turnNumber)
                .notIn(!protectedIds.isEmpty(), Character::getId, protectedIds)
                .set(Character::getArchived, true).update();
    }

    @Override
    @Transactional
    public NpcChanges applyNpcIntents(GameSave settledSave, Character actor, String sceneCode, String requestId,
                                     long observedTurnNumber, Set<String> observedNpcIds, List<NpcIntent> intents) {
        List<NpcIntent> changes = intents == null ? List.of() : intents;
        if (changes.isEmpty()) {
            return new NpcChanges(List.of(), List.of());
        }
        if (settledSave == null || actor == null || !Objects.equals(settledSave.getId(), actor.getSaveId())
                || settledSave.getTotalTurnNumber() == null || observedTurnNumber < 0
                || observedTurnNumber > settledSave.getTotalTurnNumber()
                || requestId == null || requestId.isBlank() || requestId.length() > 120) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人物结算缺少有效的存档和观察快照");
        }
        if (changes.size() > 4 || changes.stream().anyMatch(Objects::isNull)
                || changes.stream().filter(intent -> blank(intent.characterId())).count() > 2) {
            throw invalid("每次最多确认4名人物，其中新人物最多2名");
        }
        GameSave currentSave = lockSave(settledSave.getId());
        if (!Objects.equals(currentSave.getTotalTurnNumber(), settledSave.getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人物结算时存档回合已变化");
        }
        Set<String> sceneCodes = sceneNpcCodes(sceneCode);
        Set<String> observed = observedNpcIds == null ? Set.of() : observedNpcIds;
        Set<String> protectedIds = activeDialogueIds(settledSave.getId());
        Set<String> handledIds = new HashSet<>();
        Set<String> newNames = new HashSet<>();
        List<Character> results = new ArrayList<>();
        Set<String> participants = new LinkedHashSet<>();
        for (int index = 0; index < changes.size(); index++) {
            NpcIntent intent = changes.get(index);
            Character npc;
            if (blank(intent.characterId())) {
                String id = UUID.nameUUIDFromBytes((settledSave.getId() + ":npc:" + requestId + ":" + index)
                        .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                npc = getById(id);
                if (npc == null) {
                    String name = requiredText(intent.name(), 64, "新人物姓名");
                    if (!newNames.add(name)) {
                        throw invalid("同一次经历中的新人物不能重复使用同一姓名");
                    }
                    npc = createNpc(settledSave, actor, sceneCode, id, name, intent);
                } else if (!Objects.equals(settledSave.getId(), npc.getSaveId()) || fixed(npc)) {
                    throw invalid("新人物身份与已有身份冲突");
                }
            } else {
                String id = intent.characterId().strip();
                if (!observed.contains(id) || !handledIds.add(id)) {
                    throw invalid("人物引用不在本次观察快照中或重复出现");
                }
                npc = getById(id);
                if (npc == null || npc.getType() != 0 || !Objects.equals(settledSave.getId(), npc.getSaveId())
                        || !activeAtObservation(npc, observedTurnNumber, protectedIds)
                        || !inScene(npc, sceneCode, sceneCodes)) {
                    throw invalid("被引用的人物未在本次场景中有效出现");
                }
                // 已有姓名、年龄、性格和权限始终沿用同一身份，只有真实交互能更新保留策略。
                if (!fixed(npc) && intent.participated()) {
                    retain(npc, intent, settledSave.getTotalTurnNumber());
                    String description = optionalText(intent.description(), 1000, "人物状态");
                    if (description != null) {
                        npc.setCurrentState(description);
                    }
                    lambdaUpdate().eq(Character::getId, npc.getId()).eq(Character::getSaveId, settledSave.getId())
                            .set(Character::getRetentionLevel, npc.getRetentionLevel())
                            .set(Character::getExpiresAtTurn, npc.getExpiresAtTurn())
                            .set(Character::getArchived, false)
                            .set(Character::getRetentionReason, npc.getRetentionReason())
                            .set(description != null, Character::getCurrentState, description).update();
                }
            }
            results.add(npc);
            if (intent.participated()) {
                participants.add(npc.getId());
            }
        }
        return new NpcChanges(List.copyOf(results), List.copyOf(participants));
    }

    private Character createNpc(GameSave save, Character actor, String sceneCode, String id, String name, NpcIntent intent) {
        if (intent.age() == null || intent.age() < GameRuleConstant.SCHOOL_START_AGE || intent.age() > 80) {
            throw invalid("新人物年龄应为6至80岁");
        }
        if (actor.getCurrentRegionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "行动人物缺少当前地区");
        }
        if (lambdaQuery().eq(Character::getSaveId, save.getId()).isNotNull(Character::getNpcCode)
                .eq(Character::getName, name).count() > 0) {
            throw invalid("不能把本存档的固定人物当作新人物创建，请沿用已观察到的原ID");
        }
        CharacterEngine.StartLifeResult initial = characterEngine.startLife(actor.getCurrentRegionId());
        CharacterEngine.CharacterState state = initial.character();
        Character npc = new Character().setId(id).setSaveId(save.getId()).setType(0).setNpcCode(null)
                .setName(name).setWallet(0).setSickTurnsRemaining(0).setTitlesJson("[]")
                .setBirthRegionId(actor.getCurrentRegionId()).setCurrentRegionId(actor.getCurrentRegionId())
                .setBirthday((save.getCurrentYear() - intent.age()) + "-01-01")
                .setPersonalitySummary(requiredText(intent.personality(), 1000, "新人物性格"))
                .setCurrentState(requiredText(intent.description(), 1000, "新人物身份描述"))
                .setAvailableStageCodeJson(actor.getAvailableStageCodeJson()).setEnabled(true)
                .setCurrentSceneCode(sceneCode).setRetentionLevel("L0").setArchived(false)
                .setCharacterZhili(state.characterZhili()).setCharacterDaode(state.characterDaode())
                .setCharacterZhengzhi(state.characterZhengzhi()).setCharacterJiaoji(state.characterJiaoji())
                .setCharacterTineng(state.characterTineng()).setCharacterJiankang(state.characterJiankang())
                .setCharacterPilao(state.characterPilao());
        if (intent.participated()) {
            retain(npc, intent, save.getTotalTurnNumber());
        } else {
            npc.setExpiresAtTurn(save.getTotalTurnNumber() + 1).setRetentionReason("仅在本次场景观察到，尚无实际交互");
        }
        save(npc);
        CharacterEngine.ScholarState scholar = initial.scholar();
        scholarService.save(new CareerProfileShusheng().setCharacterId(npc.getId())
                .setUnlockTurnNumber(save.getTotalTurnNumber()).setLastActiveTurnNumber(null)
                .setAbilityShizi(scholar.abilityShizi()).setAbilityJingyi(scholar.abilityJingyi())
                .setAbilityWenzhang(scholar.abilityWenzhang()).setAbilityCelun(scholar.abilityCelun())
                .setAbilityWenxue(scholar.abilityWenxue()));
        return npc;
    }

    private void retain(Character npc, NpcIntent intent, long settledTurn) {
        String proposed = blank(intent.level()) ? level(npc) : intent.level().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("L0", "L1", "L2").contains(proposed)) {
            throw invalid("人物保留级别必须为L0、L1或L2");
        }
        String nextLevel = level(npc).compareTo(proposed) >= 0 ? level(npc) : proposed;
        Long expires = null;
        if ("L0".equals(nextLevel)) {
            expires = settledTurn + 1;
        } else if ("L1".equals(nextLevel)) {
            int duration = intent.retentionTurns() == null
                    ? GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS : intent.retentionTurns();
            if (!Set.of(GameRuleConstant.MEMORY_SHORT_RETENTION_TURNS,
                    GameRuleConstant.MEMORY_DEFAULT_RETENTION_TURNS,
                    GameRuleConstant.MEMORY_LONG_RETENTION_TURNS).contains(duration)) {
                throw invalid("短期人物只能保留3、9或18回合");
            }
            expires = settledTurn + duration;
        }
        if (expires != null && npc.getExpiresAtTurn() != null) {
            expires = Math.max(expires, npc.getExpiresAtTurn());
        }
        npc.setRetentionLevel(nextLevel).setExpiresAtTurn(expires).setArchived(false)
                .setRetentionReason(requiredText(intent.reason(), 500, "人物保留依据"));
    }

    private boolean active(Character npc, long turn, Set<String> protectedIds) {
        return Boolean.TRUE.equals(npc.getEnabled())
                && (!Boolean.TRUE.equals(npc.getArchived()) || protectedIds.contains(npc.getId()))
                && ("L2".equals(level(npc)) || protectedIds.contains(npc.getId())
                || npc.getExpiresAtTurn() != null && npc.getExpiresAtTurn() > turn);
    }

    /** 本次推进可能已越过到期点，引用是否有效应按行动前观察回合判断。 */
    private boolean activeAtObservation(Character npc, long observedTurn, Set<String> protectedIds) {
        return Boolean.TRUE.equals(npc.getEnabled()) && (protectedIds.contains(npc.getId())
                || !Boolean.TRUE.equals(npc.getArchived()) && ("L2".equals(level(npc))
                || npc.getExpiresAtTurn() != null && npc.getExpiresAtTurn() > observedTurn));
    }

    private boolean inScene(Character npc, String sceneCode, Set<String> fixedCodes) {
        return fixed(npc) ? fixedCodes.contains(npc.getNpcCode()) : Objects.equals(sceneCode, npc.getCurrentSceneCode());
    }

    private Set<String> sceneNpcCodes(String sceneCode) {
        JSONObject scene = jsonLoader.load("game/scene.json", JSONObject.class).getJSONArray("scene")
                .toList(JSONObject.class).stream().filter(item -> Objects.equals(sceneCode, item.getStr("sceneCode")))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景不存在"));
        return new HashSet<>(scene.getJSONArray("availableNpcCode").toList(String.class));
    }

    private Set<String> activeDialogueIds(String saveId) {
        Set<String> ids = new HashSet<>();
        for (DialogueRecord dialogue : dialogueRecordMapper.selectList(Wrappers.<DialogueRecord>lambdaQuery()
                .eq(DialogueRecord::getSaveId, saveId).eq(DialogueRecord::getEnded, false))) {
            ids.add(dialogue.getActorId());
            ids.add(dialogue.getCounterpartId());
        }
        return ids;
    }

    private GameSave lockSave(String saveId) {
        GameSave save = gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery()
                .eq(GameSave::getId, saveId).last("FOR UPDATE"));
        if (save == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        return save;
    }

    private boolean fixed(Character character) {
        return character.getType() == 1 || !blank(character.getNpcCode());
    }

    private String level(Character character) {
        return fixed(character) || blank(character.getRetentionLevel()) ? "L2" : character.getRetentionLevel();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String requiredText(String value, int maxLength, String field) {
        String text = optionalText(value, maxLength, field);
        if (text == null) {
            throw invalid(field + "不能为空");
        }
        return text;
    }

    private String optionalText(String value, int maxLength, String field) {
        if (blank(value)) {
            return null;
        }
        String text = value.strip();
        if (text.length() > maxLength) {
            throw invalid(field + "过长");
        }
        return text;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI人物结算无效：" + message);
    }
}
