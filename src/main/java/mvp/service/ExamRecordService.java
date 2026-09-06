package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.entity.ExamRecord;
import mvp.entity.GameSave;

import java.math.BigDecimal;
import java.util.List;

public interface ExamRecordService extends IService<ExamRecord> {

    /**
     * 为刚进入待考阶段的存档创建固定考试快照；当前回合已有待考记录时复用。
     *
     * @param save 已推进到考试节点的存档
     * @param characterId 本次参加考试的人物ID
     * @param character 行动结算后的人物状态
     * @param scholar 行动结算后的书生领域能力
     * @param knowledgeTotal 行动结算后的全部书籍学识
     * @return 保存后的待考记录，题目、通过线、B/R、学识与骰点在此时固定
     */
    ExamRecord prepare(GameSave save, String characterId, CharacterState character,
                       ScholarState scholar, BigDecimal knowledgeTotal);

    /**
     * 使用考试快照结算系统代行，重复提交已经代行完成的考试时返回原结果。
     *
     * @param save 已由调用方锁定的当前存档
     * @param characterId 本次参加考试的人物ID
     * @param examId 待结算考试记录ID
     * @return 考试结果及本次是否首次完成；不推进时间或切换存档阶段
     */
    AutoExamSettlement settleAuto(GameSave save, String characterId, String examId);

    /**
     * 查询存档内的待考和历史考试记录。
     *
     * @param saveId 存档ID
     * @return 按考试发生回合升序排列的记录
     */
    List<ExamRecord> listForSave(String saveId);

    record AutoExamSettlement(ExamRecord exam, boolean newlySettled) {
    }
}
