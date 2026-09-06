package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.ExamResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exam")
public class ExamController {

    private final GameSaveService gameSaveService;

    /**
     * 系统代行当前考试，并按阶段考试或县试恢复成长或结束存档。
     *
     * @param saveId 考试所属存档ID
     * @param examId 当前考试记录ID，重复提交返回已保存的考试结果
     * @return 考试结算与最新存档状态
     */
    @PostMapping("/{saveId}/{examId}/auto")
    public ExamResult completeAuto(@PathVariable("saveId") String saveId, @PathVariable("examId") String examId) {
        return gameSaveService.completeAutoExam(saveId, examId);
    }

    /** 玩家与NPC共用考试结算；characterId必须属于该考试。 */
    @PostMapping("/{saveId}/actor/{characterId}/{examId}/auto")
    public ExamResult completeCharacterExam(@PathVariable String saveId, @PathVariable String characterId,
                                            @PathVariable String examId) {
        return gameSaveService.completeCharacterExam(saveId, characterId, examId);
    }
}
