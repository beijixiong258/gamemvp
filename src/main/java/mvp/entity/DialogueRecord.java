package mvp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("dialogue_record")
public class DialogueRecord {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String saveId;
    private String actorId;
    private String counterpartId;
    private String sceneCode;
    private Long startedTurnNumber;
    private Integer version; // 每次成功对话往返加1，防止旧消息覆盖新历史
    private Boolean ended;
    private String messagesJson; // 本场原文及已执行交易；结束后作为压缩记忆来源，不直接带入后续上下文
}
