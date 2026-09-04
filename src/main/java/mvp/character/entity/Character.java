package mvp.character.entity;

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
@TableName("game_character")
public class Character {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 人物ID
    private String saveId; // 人物所属的游戏存档ID
    private String name; // 人物姓名
    private int type;//1为人类玩家，0为NPC。只要不是人类玩家控制的就都是NPC
    private Integer characterZhili; //智力
    private Integer characterDaode; //道德
    private Integer characterZhengzhi; //政治
    private Integer characterJiaoji; //交际
    private Integer characterTineng; //体能
    private Integer characterJiankang; // 角色当前健康值
    private Integer characterPilao; // 角色当前疲劳值
    private String currentMainCareerCode; // 人物当前主职业编码
    private String birthday; // 人物在游戏纪年中的生日
    private String personalitySummary; // 人物性格与行为倾向摘要
    private String currentState; // 人物当前可供游戏逻辑和AI读取的状态摘要
    private String availableStageCodeJson; // 人物可以参与的成长阶段编码JSON数组
    private Boolean enabled; // 人物当前是否启用
}
