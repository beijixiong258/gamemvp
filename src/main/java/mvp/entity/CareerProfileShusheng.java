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
    @TableId(type = IdType.INPUT)
    private String careerProfileId; // 父职业档案ID，同时也是本表主键
    private Integer abilityShizi; // 识字能力
    private Integer abilityJingyi; // 经义能力
    private Integer abilityWenzhang; // 文章能力
    private Integer abilityCelun; // 策论能力
    private Integer abilityWenxue; // 文学能力
}
