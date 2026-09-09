package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.DialogueRecord;

public interface DialogueRecordService extends IService<DialogueRecord> {
    /**
     * 创建一场可由任一人物发起的对话，不调用模型或结算属性。
     *
     * @param saveId 存档ID
     * @param actorId 发起人物ID
     * @param command 请求编号、对方人物ID与场景
     * @return 对话ID及版本
     */
    JSONObject start(String saveId, String actorId, StartDialogueCommand command);

    /**
     * 发送消息或手动结束；AI可提前终止且第5轮必须返回结束标志，实际结算在短事务内。
     *
     * @param saveId 存档ID
     * @param dialogueId 对话ID
     * @param command 请求编号、原文、预期版本与手动结束标志
     * @return 回应、真实交易结果、是否结束及最新对话版本
     */
    JSONObject respond(String saveId, String dialogueId, DialogueCommand command);

    /**
     * 读取对话历史与当前版本，不调用模型。
     *
     * @param saveId 存档ID
     * @param dialogueId 对话ID
     * @return 已保存的对话状态
     */
    DialogueRecord loadDialogue(String saveId, String dialogueId);

    record StartDialogueCommand(String requestId, String counterpartId, String sceneCode) {
    }

    record DialogueCommand(String requestId, String text, Integer expectedVersion, boolean endDialogue) {
    }
}
