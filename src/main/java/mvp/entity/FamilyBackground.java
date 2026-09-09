package mvp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("family_background")
public class FamilyBackground {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 初始家庭背景ID
    private String saveId; // 所属存档ID
    private Integer initialWealth; // 开局家庭财富，单位为文
    private String backgroundSummary; // 初始家庭背景；独立生成成功后保存六岁入学前的童年叙事
}
