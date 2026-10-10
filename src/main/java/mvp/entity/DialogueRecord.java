package mvp.entity;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.experimental.Accessors;
import mvp.engine.GameRuleConstant;

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
    private Integer version; // 回复及手动结束均加1，用于并发校验；聊天轮数按真实回复数计算
    private Boolean ended;
    private String messagesJson; // 本场原文及已执行交易；结束后作为压缩记忆来源，不直接带入后续上下文

    // 展示值从已保存的本轮回执计算，不需要新增数据库列。
    @TableField(exist = false)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    private int staminaCharacters;
    @TableField(exist = false)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    private int staminaCost;

    public int getStaminaCharacters() {
        if (messagesJson == null) {
            return 0;
        }
        // 只累计带逐轮扣款回执的新消息；旧消息不追扣，也不混入本轮计算。
        return JSONUtil.parseArray(messagesJson).toList(JSONObject.class).stream()
                .filter(message -> message.containsKey("staminaCost"))
                .mapToInt(message -> message.getInt("staminaCharacters", 0)).sum();
    }

    /** 本场已实际扣除的体力，供刷新恢复与后续差额计算使用。 */
    public int getStaminaCost() {
        if (messagesJson == null) {
            return 0;
        }
        return JSONUtil.parseArray(messagesJson).toList(JSONObject.class).stream()
                .mapToInt(message -> message.getInt("staminaCost", 0)).sum();
    }

    public int staminaCostAfter(int additionalCharacters) {
        int characters = getStaminaCharacters() + additionalCharacters;
        return characters == 0 ? 0 : Math.max(GameRuleConstant.DIALOGUE_MIN_STAMINA_COST,
                (characters + GameRuleConstant.DIALOGUE_CHARACTERS_PER_STAMINA - 1)
                        / GameRuleConstant.DIALOGUE_CHARACTERS_PER_STAMINA);
    }
}
