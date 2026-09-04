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
@TableName("exam_record")
public class ExamRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 考试记录ID
    private String saveId; // 考试所属的游戏存档ID
    private String characterId; // 参加本次考试的人物ID
    private String requestId; // 最终结算请求的唯一ID，用于防止重复提交
    private String examType; // 考试类型编码
    private String questionText; // 本次考试题目原文
    private Integer passThreshold; // 本次考试的通过分数线
    private Integer baseAbilityScore; // 根据人物属性和职业能力算出的基础能力分B
    private Integer stateOffset; // 创建考试时固定下来的身体状态偏移R
    private String AIThoughtBubble; // 由AI根据人物已有知识生成的思维泡泡
    private String playerChoice; // 玩家选择系统代行还是以身入局
    private String playerInput; // 玩家以身入局时亲自输入的答案原文
    private Integer AIPlayerContentModifier; // 由AI评价玩家答案后产生的内容修正M
    private Integer finalScore; // 按考试公式得到的最终分数
    private String status; // 考试当前状态及最终通过或落榜结果
    private String AIAnswerText; // 系统代行时由AI生成并用于展示的完整答卷文字
    private String AIContent; // 由AI根据Java已确定的考试结果生成的评价与叙事文字
    private Long turnNumber; // 考试发生的回合数
}
