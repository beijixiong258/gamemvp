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
    private Long sourceEventSequence; // 来源事件的稳定顺序，同一回合仍能区分先后
    private String sceneCode; // 事件发生场景，仅作为检索索引
    private String relatedCharacterIdJson; // 有来源依据的相关人物ID数组
    private String relatedEquipmentCodeJson; // 已确认涉及的物品编码数组
    private Long occurredTurnNumber; // 记忆对应事件发生时的总回合编号
    private String memoryLevel; // L0当场、L1近期、L2长期；固定NPC身份与记忆级别独立
    private String memoryKind; // EXPERIENCE经历、RELATIONSHIP关系、COMMITMENT明确约定
    private String retentionReason; // resolver建议经规则确认后的保留原因
    private Long expiresAtTurn; // L1到期回合，达到即退出召回；其他级别为空
    private Long lastReinforcedTurn; // 最后一次实际经历强化该记忆的回合，读取不续期
    private Long lastReinforcedSequence; // 最后一次实际强化的事件顺序，读取不改变
    private Boolean archived; // 主动不保留或已到期；仍保留索引，禁止缺口补齐重新生成
}
