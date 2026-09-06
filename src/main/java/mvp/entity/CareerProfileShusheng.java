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
@TableName("career_profile_shusheng")
public class CareerProfileShusheng {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 书生领域档案ID
    private String characterId; // 所属人物ID，同一人物可以同时拥有不同领域档案
    private Long unlockTurnNumber; // 首次建立书生领域档案时的总回合编号
    private Long lastActiveTurnNumber; // 最后参与书生领域行动时的总回合编号
    private Integer abilityShizi; // 识字能力
    private Integer abilityJingyi; // 经义能力
    private Integer abilityWenzhang; // 文章能力
    private Integer abilityCelun; // 策论能力
    private Integer abilityWenxue; // 文学能力
}
