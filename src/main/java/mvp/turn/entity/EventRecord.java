package mvp.turn.entity;

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
@TableName("event_record")
public class EventRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 事件记录ID
    private String saveId; // 事件所属的游戏存档ID
    private String eventCode; // 事件编码
    private String eventSummary; // 人生节点或世界事件的自然语言摘要
    private String relatedCharacterIdJson; // 与事件有关的人物ID组成的JSON数组
    private Long occurredTurnNumber; // 事件实际发生时的总回合编号
    private String settlementResultJson; // 事件已经通过校验并执行的结算结果JSON
    private Boolean lifeMilestone; // 该事件是否需要展示在人物人生节点列表中
}
