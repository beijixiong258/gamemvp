package mvp.entity;

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
    private Integer quantity; // 本次取得的数量；同种装备允许有多条记录
    private String aiText; // 由AI生成的装备来源
    private Long acquiredTurnNumber; // 本次实际取得装备时的总回合编号
    private String status; // 当前使用OWNED表示背包内持有
}
