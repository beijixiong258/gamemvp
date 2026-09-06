package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.ai.ClasspathJsonLoader;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EquipmentRecordServiceImpl extends ServiceImpl<EquipmentRecordMapper, EquipmentRecord> implements EquipmentRecordService {

    private final GameSaveMapper gameSaveMapper;
    private final CharacterService characterService;
    private final EquipmentService equipmentService;
    private final EventRecordService eventRecordService;
    private final ClasspathJsonLoader jsonLoader;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public JSONObject acquire(String saveId, String actorId, AcquisitionCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少获取参数");
        }
        GameSave gameSave = gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery()
                .eq(GameSave::getId, saveId).last("FOR UPDATE"));
        if (gameSave == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        JSONObject payload = new JSONObject().set("operation", "ACQUIRE").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        Character actor = characterService.getById(actorId);
        if (actor == null || !Objects.equals(saveId, actor.getSaveId()) || !Boolean.TRUE.equals(actor.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该人物");
        }
        if (!"STUDYING".equals(gameSave.getStatus()) || actor.getCharacterJiankang() <= 0
                || actor.getSickTurnsRemaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成考试或重病休养");
        }
        if (command.quantity() < 1 || command.quantity() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次获取数量须在1至100之间");
        }
        Equipment definition = equipmentService.lambdaQuery()
                .eq(Equipment::getEquipmentCode, command.equipmentCode()).one();
        if (definition == null || !Objects.equals(definition.getSupplierNpcCode(), command.supplierNpcCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "此提供者没有该物品");
        }
        boolean available = jsonLoader.load("game/scene.json", JSONObject.class).getJSONArray("scene")
                .toList(JSONObject.class).stream().anyMatch(scene ->
                        Objects.equals(scene.getStr("sceneCode"), command.sceneCode())
                                && scene.getJSONArray("availableNpcCode").contains(command.supplierNpcCode()));
        Character supplier = characterService.lambdaQuery().eq(Character::getSaveId, saveId)
                .eq(Character::getNpcCode, command.supplierNpcCode()).eq(Character::getEnabled, true).one();
        if (!available || supplier == null || Objects.equals(actorId, supplier.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景没有可交易的提供者");
        }
        int cost = Math.multiplyExact(definition.getPrice(), command.quantity());
        if (actor.getWallet() < cost) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "可支配资金不足");
        }
        actor.setWallet(actor.getWallet() - cost);
        characterService.updateById(actor);
        if (cost > 0) {
            supplier.setWallet(Math.addExact(supplier.getWallet(), cost));
            characterService.updateById(supplier);
        }
        EquipmentRecord item = new EquipmentRecord().setSaveId(saveId).setCharacterId(actorId)
                .setEquipmentId(definition.getId()).setQuantity(command.quantity())
                .setAcquiredTurnNumber(gameSave.getTotalTurnNumber()).setStatus("OWNED");
        save(item);
        JSONObject result = new JSONObject().set("requestId", command.requestId()).set("equipmentRecordId", item.getId())
                .set("actorId", actorId).set("equipmentCode", definition.getEquipmentCode())
                .set("equipmentName", definition.getEquipmentName()).set("quantity", command.quantity())
                .set("cost", cost).set("walletAfter", actor.getWallet()).set("turnNumber", gameSave.getTotalTurnNumber());
        eventRecordService.recordOperation(saveId, actorId, command.requestId(), payload,
                "ACQUIRE_EQUIPMENT", gameSave.getTotalTurnNumber(), result);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public List<InventoryItem> backpack(String saveId, String actorId) {
        Character actor = characterService.getById(actorId);
        if (actor == null || !Objects.equals(saveId, actor.getSaveId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该人物");
        }
        Map<String, Integer> quantities = lambdaQuery().eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getCharacterId, actorId).eq(EquipmentRecord::getStatus, "OWNED")
                .list().stream().collect(Collectors.toMap(EquipmentRecord::getEquipmentId,
                        EquipmentRecord::getQuantity, Integer::sum));
        if (quantities.isEmpty()) {
            return List.of();
        }
        return equipmentService.listByIds(quantities.keySet()).stream()
                .sorted(Comparator.comparing(Equipment::getEquipmentCode))
                .map(item -> new InventoryItem(item, quantities.get(item.getId()))).toList();
    }
}
