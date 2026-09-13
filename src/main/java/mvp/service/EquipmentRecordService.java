package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

public interface EquipmentRecordService extends IService<EquipmentRecord> {

    /** 固定目录领取/购买；相同请求重放不重复扣款发货。 */
    JSONObject acquire(String saveId, String actorId, AcquisitionCommand command);

    /** 公共物品按定义叠加，动态物品保留各自稳定身份。 */
    List<InventoryItem> backpack(String saveId, String actorId);

    /** 读取当前场景有效物品；查询不延长保留期限。 */
    List<SceneItem> sceneItems(String saveId, String sceneCode);

    /** 当前存档全部有效场景物品，由客户端按已进入场景筛选。 */
    List<SceneItem> listVisibleSceneItems(String saveId);

    /** 固定目录及实际领取/资金限制，不创建供应者库存。 */
    List<SupplyOffer> supplies(String saveId, String actorId);

    /**
     * 随已确认行动提交场景物品决策。旧ID必须在观察快照中且观察时有效，
     * 新期限从实际结算回合计算；调用方先处理整个行动的请求重放和快照冲突。
     */
    List<SceneItem> applySceneItemChanges(String saveId, String sceneCode, String requestId,
                                        long observedTurnNumber, long occurredTurnNumber,
                                        List<SceneItemChange> changes, Set<String> observedItemIds);

    /** 同一实例拾取后成为长期持有物品，原ID不变。 */
    JSONObject pickup(String saveId, String actorId, ItemCommand command);

    /** 每次使用一份固定用途消耗品；动态线索不接受模型给出的数值效果。 */
    JSONObject use(String saveId, String actorId, ItemCommand command);

    record AcquisitionCommand(String requestId, String sceneCode, String supplierNpcCode,
                              String equipmentCode, int quantity) {
    }

    record AcquisitionIntent(String supplierNpcCode, String equipmentCode, int quantity) implements Serializable {
    }

    record ItemCommand(String requestId, String itemId, String sceneCode, Long expectedTurnNumber) {
    }

    record SceneItemChange(String itemId, String itemName, String description,
                           String retentionLevel, Integer retentionTurns) implements Serializable {
    }

    record SceneItem(String id, String itemCode, String itemName, String description, String sceneCode,
                     String retentionLevel, Long expiresAtTurn, int quantity) {
    }

    record SupplyOffer(Equipment equipment, String sceneCode, int ownedQuantity,
                       boolean canAcquire, List<String> blockedReasons) {
    }

    record InventoryItem(Equipment equipment, int quantity, String itemId,
                         String useEffectCode, boolean usable) {
    }
}
