package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.ai.ClasspathJsonLoader;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.ExamEngine.ExamPreparation;
import mvp.engine.ExamEngine.ExamWeights;
import mvp.engine.ExamEngine;
import mvp.engine.TurnEngine;
import mvp.entity.ExamRecord;
import mvp.entity.GameSave;
import mvp.mapper.ExamRecordMapper;
import mvp.service.ExamRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ExamRecordServiceImpl extends ServiceImpl<ExamRecordMapper, ExamRecord> implements ExamRecordService {

    private static final String READY = "READY";
    private static final String AUTO = "AUTO";

    private final ExamEngine examEngine;
    private final TurnEngine turnEngine;
    private final Map<String, ExamDefinition> exams;

    /**
     * 加载各考试的唯一题目和计分权重，供整个运行周期复用。
     *
     * @param jsonLoader classpath JSON资源读取器
     * @param examEngine 考试数值计算引擎
     * @param turnEngine 考试年龄节点引擎
     */
    public ExamRecordServiceImpl(ClasspathJsonLoader jsonLoader, ExamEngine examEngine, TurnEngine turnEngine) {
        this.examEngine = examEngine;
        this.turnEngine = turnEngine;
        JSONArray definitions = jsonLoader.load("game/exam.json", JSONObject.class).getJSONArray("exam");
        Map<String, ExamDefinition> index = new LinkedHashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            String examType = definition.getStr("examType");
            JSONArray questions = definition.getJSONArray("question");
            if (questions == null || questions.size() != 1) {
                throw new IllegalStateException("MVP考试必须配置唯一题目：" + examType);
            }
            JSONObject question = questions.getJSONObject(0);
            JSONObject general = question.getJSONObject("generalAbilityWeight");
            JSONObject scholar = question.getJSONObject("careerAbilityWeight");
            ExamWeights weights = new ExamWeights(
                    general.getBigDecimal("characterZhili"),
                    general.getBigDecimal("characterDaode"),
                    general.getBigDecimal("characterZhengzhi"),
                    general.getBigDecimal("characterJiaoji"),
                    general.getBigDecimal("characterTineng"),
                    scholar.getBigDecimal("abilityShizi"),
                    scholar.getBigDecimal("abilityJingyi"),
                    scholar.getBigDecimal("abilityWenzhang"),
                    scholar.getBigDecimal("abilityCelun"),
                    scholar.getBigDecimal("abilityWenxue"),
                    question.getBigDecimal("knowledgeWeight")
            );
            ExamDefinition previous = index.put(examType, new ExamDefinition(
                    definition.getInt("triggerAge"),
                    definition.getBool("finalExam"),
                    question.getStr("questionText"),
                    question.getInt("passThreshold"),
                    weights
            ));
            if (previous != null) {
                throw new IllegalStateException("考试类型重复配置：" + examType);
            }
        }
        this.exams = Map.copyOf(index);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ExamRecord prepare(GameSave save, String characterId, CharacterState character,
                              ScholarState scholar, BigDecimal knowledgeTotal) {
        String examType = turnEngine.examTypeAtAge(save.getAge());
        requireExamStage(save, examType);
        ExamRecord existing = lambdaQuery()
                .eq(ExamRecord::getCharacterId, characterId)
                .eq(ExamRecord::getExamType, examType)
                .one();
        if (existing != null) {
            if (READY.equals(existing.getStatus())
                    && Objects.equals(existing.getSaveId(), save.getId())
                    && Objects.equals(existing.getTurnNumber(), save.getTotalTurnNumber())) {
                return existing;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此人物已存在该类型的考试记录");
        }
        ExamDefinition definition = exams.get(examType);
        if (definition == null || definition.triggerAge() != save.getAge()
                || definition.finalExam() != TurnEngine.EXAM_XIANSHI.equals(examType)) {
            throw new IllegalStateException("考试配置与年龄节点不一致：" + examType);
        }
        ExamPreparation preparation = examEngine.prepare(character, scholar, knowledgeTotal, definition.weights());
        int diceRoll = ThreadLocalRandom.current().nextInt(1, 101);
        ExamRecord exam = new ExamRecord()
                .setSaveId(save.getId())
                .setCharacterId(characterId)
                .setExamType(examType)
                .setQuestionText(definition.questionText())
                .setPassThreshold(definition.passThreshold())
                .setBaseAbilityScore(preparation.baseAbilityScore())
                .setStateOffset(preparation.stateOffset())
                .setKnowledgeTotal(knowledgeTotal).setDiceRoll(diceRoll).setLuckOffset(examEngine.luckOffset(diceRoll))
                .setStatus(READY)
                .setTurnNumber(save.getTotalTurnNumber());
        save(exam);
        return exam;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public AutoExamSettlement settleAuto(GameSave save, String characterId, String examId) {
        ExamRecord exam = getById(examId);
        if (exam == null || !Objects.equals(exam.getSaveId(), save.getId())
                || !Objects.equals(exam.getCharacterId(), characterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该考试记录");
        }
        boolean completed = "COMPLETED_PASS".equals(exam.getStatus())
                || "COMPLETED_FAIL".equals(exam.getStatus());
        if (completed && AUTO.equals(exam.getPlayerChoice())) {
            return new AutoExamSettlement(exam, false);
        }
        if (!READY.equals(exam.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该考试当前不能使用系统代行");
        }
        requireExamStage(save, exam.getExamType());
        if (!Objects.equals(exam.getTurnNumber(), save.getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试记录与当前存档回合不一致");
        }
        ExamEngine.ExamResult result = examEngine.settleAuto(
                exam.getBaseAbilityScore(), exam.getStateOffset(), exam.getDiceRoll(), exam.getLuckOffset(), exam.getPassThreshold()
        );
        boolean updated = lambdaUpdate()
                .eq(ExamRecord::getId, exam.getId())
                .eq(ExamRecord::getStatus, READY)
                .set(ExamRecord::getPlayerChoice, AUTO)
                .set(ExamRecord::getFinalScore, result.finalScore())
                .set(ExamRecord::getStatus, result.status())
                .update();
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试状态已发生变化，请重新读取存档");
        }
        exam.setPlayerChoice(AUTO).setFinalScore(result.finalScore()).setStatus(result.status());
        return new AutoExamSettlement(exam, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<ExamRecord> listForSave(String saveId) {
        return lambdaQuery().eq(ExamRecord::getSaveId, saveId).orderByAsc(ExamRecord::getTurnNumber).list();
    }

    /**
     * 确认考试类型与存档的待考阶段和年龄一致。
     *
     * @param save 当前存档
     * @param examType 准备创建或结算的考试类型
     */
    private void requireExamStage(GameSave save, String examType) {
        String expectedStatus = TurnEngine.EXAM_XIANSHI.equals(examType) ? "EXAM_READY" : "STAGE_EXAM_READY";
        if (examType == null || !examType.equals(turnEngine.examTypeAtAge(save.getAge()))
                || !expectedStatus.equals(save.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前存档不在该考试的待考阶段");
        }
    }

    private record ExamDefinition(int triggerAge, boolean finalExam, String questionText,
                                  int passThreshold, ExamWeights weights) {
    }
}
