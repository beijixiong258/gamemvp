package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;

import java.io.Serializable;
import java.util.List;

public interface EquipmentRecordService extends IService<EquipmentRecord> {

    /**
     * 执行一次免费获取或有偿购买，玩家与NPC使用同一方法。
     *
     * @param actorId 获取物品的人物ID
     * @param command 稳定请求编号、场景、提供者、装备编码和数量
     * @return 已完成的扣款/入包结果；重传返回原结果
     */
    JSONObject acquire(String saveId, String actorId, AcquisitionCommand command);

    /**
     * 按装备定义聚合背包数量，不改变持有记录。
     *
     * @return 同种物品叠加后的背包
     */
    List<InventoryItem> backpack(String saveId, String actorId);

    record AcquisitionCommand(String requestId, String sceneCode, String supplierNpcCode,
                              String equipmentCode, int quantity) {
    }

    record AcquisitionIntent(String supplierNpcCode, String equipmentCode, int quantity) implements Serializable {
    }

    record InventoryItem(Equipment equipment, int quantity) {
    }
}
