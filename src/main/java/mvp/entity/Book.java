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
@TableName("book_definition")
public class Book {
    @TableId(type = IdType.INPUT)
    private String equipmentId; // 父装备定义ID，同时也是本表主键
    private String growthDomainCode; // 阅读收益所属领域，不作为人物身份限制
    private String readingRequirementJson; // 固定结构的阅读条件JSON
    private Integer difficulty; // 书籍阅读难度
    private Integer requiredProgress; // 完整读完该书所需的总进度
    private Integer baseProgressPerTurn; // 不考虑人物修正时每回合增加的基础进度
    private Integer abilityShiziWeight; // 识字能力收益权重百分比
    private Integer abilityJingyiWeight; // 经义能力收益权重百分比
    private Integer abilityWenzhangWeight; // 文章能力收益权重百分比
    private Integer abilityCelunWeight; // 策论能力收益权重百分比
    private Integer abilityWenxueWeight; // 文学能力收益权重百分比
    private Integer fatigueCost; // 阅读一个回合产生的基础疲劳值
    private Integer totalKnowledge; // 完整读完本书获得的总学识
    private String knowledgeSummary; // 本书内容介绍，不再按进度阈值解锁文本
}
