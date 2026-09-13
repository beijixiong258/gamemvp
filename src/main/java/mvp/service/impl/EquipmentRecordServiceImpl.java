package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.entity.Character;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;
import mvp.entity.GameSave;
import mvp.mapper.EquipmentRecordMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.EquipmentRecordService;
import mvp.service.EquipmentService;
import mvp.service.EventRecordService;
import mvp.utils.ClasspathJsonLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EquipmentRecordServiceImpl extends ServiceImpl<EquipmentRecordMapper, EquipmentRecord> implements EquipmentRecordService {

    private static final String SCENE = "SCENE";
    private static final String OWNED = "OWNED";
    private static final String CONSUMED = "CONSUMED";
    private static final String NO_EFFECT = "NONE";
    private static final String RELIEVE_FATIGUE = "RELIEVE_FATIGUE";
    private static final int TEA_FATIGUE_RELIEF = 10;

    private final GameSaveMapper gameSaveMapper;
    private final CharacterService characterService;
    private final EquipmentService equipmentService;
    private final EventRecordService eventRecordService;
    private final ClasspathJsonLoader jsonLoader;

    @Override
    @Transactional
    public JSONObject acquire(String saveId, String actorId, AcquisitionCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少获取参数");
        }
        GameSave gameSave = requireSave(saveId, true);
        JSONObject payload = new JSONObject().set("operation", "ACQUIRE").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        Character actor = requireActor(saveId, actorId);
        requireAvailable(gameSave, actor);
        if (command.quantity() < 1 || command.quantity() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次获取数量须在1至100之间");
        }
        Equipment definition = equipmentService.lambdaQuery()
                .eq(Equipment::getEquipmentCode, command.equipmentCode()).one();
        if (definition == null || !Objects.equals(definition.getSupplierNpcCode(), command.supplierNpcCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此提供者没有该物品");
        }
        JSONObject scene = requireScene(command.sceneCode(), actor, gameSave.getTotalTurnNumber());
        Character supplier = characterService.lambdaQuery().eq(Character::getSaveId, saveId)
                .eq(Character::getNpcCode, command.supplierNpcCode()).eq(Character::getEnabled, true).one();
        if (!scene.getJSONArray("availableActionCode").contains("ACQUIRE_EQUIPMENT")
                || !scene.getJSONArray("availableNpcCode").contains(command.supplierNpcCode())
                || supplier == null || Objects.equals(actorId, supplier.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景没有可交易的提供者");
        }
        if (definition.getPrice() == 0 && (command.quantity() != 1 || lambdaQuery()
                .eq(EquipmentRecord::getSaveId, saveId).eq(EquipmentRecord::getCharacterId, actorId)
                .eq(EquipmentRecord::getEquipmentId, definition.getId()).count() > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "每人每种免费教材只能领取一份");
        }
        long totalCost = (long) definition.getPrice() * command.quantity();
        if (totalCost > actor.getWallet()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "可支配资金不足");
        }
        int cost = (int) totalCost;
        if ((long) supplier.getWallet() + cost > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "提供者钱包已达到上限，当前无法交易");
        }
        actor.setWallet(actor.getWallet() - cost);
        characterService.updateById(actor);
        if (cost > 0) {
            supplier.setWallet(supplier.getWallet() + cost);
            characterService.updateById(supplier);
        }
        EquipmentRecord item = new EquipmentRecord().setSaveId(saveId).setCharacterId(actorId)
                .setEquipmentId(definition.getId()).setQuantity(command.quantity())
                .setItemName(definition.getEquipmentName()).setItemDescription(definition.getDescription())
                .setAcquiredTurnNumber(gameSave.getTotalTurnNumber()).setStatus(OWNED)
                .setRetentionLevel("L2").setLastReinforcedTurn(gameSave.getTotalTurnNumber()).setArchived(false);
        save(item);
        JSONObject result = itemResult(command.requestId(), actor, item, definition, gameSave.getTotalTurnNumber())
                .set("quantity", command.quantity()).set("cost", cost).set("walletAfter", actor.getWallet());
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload,
                "ACQUIRE_EQUIPMENT", gameSave.getTotalTurnNumber(), result);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItem> backpack(String saveId, String actorId) {
        Character actor = requireActor(saveId, actorId);
        List<EquipmentRecord> records = lambdaQuery().eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getCharacterId, actorId).eq(EquipmentRecord::getStatus, OWNED)
                .gt(EquipmentRecord::getQuantity, 0).orderByAsc(EquipmentRecord::getAcquiredTurnNumber)
                .orderByAsc(EquipmentRecord::getId).list();
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> definitionIds = records.stream().map(EquipmentRecord::getEquipmentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Equipment> definitions = definitionIds.isEmpty() ? Map.of()
                : equipmentService.listByIds(definitionIds).stream()
                        .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        Map<String, List<EquipmentRecord>> groups = records.stream().collect(Collectors.groupingBy(
                item -> item.getEquipmentId() == null ? "DYNAMIC_" + item.getId() : item.getEquipmentId(),
                LinkedHashMap::new, Collectors.toList()));
        List<InventoryItem> result = new ArrayList<>();
        GameSave gameSave = requireSave(saveId, false);
        for (List<EquipmentRecord> group : groups.values()) {
            EquipmentRecord first = group.get(0);
            Equipment definition = first.getEquipmentId() == null ? dynamicDefinition(first)
                    : definitions.get(first.getEquipmentId());
            if (definition == null) {
                throw new IllegalStateException("持有物品缺少对应公共定义：" + first.getId());
            }
            int quantity = group.stream().map(EquipmentRecord::getQuantity).reduce(0, Math::addExact);
            String effect = effectCode(definition);
            boolean usable = RELIEVE_FATIGUE.equals(effect) && actor.getCharacterPilao() > 0
                    && "STUDYING".equals(gameSave.getStatus()) && actor.getCharacterJiankang() > 0
                    && actor.getSickTurnsRemaining() == 0;
            result.add(new InventoryItem(definition, quantity, first.getId(), effect, usable));
        }
        result.sort(Comparator.comparing(item -> item.equipment().getEquipmentCode()));
        return List.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneItem> sceneItems(String saveId, String sceneCode) {
        GameSave gameSave = requireSave(saveId, false);
        requireScene(sceneCode, null, gameSave.getTotalTurnNumber());
        return lambdaQuery().eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getSceneCode, sceneCode).eq(EquipmentRecord::getStatus, SCENE)
                .eq(EquipmentRecord::getArchived, false).gt(EquipmentRecord::getQuantity, 0)
                .and(query -> query.isNull(EquipmentRecord::getExpiresAtTurn)
                        .or().gt(EquipmentRecord::getExpiresAtTurn, gameSave.getTotalTurnNumber()))
                .orderByAsc(EquipmentRecord::getAcquiredTurnNumber).orderByAsc(EquipmentRecord::getId)
                .list().stream().map(this::sceneItem).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SceneItem> listVisibleSceneItems(String saveId) {
        GameSave gameSave = requireSave(saveId, false);
        return lambdaQuery().eq(EquipmentRecord::getSaveId, saveId).eq(EquipmentRecord::getStatus, SCENE)
                .eq(EquipmentRecord::getArchived, false).gt(EquipmentRecord::getQuantity, 0)
                .and(query -> query.isNull(EquipmentRecord::getExpiresAtTurn)
                        .or().gt(EquipmentRecord::getExpiresAtTurn, gameSave.getTotalTurnNumber()))
                .orderByAsc(EquipmentRecord::getSceneCode).orderByAsc(EquipmentRecord::getAcquiredTurnNumber)
                .orderByAsc(EquipmentRecord::getId).list().stream().map(this::sceneItem).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplyOffer> supplies(String saveId, String actorId) {
        GameSave gameSave = requireSave(saveId, false);
        Character actor = requireActor(saveId, actorId);
        List<EquipmentRecord> history = lambdaQuery().eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getCharacterId, actorId).isNotNull(EquipmentRecord::getEquipmentId).list();
        Set<String> acquired = history.stream().map(EquipmentRecord::getEquipmentId).collect(Collectors.toSet());
        Map<String, Integer> owned = history.stream().filter(item -> OWNED.equals(item.getStatus()))
                .collect(Collectors.toMap(EquipmentRecord::getEquipmentId, EquipmentRecord::getQuantity, Math::addExact));
        Set<String> suppliers = characterService.lambdaQuery().eq(Character::getSaveId, saveId)
                .eq(Character::getEnabled, true).isNotNull(Character::getNpcCode).list().stream()
                .map(Character::getNpcCode).collect(Collectors.toSet());
        List<JSONObject> scenes = jsonLoader.load("game/scene.json", JSONObject.class)
                .getJSONArray("scene").toList(JSONObject.class);
        List<SupplyOffer> offers = new ArrayList<>();
        for (Equipment definition : equipmentService.lambdaQuery().orderByAsc(Equipment::getEquipmentCode).list()) {
            for (JSONObject scene : scenes) {
                if (!scene.getJSONArray("availableActionCode").contains("ACQUIRE_EQUIPMENT")
                        || !scene.getJSONArray("availableNpcCode").contains(definition.getSupplierNpcCode())) {
                    continue;
                }
                List<String> reasons = new ArrayList<>();
                if (!"STUDYING".equals(gameSave.getStatus())) {
                    reasons.add("请先完成当前考试");
                }
                if (actor.getCharacterJiankang() <= 0 || actor.getSickTurnsRemaining() > 0) {
                    reasons.add("请先完成重病休养");
                }
                if (actor.getType() != 1 && !characterService.isPresent(actor, scene.getStr("sceneCode"), gameSave.getTotalTurnNumber())) {
                    reasons.add("人物不在供应场景");
                }
                if (!suppliers.contains(definition.getSupplierNpcCode())
                        || Objects.equals(actor.getNpcCode(), definition.getSupplierNpcCode())) {
                    reasons.add("当前没有可交易的提供者");
                }
                if (definition.getPrice() == 0 && acquired.contains(definition.getId())) {
                    reasons.add("这本免费教材已经领取过");
                }
                if (actor.getWallet() < definition.getPrice()) {
                    reasons.add("可支配资金不足");
                }
                offers.add(new SupplyOffer(definition, scene.getStr("sceneCode"),
                        owned.getOrDefault(definition.getId(), 0), reasons.isEmpty(), List.copyOf(reasons)));
            }
        }
        return List.copyOf(offers);
    }

    @Override
    @Transactional
    public List<SceneItem> applySceneItemChanges(String saveId, String sceneCode, String requestId,
                                               long observedTurnNumber, long occurredTurnNumber,
                                               List<SceneItemChange> changes, Set<String> observedItemIds) {
        GameSave gameSave = requireSave(saveId, true);
        requireScene(sceneCode, null, gameSave.getTotalTurnNumber());
        if (requestId == null || requestId.isBlank() || requestId.length() > 120
                || observedTurnNumber < 0 || observedTurnNumber > occurredTurnNumber
                || gameSave.getTotalTurnNumber() != occurredTurnNumber) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "场景物品的请求或结算回合不一致");
        }
        List<SceneItemChange> decisions = changes == null ? List.of() : changes;
        if (decisions.size() > 8 || decisions.stream().anyMatch(Objects::isNull)) {
            throw invalidSceneDecision("物品决策最多8条，且不能包含空项");
        }
        Set<String> observedIds = observedItemIds == null ? Set.of() : observedItemIds;
        Set<String> changedIds = new HashSet<>();
        List<SceneItem> result = new ArrayList<>();
        int newItems = 0;
        for (int index = 0; index < decisions.size(); index++) {
            SceneItemChange decision = decisions.get(index);
            EquipmentRecord item;
            boolean created = decision.itemId() == null || decision.itemId().isBlank();
            if (created) {
                if (++newItems > 2) {
                    throw invalidSceneDecision("同次行动最多新增2个场景物品");
                }
                String id = UUID.nameUUIDFromBytes((saveId + ":" + requestId + ":scene-item:" + index)
                        .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                item = getById(id);
                if (item != null) {
                    // 同一行动重新进入内部结算时保留既有身份、所有权和期限。
                    result.add(sceneItem(item));
                    continue;
                }
                String name = boundedDescription(decision.itemName(), 64, "场景物品名称");
                if (equipmentService.lambdaQuery().eq(Equipment::getEquipmentName, name).count() > 0) {
                    throw invalidSceneDecision("公共目录中的书籍或消耗品必须通过正式获取，不能创建同名免费小物");
                }
                String description = boundedDescription(decision.description(), 600, "场景物品说明");
                item = new EquipmentRecord().setId(id).setSaveId(saveId).setSceneCode(sceneCode)
                        .setItemName(name).setItemDescription(description).setQuantity(1)
                        .setAcquiredTurnNumber(occurredTurnNumber).setStatus(SCENE).setArchived(false);
            } else {
                if (!observedIds.contains(decision.itemId()) || !changedIds.add(decision.itemId())) {
                    throw invalidSceneDecision("只能处理本次已观察且未重复引用的物品ID");
                }
                item = getById(decision.itemId());
                if (item == null || !Objects.equals(saveId, item.getSaveId())
                        || !Objects.equals(sceneCode, item.getSceneCode()) || !SCENE.equals(item.getStatus())
                        || item.getEquipmentId() != null || item.getQuantity() <= 0
                        || item.getAcquiredTurnNumber() > observedTurnNumber
                        || (item.getExpiresAtTurn() != null && item.getExpiresAtTurn() <= observedTurnNumber)
                        || (Boolean.TRUE.equals(item.getArchived()) && item.getExpiresAtTurn() == null)) {
                    throw invalidSceneDecision("物品不属于本次场景、观察时已到期或已经被取得");
                }
            }
            String proposed = decision.retentionLevel();
            String level = Set.of("L0", "L1", "L2").contains(proposed == null ? "" : proposed) ? proposed : "L1";
            if (!created && retentionRank(item.getRetentionLevel()) > retentionRank(level)) {
                level = item.getRetentionLevel();
            }
            Long expiresAtTurn = null;
            if (!"L2".equals(level)) {
                int ttl = "L0".equals(level) ? 1 : retentionTurns(decision.retentionTurns());
                expiresAtTurn = Math.addExact(occurredTurnNumber, ttl);
                if (!created && item.getExpiresAtTurn() != null) {
                    expiresAtTurn = Math.max(expiresAtTurn, item.getExpiresAtTurn());
                }
            }
            item.setRetentionLevel(level).setExpiresAtTurn(expiresAtTurn)
                    .setLastReinforcedTurn(occurredTurnNumber).setArchived(false);
            if (created) {
                save(item);
            } else {
                updateById(item);
            }
            result.add(sceneItem(item));
        }
        archiveExpired(saveId, occurredTurnNumber);
        return List.copyOf(result);
    }

    @Override
    @Transactional
    public JSONObject pickup(String saveId, String actorId, ItemCommand command) {
        requireCommand(command);
        GameSave gameSave = requireSave(saveId, true);
        JSONObject payload = new JSONObject().set("operation", "PICKUP").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        Character actor = requireActor(saveId, actorId);
        requireAvailable(gameSave, actor);
        requireTurn(gameSave, command.expectedTurnNumber());
        requireScene(command.sceneCode(), actor, gameSave.getTotalTurnNumber());
        EquipmentRecord item = getById(command.itemId());
        if (item == null || !Objects.equals(saveId, item.getSaveId()) || !SCENE.equals(item.getStatus())
                || !Objects.equals(command.sceneCode(), item.getSceneCode()) || Boolean.TRUE.equals(item.getArchived())
                || item.getQuantity() <= 0
                || (item.getExpiresAtTurn() != null && item.getExpiresAtTurn() <= gameSave.getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该物品不在当前场景、已经到期或已被取得");
        }
        String origin = item.getSceneCode();
        item.setCharacterId(actorId).setStatus(OWNED).setSceneCode(null).setRetentionLevel("L2")
                .setExpiresAtTurn(null).setArchived(false).setAcquiredTurnNumber(gameSave.getTotalTurnNumber())
                .setLastReinforcedTurn(gameSave.getTotalTurnNumber()).setAiText("在" + origin + "拾取。");
        updateById(item);
        Equipment definition = item.getEquipmentId() == null ? dynamicDefinition(item)
                : equipmentService.getById(item.getEquipmentId());
        JSONObject result = itemResult(command.requestId(), actor, item, definition, gameSave.getTotalTurnNumber())
                .set("quantity", item.getQuantity()).set("cost", 0).set("walletAfter", actor.getWallet())
                .set("summary", "已将“" + definition.getEquipmentName() + "”收入行囊。");
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload,
                "PICKUP_ITEM", gameSave.getTotalTurnNumber(), result);
        archiveExpired(saveId, gameSave.getTotalTurnNumber());
        return result;
    }

    @Override
    @Transactional
    public JSONObject use(String saveId, String actorId, ItemCommand command) {
        requireCommand(command);
        GameSave gameSave = requireSave(saveId, true);
        JSONObject payload = new JSONObject().set("operation", "USE_ITEM").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        Character actor = requireActor(saveId, actorId);
        requireAvailable(gameSave, actor);
        requireTurn(gameSave, command.expectedTurnNumber());
        requireScene(command.sceneCode(), actor, gameSave.getTotalTurnNumber());
        EquipmentRecord item = getById(command.itemId());
        if (item == null || !Objects.equals(saveId, item.getSaveId()) || !Objects.equals(actorId, item.getCharacterId())
                || !OWNED.equals(item.getStatus()) || item.getQuantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "行囊中没有可使用的这份物品");
        }
        Equipment definition = item.getEquipmentId() == null ? dynamicDefinition(item)
                : equipmentService.getById(item.getEquipmentId());
        if (definition == null || !"ITEM_QINGCHA".equals(definition.getEquipmentCode())
                || !RELIEVE_FATIGUE.equals(effectCode(definition))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这件物品没有可直接使用的效果；书籍请通过读书使用");
        }
        int fatigueBefore = actor.getCharacterPilao();
        if (fatigueBefore <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前没有疲劳，无需消耗清茶");
        }
        actor.setCharacterPilao(Math.max(0, fatigueBefore - TEA_FATIGUE_RELIEF));
        characterService.updateById(actor);
        item.setQuantity(item.getQuantity() - 1).setRetentionLevel("L2").setExpiresAtTurn(null).setArchived(false);
        if (item.getQuantity() == 0) {
            item.setStatus(CONSUMED);
        }
        updateById(item);
        int fatigueChange = actor.getCharacterPilao() - fatigueBefore;
        JSONObject result = itemResult(command.requestId(), actor, item, definition, gameSave.getTotalTurnNumber())
                .set("quantity", 1).set("remainingQuantity", item.getQuantity()).set("fatigueChange", fatigueChange)
                .set("fatigueAfter", actor.getCharacterPilao()).set("cost", 0)
                .set("summary", "饮用一份清茶，疲劳减少" + (-fatigueChange) + "。不消耗回合。");
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload,
                "USE_ITEM", gameSave.getTotalTurnNumber(), result);
        archiveExpired(saveId, gameSave.getTotalTurnNumber());
        return result;
    }

    private GameSave requireSave(String saveId, boolean lock) {
        var query = Wrappers.<GameSave>lambdaQuery().eq(GameSave::getId, saveId);
        if (lock) {
            query.last("FOR UPDATE");
        }
        GameSave gameSave = gameSaveMapper.selectOne(query);
        if (gameSave == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        return gameSave;
    }

    private Character requireActor(String saveId, String actorId) {
        Character actor = characterService.getById(actorId);
        if (actor == null || !Objects.equals(saveId, actor.getSaveId()) || !Boolean.TRUE.equals(actor.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该人物");
        }
        return actor;
    }

    private void requireAvailable(GameSave gameSave, Character actor) {
        if (!"STUDYING".equals(gameSave.getStatus()) || actor.getCharacterJiankang() <= 0
                || actor.getSickTurnsRemaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成考试或重病休养");
        }
    }

    private JSONObject requireScene(String sceneCode, Character actor, long turnNumber) {
        JSONObject scene = jsonLoader.load("game/scene.json", JSONObject.class).getJSONArray("scene")
                .toList(JSONObject.class).stream().filter(value -> Objects.equals(sceneCode, value.getStr("sceneCode")))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景不存在"));
        if (actor != null && actor.getType() != 1 && !characterService.isPresent(actor, sceneCode, turnNumber)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行动者不在当前场景");
        }
        if (actor != null && !scene.getJSONArray("availableActionCode").contains("FREE_ACTION")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此场景当前不开放物品操作");
        }
        return scene;
    }

    private void requireCommand(ItemCommand command) {
        if (command == null || command.itemId() == null || command.itemId().isBlank()
                || command.sceneCode() == null || command.sceneCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供物品实例和发生场景");
        }
    }

    private void requireTurn(GameSave gameSave, Long expectedTurn) {
        if (expectedTurn == null || !expectedTurn.equals(gameSave.getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回合已变化，请重新查看物品后操作");
        }
    }

    private void archiveExpired(String saveId, long turnNumber) {
        lambdaUpdate().eq(EquipmentRecord::getSaveId, saveId).eq(EquipmentRecord::getStatus, SCENE)
                .eq(EquipmentRecord::getArchived, false).isNotNull(EquipmentRecord::getExpiresAtTurn)
                .le(EquipmentRecord::getExpiresAtTurn, turnNumber).set(EquipmentRecord::getArchived, true).update();
    }

    private Equipment dynamicDefinition(EquipmentRecord item) {
        return new Equipment().setId(item.getId()).setEquipmentCode("DYNAMIC_" + item.getId())
                .setEquipmentName(item.getItemName()).setDescription(item.getItemDescription())
                .setEquipmentType("TOKEN").setRarityCode("COMMON").setPrice(0).setUseEffectCode(NO_EFFECT);
    }

    private SceneItem sceneItem(EquipmentRecord item) {
        return new SceneItem(item.getId(), "DYNAMIC_" + item.getId(), item.getItemName(), item.getItemDescription(), item.getSceneCode(),
                item.getRetentionLevel(), item.getExpiresAtTurn(), item.getQuantity());
    }

    private JSONObject itemResult(String requestId, Character actor, EquipmentRecord item,
                                  Equipment definition, long turnNumber) {
        if (definition == null) {
            throw new IllegalStateException("物品缺少正式定义：" + item.getId());
        }
        return new JSONObject().set("requestId", requestId).set("equipmentRecordId", item.getId())
                .set("actorId", actor.getId()).set("equipmentCode", definition.getEquipmentCode())
                .set("equipmentName", definition.getEquipmentName()).set("turnNumber", turnNumber);
    }

    private String effectCode(Equipment definition) {
        return definition.getUseEffectCode() == null ? NO_EFFECT : definition.getUseEffectCode();
    }

    private int retentionTurns(Integer suggested) {
        return suggested != null && Set.of(3, 9, 18).contains(suggested) ? suggested : 9;
    }

    private int retentionRank(String level) {
        return "L2".equals(level) ? 2 : "L1".equals(level) ? 1 : 0;
    }

    private String boundedDescription(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw invalidSceneDecision(label + "缺失或超过" + maxLength + "字符");
        }
        return value.strip();
    }

    private ResponseStatusException invalidSceneDecision(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI场景物品决策无效：" + message);
    }
}
