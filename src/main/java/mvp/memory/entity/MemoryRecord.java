package mvp.memory.entity;

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
    private String AIMemorySummary; // 由AI生成并提供给游戏逻辑或后续AI调用的记忆摘要
    private String relatedCharacterIdJson;
    private Long occurredTurnNumber; // 记忆对应事件发生时的总回合编号
}
