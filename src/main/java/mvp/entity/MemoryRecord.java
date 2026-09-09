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
@TableName("memory_record")
public class MemoryRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 记忆记录ID
    private String saveId; // 记忆所属的游戏存档ID
    private String ownerCharacterId; // 拥有这段记忆的人物ID
    private String sourceEventId; // 产生这段记忆的事件记录ID
    private String sceneCode; // 事件发生场景，仅作为检索索引
    private String relatedCharacterIdJson; // 有来源依据的相关人物ID数组
    private String relatedEquipmentCodeJson; // 已确认涉及的物品编码数组
    private Long occurredTurnNumber; // 记忆对应事件发生时的总回合编号
}
