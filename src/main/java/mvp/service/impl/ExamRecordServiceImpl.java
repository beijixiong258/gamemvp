package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.ai.GameClient;
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
import mvp.utils.ClasspathJsonLoader;
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
    private static final String PLAYER = "PLAYER";

    private final ExamEngine examEngine;
    private final TurnEngine turnEngine;
    private final GameClient gameClient;
    private final Map<String, ExamDefinition> exams;

    /** 加载四场考试的唯一题目、计分权重、评分点和各阶段提示词编码。 */
    public ExamRecordServiceImpl(ClasspathJsonLoader jsonLoader, ExamEngine examEngine,
                                 TurnEngine turnEngine, GameClient gameClient) {
        this.examEngine = examEngine;
        this.turnEngine = turnEngine;
        this.gameClient = gameClient;
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
                    weights,
                    List.copyOf(question.getJSONArray("scoringPoint").toList(String.class)),
                    question.getStr("thoughtPromptCode"),
                    question.getStr("evaluationPromptCode"),
                    question.getStr("answerPromptCode")
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
        ExamDefinition definition = definitionFor(examType);
        if (definition.triggerAge() != save.getAge()
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
    public ExamRecord loadForCharacter(GameSave save, String characterId, String examId) {
        ExamRecord exam = getById(examId);
        if (exam == null || !Objects.equals(exam.getSaveId(), save.getId())
                || !Objects.equals(exam.getCharacterId(), characterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该人物的考试记录");
        }
        if (!completed(exam)) {
            requireReady(exam);
            requireExamStage(save, exam.getExamType());
            if (!Objects.equals(exam.getTurnNumber(), save.getTotalTurnNumber())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "考试记录与当前存档回合不一致");
            }
        }
        return exam;
    }

    /** {@inheritDoc} */
    @Override
    public String generateThought(ExamRecord exam, String characterContext) {
        if (exam.getAiThoughtBubble() != null && !exam.getAiThoughtBubble().isBlank()) {
            return exam.getAiThoughtBubble();
        }
        requireReady(exam);
        ThoughtOutput output = gameClient.chat(definitionFor(exam.getExamType()).thoughtPromptCode(),
                examContext(exam, characterContext).toString(), ThoughtOutput.class);
        return requireAiText(output.thought(), 3000, "作答思路");
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ExamRecord saveThought(GameSave save, String characterId, ExamRecord before, String thought) {
        ExamRecord exam = loadForCharacter(save, characterId, before.getId());
        if (exam.getAiThoughtBubble() != null && !exam.getAiThoughtBubble().isBlank()) {
            return exam;
        }
        requireReady(exam);
        requireSameSnapshot(before, exam);
        boolean updated = lambdaUpdate().eq(ExamRecord::getId, exam.getId())
                .eq(ExamRecord::getStatus, READY)
                .set(ExamRecord::getAiThoughtBubble, thought).update();
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试状态已发生变化，请重新读取存档");
        }
        return exam.setAiThoughtBubble(thought);
    }

    /** {@inheritDoc} */
    @Override
    public ExamResolution resolveAuto(ExamRecord exam, String characterContext) {
        if (completed(exam)) {
            return completedResolution(exam, AUTO, null);
        }
        requireReady(exam);
        ExamEngine.ExamResult result = examEngine.settleAuto(
                exam.getBaseAbilityScore(), exam.getStateOffset(), exam.getDiceRoll(),
                exam.getLuckOffset(), exam.getPassThreshold()
        );
        NarrativeOutput narrative = generateNarrative(exam, characterContext, AUTO, null, null, result);
        return new ExamResolution(AUTO, null, result,
                requireAiText(narrative.answerText(), 8000, "系统代行答卷"),
                requireAiText(narrative.summary(), 4000, "考试总结"));
    }

    /** {@inheritDoc} */
    @Override
    public ExamResolution resolvePlayer(ExamRecord exam, String playerInput, String characterContext) {
        if (playerInput == null || playerInput.isBlank() || playerInput.length() > 8000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交1至8000字符的答案");
        }
        if (completed(exam)) {
            return completedResolution(exam, PLAYER, playerInput);
        }
        requireReady(exam);
        JSONObject input = examContext(exam, characterContext).set("playerInput", playerInput);
        EvaluationOutput evaluation = gameClient.chat(definitionFor(exam.getExamType()).evaluationPromptCode(),
                input.toString(), EvaluationOutput.class);
        if (evaluation.contentModifier() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI未返回答案内容修正，请重新提交");
        }
        String comment = requireAiText(evaluation.evaluation(), 4000, "答案评价");
        ExamEngine.ExamResult result = examEngine.settlePlayer(
                exam.getBaseAbilityScore(), exam.getStateOffset(), evaluation.contentModifier(),
                exam.getDiceRoll(), exam.getLuckOffset(), exam.getPassThreshold()
        );
        NarrativeOutput narrative = generateNarrative(exam, characterContext, PLAYER, playerInput, comment, result);
        String summary = requireAiText(narrative.summary(), 4000, "考试总结");
        return new ExamResolution(PLAYER, playerInput, result, null, "内容评价：" + comment + "\n\n考试结果：" + summary);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public ExamSettlement settleResolved(GameSave save, String characterId, ExamRecord before, ExamResolution resolution) {
        ExamRecord exam = loadForCharacter(save, characterId, before.getId());
        if (completed(exam)) {
            completedResolution(exam, resolution.playerChoice(), resolution.playerInput());
            return new ExamSettlement(exam, false);
        }
        requireSameSnapshot(before, exam);
        ExamEngine.ExamResult result = resolution.result();
        boolean updated = lambdaUpdate()
                .eq(ExamRecord::getId, exam.getId()).eq(ExamRecord::getStatus, READY)
                .set(ExamRecord::getPlayerChoice, resolution.playerChoice())
                .set(ExamRecord::getPlayerInput, resolution.playerInput())
                .set(ExamRecord::getAiPlayerContentModifier, result.effectiveContentModifier())
                .set(ExamRecord::getFinalScore, result.finalScore())
                .set(ExamRecord::getStatus, result.status())
                .set(ExamRecord::getAiAnswerText, resolution.answerText())
                .set(ExamRecord::getAiContent, resolution.content())
                .update();
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试状态已发生变化，请重新读取存档");
        }
        exam.setPlayerChoice(resolution.playerChoice()).setPlayerInput(resolution.playerInput())
                .setAiPlayerContentModifier(result.effectiveContentModifier())
                .setFinalScore(result.finalScore()).setStatus(result.status())
                .setAiAnswerText(resolution.answerText()).setAiContent(resolution.content());
        return new ExamSettlement(exam, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<ExamRecord> listForSave(String saveId) {
        return lambdaQuery().eq(ExamRecord::getSaveId, saveId).orderByAsc(ExamRecord::getTurnNumber).list();
    }

    /** 思路与评价只接收能力和学习事实，不接收尚未公布的骰点及最终成绩。 */
    private JSONObject examContext(ExamRecord exam, String characterContext) {
        return new JSONObject()
                .set("examType", exam.getExamType()).set("questionText", exam.getQuestionText())
                .set("scoringPoints", definitionFor(exam.getExamType()).scoringPoints())
                .set("baseAbilityScore", exam.getBaseAbilityScore()).set("stateOffset", exam.getStateOffset())
                .set("knowledgeTotal", exam.getKnowledgeTotal())
                .set("thoughtLevel", examEngine.thoughtLevel(exam.getBaseAbilityScore() + exam.getStateOffset()).name())
                .set("characterFacts", JSONUtil.parseObj(characterContext));
    }

    /** 先取得Java计分结果，再请求展示文字；模型没有可回写成绩或通过状态的输出字段。 */
    private NarrativeOutput generateNarrative(ExamRecord exam, String characterContext, String choice,
                                              String playerInput, String evaluation, ExamEngine.ExamResult result) {
        JSONObject input = examContext(exam, characterContext).set("playerChoice", choice)
                .set("playerInput", playerInput).set("contentEvaluation", evaluation)
                .set("passThreshold", exam.getPassThreshold()).set("diceRoll", exam.getDiceRoll())
                .set("luckOffset", exam.getLuckOffset()).set("confirmedResult", result);
        return gameClient.chat(definitionFor(exam.getExamType()).answerPromptCode(), input.toString(), NarrativeOutput.class);
    }

    private ExamResolution completedResolution(ExamRecord exam, String choice, String playerInput) {
        if (!Objects.equals(choice, exam.getPlayerChoice())
                || !Objects.equals(playerInput, exam.getPlayerInput())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试已经结算，不能更换作答方式或答案");
        }
        ExamEngine.ExamResult result = new ExamEngine.ExamResult(exam.getFinalScore(),
                exam.getAiPlayerContentModifier() == null ? 0 : exam.getAiPlayerContentModifier(),
                "COMPLETED_PASS".equals(exam.getStatus()), exam.getStatus());
        return new ExamResolution(choice, playerInput, result, exam.getAiAnswerText(), exam.getAiContent());
    }

    private boolean completed(ExamRecord exam) {
        return "COMPLETED_PASS".equals(exam.getStatus()) || "COMPLETED_FAIL".equals(exam.getStatus());
    }

    private void requireReady(ExamRecord exam) {
        if (!READY.equals(exam.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该考试当前不在待考状态");
        }
    }

    private ExamDefinition definitionFor(String examType) {
        ExamDefinition definition = exams.get(examType);
        if (definition == null) {
            throw new IllegalStateException("未配置考试类型：" + examType);
        }
        return definition;
    }

    private String requireAiText(String text, int maxLength, String name) {
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI返回的" + name + "无效，请重新发起");
        }
        return text;
    }

    /** 思路缓存可独立写入；所有参与计分及题目解释的快照字段必须与调用前相同。 */
    private void requireSameSnapshot(ExamRecord before, ExamRecord current) {
        if (!Objects.equals(before.getExamType(), current.getExamType())
                || !Objects.equals(before.getTurnNumber(), current.getTurnNumber())
                || !Objects.equals(before.getQuestionText(), current.getQuestionText())
                || !Objects.equals(before.getPassThreshold(), current.getPassThreshold())
                || !Objects.equals(before.getBaseAbilityScore(), current.getBaseAbilityScore())
                || !Objects.equals(before.getStateOffset(), current.getStateOffset())
                || !Objects.equals(before.getKnowledgeTotal(), current.getKnowledgeTotal())
                || !Objects.equals(before.getDiceRoll(), current.getDiceRoll())
                || !Objects.equals(before.getLuckOffset(), current.getLuckOffset())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "考试快照已变化，本次AI结果未保存，请重新发起");
        }
    }

    private void requireExamStage(GameSave save, String examType) {
        String expectedStatus = TurnEngine.EXAM_XIANSHI.equals(examType) ? "EXAM_READY" : "STAGE_EXAM_READY";
        if (examType == null || !examType.equals(turnEngine.examTypeAtAge(save.getAge()))
                || !expectedStatus.equals(save.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前存档不在该考试的待考阶段");
        }
    }

    public record ThoughtOutput(String thought) {
    }

    public record EvaluationOutput(BigDecimal contentModifier, String evaluation) {
    }

    public record NarrativeOutput(String answerText, String summary) {
    }

    private record ExamDefinition(int triggerAge, boolean finalExam, String questionText,
                                  int passThreshold, ExamWeights weights, List<String> scoringPoints,
                                  String thoughtPromptCode, String evaluationPromptCode, String answerPromptCode) {
    }
}
