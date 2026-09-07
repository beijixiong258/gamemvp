package mvp.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

@Component
public class FreeActionResolver {

    private static final String PROMPT_CODE = "PROMPT_FREE_ACTION";

    private final GameClient gameClient;

    public FreeActionResolver(GameClient gameClient) {
        this.gameClient = gameClient;
    }

    /**
     * 把自由行动原文与业务层提供的事实解析为引擎驱动量和事件语义。
     *
     * @param playerText 玩家提交的自由行动原文
     * @param contextSummary 事实JSON，包含日期、人物属性与能力、玩家初始家庭背景、场景、书目和NPC
     * @return 供确定性引擎使用的驱动量和事件语义
     */
    public FreeActionResolution resolve(String playerText, String contextSummary) {
        String input = new JSONObject().set("currentText", playerText)
                .set("facts", JSONUtil.parseObj(contextSummary)).toString();
        return gameClient.chat(PROMPT_CODE, input, FreeActionResolution.class);
    }

    /**
     * 解析一轮对话；普通轮只返回回应和本轮行为，结束轮额外给出整场属性变化。
     *
     * @param context 当前事实、对话对象、完整历史、本轮原文和手动结束标志
     * @return 回应、结束决定、属性驱动量与本轮获取意图
     */
    public DialogueResolution resolveDialogue(String context) {
        return gameClient.chat("PROMPT_NPC_DIALOGUE", context, DialogueResolution.class);
    }

    public record DialogueResolution(String reply, boolean endDialogue, DriverPatch driverPatch,
                                     List<AcquisitionIntent> acquisitions) {
    }

    public record FreeActionResolution(
            DriverPatch driverPatch,
            String eventSummary,
            boolean lifeMilestone,
            List<AcquisitionIntent> acquisitions
    ) implements Serializable {
    }
}
