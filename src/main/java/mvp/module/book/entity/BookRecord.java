package mvp.module.book.entity;

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
@TableName("character_book_progress")
public class BookRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 阅读进度记录ID
    private String characterId; // 形成该阅读进度的人物ID
    private String equipmentId; // 对应的书籍装备定义ID
    private BigDecimal currentProgress; // 当前阅读进度，新取得书籍时从0开始
    private Integer totalReadTurnNumber; // 累计用于阅读该书的回合数
    private Boolean completed; // 当前进度是否已经达到该书要求的完成进度
    private Long lastReadTurnNumber; // 最后一次阅读该书时的总回合编号
}
