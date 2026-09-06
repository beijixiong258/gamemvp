package mvp.controller;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import mvp.entity.DialogueRecord;
import mvp.service.DialogueRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dialogue")
public class DialogueController {
    private final DialogueRecordService dialogueRecordService;

    /** 开始对话：actorId为玩家或NPC，command指定对象、场景和请求编号。 */
    @PostMapping("/{saveId}/actor/{actorId}/start")
    public JSONObject start(@PathVariable String saveId, @PathVariable String actorId,
                             @RequestBody DialogueRecordService.StartDialogueCommand command) {
        return dialogueRecordService.start(saveId, actorId, command);
    }

    /** 发送消息或结束整场对话；重传必须沿用requestId和expectedVersion。 */
    @PostMapping("/{saveId}/{dialogueId}/message")
    public JSONObject respond(@PathVariable String saveId, @PathVariable String dialogueId,
                               @RequestBody DialogueRecordService.DialogueCommand command) {
        return dialogueRecordService.respond(saveId, dialogueId, command);
    }

    /** 读取已经持久化的对话历史，不结算属性。 */
    @GetMapping("/{saveId}/{dialogueId}")
    public DialogueRecord load(@PathVariable String saveId, @PathVariable String dialogueId) {
        return dialogueRecordService.loadDialogue(saveId, dialogueId);
    }
}
