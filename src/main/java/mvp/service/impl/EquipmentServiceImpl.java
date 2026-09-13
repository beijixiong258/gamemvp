package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.entity.Equipment;
import mvp.mapper.EquipmentMapper;
import mvp.service.EquipmentService;
import mvp.utils.ClasspathJsonLoader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, Equipment> implements EquipmentService {

    private final ClasspathJsonLoader jsonLoader;

    @Override
    @Transactional
    public Map<String, Equipment> importDefinitions() {
        JSONArray configured = jsonLoader.load("game/equipment.json", JSONObject.class).getJSONArray("equipment");
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("固定物品目录缺少equipment数组");
        }
        List<Equipment> definitions = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < configured.size(); index++) {
            Equipment definition = configured.getJSONObject(index).toBean(Equipment.class);
            String code = definition.getEquipmentCode();
            if (code == null || code.isBlank() || code.length() > 64 || !codes.add(code)) {
                throw new IllegalStateException("固定物品编码缺失、过长或重复：" + code);
            }
            try {
                Equipment.Rarity.valueOf(definition.getRarityCode());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalStateException("固定物品品质编码无效：" + code, exception);
            }
            String effect = definition.getUseEffectCode();
            if (effect == null || effect.isBlank()) {
                definition.setUseEffectCode("NONE");
            } else if (!"NONE".equals(effect) && !"RELIEVE_FATIGUE".equals(effect)) {
                throw new IllegalStateException("固定物品用途未知：" + code);
            }
            if (definition.getEquipmentName() == null || definition.getEquipmentName().isBlank()
                    || definition.getEquipmentName().length() > 128
                    || !("BOOK".equals(definition.getEquipmentType()) || "CONSUMABLE".equals(definition.getEquipmentType()))
                    || definition.getSupplierNpcCode() == null || definition.getSupplierNpcCode().isBlank()
                    || definition.getDescription() == null || definition.getDescription().isBlank()
                    || definition.getPrice() == null || definition.getPrice() < 0
                    || ("BOOK".equals(definition.getEquipmentType()) && !"NONE".equals(definition.getUseEffectCode()))
                    || ("RELIEVE_FATIGUE".equals(definition.getUseEffectCode())
                        && !"ITEM_QINGCHA".equals(code))) {
                throw new IllegalStateException("固定物品目录字段无效：" + code);
            }
            definitions.add(definition.setId(null));
        }
        // 所有初始化按相同编码顺序取得定义行锁，降低并发初始化的锁顺序冲突。
        definitions.sort(Comparator.comparing(Equipment::getEquipmentCode));
        Map<String, Equipment> result = new LinkedHashMap<>();
        for (Equipment definition : definitions) {
            Equipment existing = lambdaQuery().eq(Equipment::getEquipmentCode, definition.getEquipmentCode()).one();
            if (existing == null) {
                try {
                    baseMapper.insert(definition);
                } catch (DuplicateKeyException exception) {
                    // 另一个存档已先插入同编码；锁定读取得已提交的真实ID，不沿用失败插入的UUID。
                    Equipment concurrent = baseMapper.selectOne(Wrappers.<Equipment>lambdaQuery()
                            .eq(Equipment::getEquipmentCode, definition.getEquipmentCode()).last("FOR UPDATE"));
                    if (concurrent == null) {
                        throw exception;
                    }
                    definition.setId(concurrent.getId());
                    baseMapper.updateById(definition);
                }
            } else {
                definition.setId(existing.getId());
                baseMapper.updateById(definition);
            }
            result.put(definition.getEquipmentCode(), definition);
        }
        return Map.copyOf(result);
    }
}
