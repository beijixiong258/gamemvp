package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.ExamEngine;
import mvp.entity.ExamRecord;
import mvp.entity.GameSave;

import java.math.BigDecimal;
import java.util.List;

public interface ExamRecordService extends IService<ExamRecord> {

    /**
     * 为刚进入待考阶段的存档创建固定考试快照；不调用模型。
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

    /** 核对考试归属；待考记录还须符合当前存档阶段与回合，已完成记录允许重读。 */
    ExamRecord loadForCharacter(GameSave save, String characterId, String examId);

    /** 在事务外生成角色水平对应的思路；已有思路直接复用，不展示骰点或最终成绩。 */
    String generateThought(ExamRecord exam, String characterContext);

    /** 在调用方的存档锁事务内保存思路；并发请求复用首先保存的结果，不结算考试。 */
    ExamRecord saveThought(GameSave save, String characterId, ExamRecord before, String thought);

    /** 在事务外由引擎计算系统代行成绩，再让AI生成相符的答卷和总结；已完成时不再调用AI。 */
    ExamResolution resolveAuto(ExamRecord exam, String characterContext);

    /** 在事务外评价玩家原文、由引擎计分，再生成结果总结；相同已交答案不重复评价。 */
    ExamResolution resolvePlayer(ExamRecord exam, String playerInput, String characterContext);

    /**
     * 在调用方的存档锁事务内核对原快照并保存全部考试结果，不切换存档阶段。
     *
     * @param save 已由调用方锁定的当前存档
     * @param characterId 本次参加考试的人物ID
     * @param before 模型调用前的考试快照
     * @param resolution 由引擎确定的成绩及AI展示内容
     * @return 已保存的考试及本次是否首次完成；不同作答方式或不同答案不能覆盖原结果
     */
    ExamSettlement settleResolved(GameSave save, String characterId, ExamRecord before, ExamResolution resolution);

    /** 查询存档内的待考和历史考试，按发生回合升序排列。 */
    List<ExamRecord> listForSave(String saveId);

    record ExamResolution(String playerChoice, String playerInput, ExamEngine.ExamResult result,
                          String answerText, String content) {
    }

    record ExamSettlement(ExamRecord exam, boolean newlySettled) {
    }
}
