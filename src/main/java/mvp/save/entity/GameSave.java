package mvp.save.entity;

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
@TableName("game_save")
public class GameSave {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 存档ID
    private String status; // 存档当前流程状态
    private Integer birthYear; // 角色出生年份
    private Integer currentYear; // 当前游戏年份
    private Integer currentMonth; // 当前游戏月份
    private Integer turnInMonth; // 当前月份内的回合序号
    private Integer age; // 角色当前年龄
    private Long totalTurnNumber; // 从正常游戏阶段开始累计的总回合编号
    private String growthStage; // 角色当前成长阶段
    private Long randomSeed; // 当前存档使用的固定随机种子
}
