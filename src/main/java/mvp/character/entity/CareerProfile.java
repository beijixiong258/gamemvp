package mvp.character.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("character_career")
public class CareerProfile {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 职业档案ID
    private String characterId; // 档案所属的人物ID
    private String careerCode; // 职业编码
    private Long unlockTurnNumber; // 人物进入该职业线时的总回合编号
    private Long lastActiveTurnNumber; // 人物最后一次以该职业活动时的总回合编号
    private String status; // 该职业档案当前状态
}
