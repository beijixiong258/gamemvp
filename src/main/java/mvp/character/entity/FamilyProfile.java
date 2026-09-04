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
@TableName("family_state")
public class FamilyProfile {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 家庭状态记录ID
    private String saveId; // 所属存档ID
    private Integer wealth; // 家庭可用财富，单位为文
    private String backgroundSummary; // 家庭背景摘要
}
