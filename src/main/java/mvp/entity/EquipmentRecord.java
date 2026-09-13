package mvp.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("character_equipment")
public class EquipmentRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 本存档物品实例ID，拾取和保留升级时不变
    private String saveId;
    private String characterId; // SCENE无持有人，OWNED/CONSUMED保留实际持有人
    private String equipmentId; // 公共定义ID；动态线索和纪念物为空
    private Integer quantity; // 耗尽时为0，保留实例和已执行事实
    private String aiText; // 实际取得时的来源说明
    private Long acquiredTurnNumber; // 场景实例创建/正式取得的总回合编号
    private String status; // SCENE、OWNED、CONSUMED
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sceneCode;
    private String itemName; // 动态实例自己的名称，不写入公共定义
    private String itemDescription; // 动态实例自己的描述
    private String retentionLevel; // L0/L1/L2；正式持有后固定为L2
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long expiresAtTurn;
    private Long lastReinforcedTurn;
    private Boolean archived; // 归档仅退出场景召回，不删除历史或正式持有物品
}
