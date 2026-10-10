package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.DialogueRecord;

public interface DialogueRecordService extends IService<DialogueRecord> {
    /**
     * 创建一场可由任一人物发起的对话，不调用模型或结算属性。
     *
     * @param command 请求编号、对方人物ID与场景
     * @return 对话ID及版本
     */
    JSONObject start(String saveId, String actorId, StartDialogueCommand command);

    /**
     * 每轮回复立即结算实际数值并扣体力；AI可提前结束且第5轮必须结束。手动结束免费且不再请求回复。
     *
     * @param command 请求编号、原文、预期版本与手动结束标志
     * @return 回应、真实交易结果、是否结束及最新对话版本
     */
    JSONObject respond(String saveId, String dialogueId, DialogueCommand command);

    /** 免费关闭对话，不再请求AI回复；保留各轮已扣体力、数值变化与交易，不重复结算。 */
    JSONObject abandon(String saveId, String dialogueId, AbandonDialogueCommand command);

    /**
     * 读取对话历史与当前版本，不调用模型。
     *
     * @return 已保存的对话状态
     */
    DialogueRecord loadDialogue(String saveId, String dialogueId);

    record StartDialogueCommand(String requestId, String counterpartId, String sceneCode) {
    }

    record DialogueCommand(String requestId, String text, Integer expectedVersion, boolean endDialogue) {
    }

    record AbandonDialogueCommand(String requestId, Integer expectedVersion) {
    }
}
