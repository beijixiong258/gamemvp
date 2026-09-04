package mvp.equipment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private String id; // 人物装备记录ID
    private String saveId; // 装备记录所属的游戏存档ID
    private String characterId; // 当前持有或使用该装备的人物ID
    private String equipmentId; // 对应的装备定义ID
    private String AIText; // 由AI生成的装备来源
    private Long acquiredTurnNumber; // 人物取得该装备或使用权时的总回合编号
    private Long expirationTurnNumber; // 临时使用权到期的总回合编号，永久持有时为空
    private String status; // 装备记录当前状态，例如有效、归还或遗失
}
