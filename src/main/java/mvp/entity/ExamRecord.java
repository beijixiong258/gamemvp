package mvp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.math.BigDecimal;

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
    private String requestId; // 保留字段；玩家请求由event_record防重，考试ID与状态保证仅结算一次
    private String examType; // 考试类型编码
    private String questionText; // 本次考试题目原文
    private Integer passThreshold; // 本次考试的通过分数线
    private Integer baseAbilityScore; // 根据人物属性、领域能力和已学知识算出的基础能力分B
    private Integer stateOffset; // 创建考试时固定下来的身体状态偏移R
    private Integer diceRoll; // 创建考试时固定的1D100骰点，重复结算不重投
    private Integer luckOffset; // 由固定骰点计算的普通区间修正
    private BigDecimal knowledgeTotal; // 考试开始时所有书籍贡献的学识总量
    private String aiThoughtBubble; // AI生成的思维泡泡，未调用模型时为空
    private String playerChoice; // 玩家选择系统代行还是以身入局
    private String playerInput; // 玩家以身入局时亲自输入的答案原文
    private Integer aiPlayerContentModifier; // Java限幅并取整后的内容修正M，玩家为-10至10，系统代行为0
    private Integer finalScore; // 按考试公式得到的最终分数
    private String status; // 考试当前状态及最终通过或落榜结果
    private String aiAnswerText; // 系统代行时由AI生成并用于展示的完整答卷文字
    private String aiContent; // 玩家内容评价及考试总结，或系统代行的考试总结；不参与计分
    private Long turnNumber; // 考试发生的回合数
}
