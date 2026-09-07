package mvp.controller;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import mvp.entity.ExamRecord;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.ExamResult;
import mvp.service.GameSaveService.PlayerExamCommand;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exam")
public class ExamController {

    private final GameSaveService gameSaveService;

    /** 首次生成并保存玩家思路，再次请求返回缓存；不交卷、不消耗回合。 */
    @PostMapping("/{saveId}/{examId}/thought")
    public ExamRecord prepareThought(@PathVariable String saveId, @PathVariable String examId) {
        return gameSaveService.prepareExamThought(saveId, examId);
    }

    /** 提交玩家原文；重传须沿用requestId和text，已结算的考试不能换答案或改用系统代行。 */
    @PostMapping("/{saveId}/{examId}/player")
    public JSONObject completePlayer(@PathVariable String saveId, @PathVariable String examId,
                                     @RequestBody PlayerExamCommand command) {
        return gameSaveService.completePlayerExam(saveId, examId, command);
    }

    /** 系统代行计分并生成AI答卷和总结；重传返回已保存结果，不重投骰点或重复结算。 */
    @PostMapping("/{saveId}/{examId}/auto")
    public ExamResult completeAuto(@PathVariable String saveId, @PathVariable String examId) {
        return gameSaveService.completeAutoExam(saveId, examId);
    }

    /** 玩家与NPC共用系统代行；characterId必须属于该考试，仅玩家考试切换存档阶段。 */
    @PostMapping("/{saveId}/actor/{characterId}/{examId}/auto")
    public ExamResult completeCharacterExam(@PathVariable String saveId, @PathVariable String characterId,
                                            @PathVariable String examId) {
        return gameSaveService.completeCharacterExam(saveId, characterId, examId);
    }
}
