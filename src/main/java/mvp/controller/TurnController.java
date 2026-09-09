package mvp.controller;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.FixedActionCommand;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/turn")
public class TurnController {

    private final GameSaveService gameSaveService;

    /**
     * 提交读书、练习文章或休息，并返回本回合完整结算。
     *
     * @param command 稳定请求编号、行动编码、场景、可选书籍及预期累计回合
     * @return 行动后的状态与实际变化
     */
    @PostMapping("/{saveId}/action")
    public JSONObject executeFixedAction(
            @PathVariable String saveId,
            @RequestBody FixedActionCommand command
    ) {
        return gameSaveService.executeFixedAction(saveId, command);
    }

    /** 玩家与NPC共用固定行动；characterId指定本次行动的人物。 */
    @PostMapping("/{saveId}/actor/{characterId}/action")
    public JSONObject executeCharacterAction(@PathVariable String saveId, @PathVariable String characterId,
                                             @RequestBody FixedActionCommand command) {
        return gameSaveService.executeCharacterAction(saveId, characterId, command);
    }

    /** 执行自定义行动；command含原文、场景、预期回合和重传不变的requestId。 */
    @PostMapping("/{saveId}/actor/{characterId}/free")
    public JSONObject executeFreeAction(@PathVariable String saveId, @PathVariable String characterId,
                                        @RequestBody GameSaveService.FreeActionCommand command) {
        return gameSaveService.executeFreeAction(saveId, characterId, command);
    }
}
