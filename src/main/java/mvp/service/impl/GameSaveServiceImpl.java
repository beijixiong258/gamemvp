package mvp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import mvp.ai.FreeActionWorkflow;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.CharacterEngine;
import mvp.engine.GameRuleConstant;
import mvp.engine.TurnEngine;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.ExamRecord;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.mapper.GameSaveMapper;
import mvp.service.BookService.LibraryBook;
import mvp.service.BookService;
import mvp.service.CareerProfileShushengService;
import mvp.service.CharacterService;
import mvp.service.EquipmentRecordService.AcquisitionCommand;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EquipmentRecordService;
import mvp.service.EventRecordService;
import mvp.service.ExamRecordService;
import mvp.service.FamilyBackgroundService;
import mvp.service.GameSaveService;
import mvp.service.MemoryRecordService;
import mvp.service.RegionService;
import mvp.utils.ClasspathJsonLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameSaveServiceImpl extends ServiceImpl<GameSaveMapper, GameSave> implements GameSaveService {

    private static final int PLAYER_CHARACTER_TYPE = 1;
    private static final String INITIAL_AVAILABLE_STAGE_JSON = "[\"STUDYING\"]";
    private static final String BOOK_QUESTION_PREFIX = "BOOK_PLAYER_QUESTION/";
    private static final String BOOK_ANSWER_PREFIX = "BOOK_PLAYER_ANSWER/";

    private final CharacterEngine characterEngine;
    private final TurnEngine turnEngine;
    private final CharacterService characterService;
    private final FamilyBackgroundService familyBackgroundService;
    private final CareerProfileShushengService careerProfileShushengService;
    private final RegionService regionService;
    private final BookService bookService;
    private final EquipmentRecordService equipmentRecordService;
    private final FreeActionWorkflow freeActionWorkflow;
    private final PlatformTransactionManager transactionManager;
    private final ExamRecordService examRecordService;
    private final EventRecordService eventRecordService;
    private final MemoryRecordService memoryRecordService;
    private final ClasspathJsonLoader jsonLoader;

    private Map<String, JSONObject> actions;
    private Map<String, String> feedbackTemplates;

    /** 加载行动路由和确定性反馈模板，不访问数据库。 */
    @PostConstruct
    private void loadActionRules() {
        Map<String, JSONObject> loadedActions = new HashMap<>();
        for (JSONObject action : jsonLoader.load("game/action.json", JSONObject.class)
                .getJSONArray("action").toList(JSONObject.class)) {
            loadedActions.put(action.getStr("actionCode"), action);
        }
        actions = Map.copyOf(loadedActions);

        Map<String, String> loadedTemplates = new HashMap<>();
        for (JSONObject text : jsonLoader.load("game/text.json", JSONObject.class)
                .getJSONArray("text").toList(JSONObject.class)) {
            loadedTemplates.put(text.getStr("textCode"), text.getStr("template"));
        }
        feedbackTemplates = Map.copyOf(loadedTemplates);
    }

    @Override
    public List<Region> listBirthRegions() {
        return regionService.listMvpBirthRegions();
    }

    @Override
    @Transactional
    public StartLifeResult startLife(StartLifeCommand command) {
        if (command == null || command.characterName() == null || command.characterName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写人物姓名");
        }
        String characterName = command.characterName().strip();
        int nameLength = characterName.codePointCount(0, characterName.length());
        if (nameLength < 2 || nameLength > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人物姓名须为2至8个字符");
        }
        Region birthRegion = listBirthRegions().stream()
                .filter(region -> Objects.equals(region.getId(), command.birthRegionId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "出生地区不在MVP可选范围内"
                ));

        CharacterEngine.StartLifeResult characterResult = characterEngine.startLife(birthRegion.getId());
        TurnEngine.TurnState turnState = turnEngine.startLife();

        GameSave gameSave = new GameSave().setCreatedAt(LocalDateTime.now());
        applyTurnState(gameSave, turnState);
        save(gameSave);

        Character character = new Character()
                .setSaveId(gameSave.getId())
                .setName(characterName)
                .setType(PLAYER_CHARACTER_TYPE)
                .setWallet(GameRuleConstant.INITIAL_WALLET).setSickTurnsRemaining(0).setTitlesJson("[]")
                .setBirthRegionId(characterResult.birthRegionId())
                .setCurrentRegionId(characterResult.currentRegionId())
                .setBirthday(turnState.birthYear() + "-01-01")
                .setAvailableStageCodeJson(INITIAL_AVAILABLE_STAGE_JSON)
                .setEnabled(true);
        applyCharacterState(character, characterResult.character());
        characterService.save(character);

        FamilyBackground familyBackground = new FamilyBackground()
                .setSaveId(gameSave.getId())
                .setInitialWealth(characterResult.initialFamilyWealth())
                .setBackgroundSummary(characterResult.familyBackgroundSummary());
        familyBackgroundService.save(familyBackground);

        CharacterEngine.ScholarState scholarState = characterResult.scholar();
        CareerProfileShusheng scholarProfile = new CareerProfileShusheng()
                .setCharacterId(character.getId())
                .setUnlockTurnNumber(turnState.totalTurnNumber())
                .setLastActiveTurnNumber(turnState.totalTurnNumber());
        applyScholarState(scholarProfile, scholarState);
        careerProfileShushengService.save(scholarProfile);
        bookService.importDefinitions();
        createNpcs(gameSave, character, scholarState);

        return new StartLifeResult(
                gameSave,
                character,
                familyBackground,
                scholarProfile
        );
    }

    @Override
    @Transactional(readOnly = true)
    public SaveDetail loadDetail(String saveId) {
        return buildDetail(loadPlayer(saveId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LibraryBook> listBooks(String saveId) {
        ActorContext context = loadPlayer(saveId, false);
        return bookService.listLibrary(
                context.save(), context.character().getId(),
                characterState(context.character()), scholarState(context.scholar())
        );
    }

    @Override
    public JSONObject preparePlayerReading(String saveId, String bookCode, PlayerReadingQuestionCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少出题参数");
        }
        PlayerReadingAttempt before = new TransactionTemplate(transactionManager).execute(status ->
                playerReadingAttempt(loadPlayer(saveId, true), bookCode, command.sceneCode(), command.expectedTurnNumber()));
        String questionId = BOOK_QUESTION_PREFIX + before.actorId() + "/" + before.book().equipmentId() + "/" + before.turnNumber();
        JSONObject payload = new JSONObject().set("operation", "BOOK_PLAYER_QUESTION")
                .set("bookCode", bookCode).set("actorId", before.actorId()).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, questionId, payload);
        if (previous != null) {
            return previous;
        }
        String question = bookService.generatePlayerReadingQuestion(before.book(), before.characterContext());
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadPlayer(saveId, true);
            JSONObject replayed = eventRecordService.replay(saveId, questionId, payload);
            if (replayed != null) {
                return replayed;
            }
            verifyPlayerReadingSnapshot(context, before);
            JSONObject result = new JSONObject().set("questionId", questionId).set("actorId", before.actorId())
                    .set("bookCode", bookCode).set("bookName", before.book().bookName())
                    .set("sceneCode", before.sceneCode()).set("turnNumber", before.turnNumber())
                    .set("currentProgress", before.book().currentProgress()).set("question", question);
            eventRecordService.recordOperation(saveId, before.actorId(), questionId, payload,
                    "BOOK_PLAYER_QUESTION", before.turnNumber(), result);
            return result;
        });
    }

    @Override
    public JSONObject completePlayerReading(String saveId, String bookCode, PlayerReadingAnswerCommand command) {
        if (command == null || command.questionId() == null || !command.questionId().startsWith(BOOK_QUESTION_PREFIX)
                || command.questionId().length() > 120 || command.text() == null || command.text().isBlank()
                || command.text().length() > 8000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供有效的questionId和1至8000字符的读书体会");
        }
        PlayerReadingSubmission submission = new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadPlayer(saveId, true);
            JSONObject question = loadPlayerReadingQuestion(context, bookCode, command.questionId());
            String requestId = BOOK_ANSWER_PREFIX + command.questionId().substring(BOOK_QUESTION_PREFIX.length());
            JSONObject payload = new JSONObject().set("operation", "BOOK_PLAYER_ANSWER")
                    .set("bookCode", bookCode).set("command", command);
            JSONObject previous = eventRecordService.replay(saveId, requestId, payload);
            if (previous != null) {
                return new PlayerReadingSubmission(null, question, payload, requestId, previous);
            }
            PlayerReadingAttempt before = playerReadingAttempt(context, bookCode, question.getStr("sceneCode"),
                    question.getLong("turnNumber"));
            if (!Objects.equals(before.book().currentProgress(), question.getInt("currentProgress"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "阅读进度已变化，请重新出题");
            }
            return new PlayerReadingSubmission(before, question, payload, requestId, null);
        });
        if (submission.previous() != null) {
            return submission.previous();
        }
        PlayerReadingAttempt before = submission.attempt();
        BookService.PlayerReadingEvaluation evaluation = bookService.evaluatePlayerReading(
                before.book(), submission.question().getStr("question"), command.text());
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadPlayer(saveId, true);
            JSONObject replayed = eventRecordService.replay(saveId, submission.requestId(), submission.payload());
            if (replayed != null) {
                return replayed;
            }
            verifyPlayerReadingSnapshot(context, before);
            TurnEngine.TurnResult turn = turnEngine.advance(turnState(context.save()), true);
            BookService.BookActionResult result = bookService.readAsPlayer(context.save(), before.actorId(),
                    before.character(), before.scholar(), bookCode, turn.state().totalTurnNumber(), evaluation.score());
            CharacterEngine.ReadBookResult reading = result.settlement();
            applyCharacterState(context.character(), reading.character());
            characterService.updateById(context.character());
            applyScholarState(context.scholar(), reading.scholar());
            context.scholar().setLastActiveTurnNumber(turn.state().totalTurnNumber());
            careerProfileShushengService.updateById(context.scholar());
            applyTurnState(context.save(), turn.state());
            updateById(context.save());
            if (reading.reachedMastered()) {
                recordMilestone(context, "BOOK_MASTERED", "你已掌握《" + result.bookName() + "》。",
                        Map.of("bookCode", bookCode, "currentProgress", reading.progress().currentProgress(),
                                "progressGain", reading.progressGain(), "score", evaluation.score()));
            }
            markIllness(context);
            prepareTriggeredExam(context, turn);
            advanceIllness(context);
            CharacterState after = characterState(context.character());
            ActionChanges changes = new ActionChanges(reading.progressGain(), reading.abilityGain(),
                    after.characterPilao() - before.character().characterPilao(),
                    after.characterJiankang() - before.character().characterJiankang(), null);
            JSONObject response = JSONUtil.parseObj(new ActionResult(buildDetail(context), changes,
                    "你写下了对《" + result.bookName() + "》的体会，阅读进度增加" + reading.progressGain() + "。"))
                    .set("questionId", command.questionId()).set("bookCode", bookCode)
                    .set("score", evaluation.score()).set("evaluation", evaluation.evaluation());
            eventRecordService.recordOperation(saveId, before.actorId(), submission.requestId(), submission.payload(),
                    "BOOK_PLAYER_ANSWER", context.save().getTotalTurnNumber(), response);
            return response;
        });
    }

    /** 只读快照由短事务内的存档锁保护，模型调用不占用锁。 */
    private PlayerReadingAttempt playerReadingAttempt(ActorContext context, String bookCode, String sceneCode,
                                                       Long expectedTurnNumber) {
        if (!"STUDYING".equals(context.save().getStatus()) || context.character().getCharacterJiankang() <= 0
                || context.character().getSickTurnsRemaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成考试或重病休养，再以身入局读书");
        }
        if (expectedTurnNumber == null || !Objects.equals(expectedTurnNumber, context.save().getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回合已变化，请刷新存档后重新出题");
        }
        if (!actions.get("READ_BOOK_PLAYER").getJSONArray("availableSceneCode").contains(sceneCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景不能读书");
        }
        CharacterState character = characterState(context.character());
        ScholarState scholar = scholarState(context.scholar());
        LibraryBook book = bookService.requirePlayerReadingBook(context.save(), context.character().getId(),
                character, scholar, bookCode);
        String characterContext = new JSONObject().set("name", context.character().getName())
                .set("age", actorAge(context))
                .set("character", character).set("scholar", scholar)
                .set("scene", requireScene(sceneCode, context.character(), "READ_BOOK_PLAYER"))
                .set("teacher", characterService.lambdaQuery().eq(Character::getSaveId, context.save().getId())
                        .eq(Character::getNpcCode, "NPC_XIANSHENG").eq(Character::getEnabled, true).one()).toString();
        return new PlayerReadingAttempt(context.character().getId(), expectedTurnNumber, sceneCode,
                character, scholar, book, characterContext);
    }

    private void verifyPlayerReadingSnapshot(ActorContext context, PlayerReadingAttempt before) {
        PlayerReadingAttempt current = playerReadingAttempt(context, before.book().bookCode(), before.sceneCode(),
                before.turnNumber());
        if (!before.equals(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人物或书籍状态已变化，本次AI结果未结算，请重试");
        }
    }

    private JSONObject loadPlayerReadingQuestion(ActorContext context, String bookCode, String questionId) {
        EventRecord record = eventRecordService.lambdaQuery().eq(EventRecord::getSaveId, context.save().getId())
                .eq(EventRecord::getRequestId, questionId).eq(EventRecord::getEventCode, "BOOK_PLAYER_QUESTION").one();
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "读书题目不存在，请先出题");
        }
        JSONObject question = JSONUtil.parseObj(record.getSettlementResultJson());
        if (!Objects.equals(bookCode, question.getStr("bookCode"))
                || !Objects.equals(context.character().getId(), question.getStr("actorId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这道题不属于当前玩家或书籍");
        }
        return question;
    }

    @Override
    @Transactional
    public JSONObject executeFixedAction(String saveId, FixedActionCommand command) {
        return executeCharacterAction(saveId, null, command);
    }

    @Override
    @Transactional
    public JSONObject executeCharacterAction(String saveId, String actorId, FixedActionCommand command) {
        ActorContext context = loadActor(saveId, actorId, true);
        GameSave gameSave = context.save();
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少行动参数");
        }
        JSONObject payload = new JSONObject().set("operation", "FIXED_ACTION").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        if (!"STUDYING".equals(gameSave.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成当前考试；已结束的存档不能继续行动");
        }
        if (command.expectedTurnNumber() == null
                || !Objects.equals(command.expectedTurnNumber(), gameSave.getTotalTurnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回合已变化，请重新读取存档后提交行动");
        }
        if (context.character().getCharacterJiankang() <= 0 || context.character().getSickTurnsRemaining() > 0) {
            CharacterState before = characterState(context.character());
            advanceIllness(context);
            CharacterState after = characterState(context.character());
            JSONObject result = JSONUtil.parseObj(new ActionResult(buildDetail(context), new ActionChanges(0,
                    abilityDifference(scholarState(context.scholar()), scholarState(context.scholar())),
                    after.characterPilao() - before.characterPilao(), after.characterJiankang() - before.characterJiankang(), null),
                    "重病期间只能休养，已强制推进至康复或下一个考试节点。"));
            eventRecordService.recordOperation(saveId, context.character().getId(), command.requestId(), payload,
                    "SICK_RECOVERY", gameSave.getTotalTurnNumber(), result);
            return result;
        }
        JSONObject action = command.actionCode() == null ? null : actions.get(command.actionCode());
        if (action == null || !"FIXED_ACTION".equals(action.getStr("actionType"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前接口只支持读书、练习文章和休息");
        }
        if (!action.getJSONArray("availableSceneCode").contains(command.sceneCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景不能执行该行动");
        }
        requireScene(command.sceneCode(), context.character(), command.actionCode());

        CharacterState characterBefore = characterState(context.character());
        ScholarState scholarBefore = scholarState(context.scholar());
        TurnEngine.TurnResult turnResult = turnEngine.advance(
                turnState(gameSave), action.getBool("endTurn")
        );
        long settlementTurnNumber = turnResult.state().totalTurnNumber();
        CharacterState characterAfter;
        ScholarState scholarAfter;
        CharacterEngine.ReadBookResult reading = null;
        String bookName = null;
        Map<String, String> feedbackValues;
        switch (action.getStr("engineRuleCode")) {
            case "RULE_READ_BOOK" -> {
                BookService.BookActionResult result = bookService.read(
                        gameSave, context.character().getId(), characterBefore, scholarBefore,
                        command.bookCode(), settlementTurnNumber
                );
                reading = result.settlement();
                bookName = result.bookName();
                characterAfter = reading.character();
                scholarAfter = reading.scholar();
                feedbackValues = Map.of(
                        "bookName", bookName,
                        "progressDelta", String.valueOf(reading.progressGain()),
                        "fatigueDelta", String.valueOf(reading.fatigueGain())
                );
            }
            case "RULE_PRACTICE_WRITING" -> {
                CharacterEngine.PracticeWritingResult result = characterEngine.practiceWriting(
                        characterBefore, scholarBefore
                );
                characterAfter = result.character();
                scholarAfter = result.scholar();
                feedbackValues = Map.of(
                        "abilityDelta", String.valueOf(result.writingAbilityGain()),
                        "fatigueDelta", String.valueOf(result.fatigueGain())
                );
            }
            case "RULE_REST" -> {
                CharacterEngine.RestResult result = characterEngine.rest(characterBefore);
                characterAfter = result.character();
                scholarAfter = scholarBefore;
                feedbackValues = Map.of(
                        "fatigueDelta", String.valueOf(result.fatigueRecovery()),
                        "healthDelta", String.valueOf(result.healthRecovery())
                );
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "固定行动规则尚未实现");
        }

        applyCharacterState(context.character(), characterAfter);
        characterService.updateById(context.character());
        if (!"RULE_REST".equals(action.getStr("engineRuleCode"))) {
            applyScholarState(context.scholar(), scholarAfter);
            context.scholar().setLastActiveTurnNumber(settlementTurnNumber);
            careerProfileShushengService.updateById(context.scholar());
        }
        applyTurnState(gameSave, turnResult.state());
        updateById(gameSave);

        ActionChanges changes = new ActionChanges(
                reading == null ? 0 : reading.progressGain(),
                abilityDifference(scholarBefore, scholarAfter),
                characterAfter.characterPilao() - characterBefore.characterPilao(),
                characterAfter.characterJiankang() - characterBefore.characterJiankang(),
                reading == null ? null : reading.diceRoll()
        );
        if (reading != null) {
            Map<String, Object> settlement = Map.of(
                    "bookCode", command.bookCode(),
                    "currentProgress", reading.progress().currentProgress(),
                    "progressGain", reading.progressGain(),
                    "diceRoll", reading.diceRoll(),
                    "character", context.character(),
                    "scholarProfile", context.scholar()
            );
            if (reading.reachedMastered()) {
                recordMilestone(context, "BOOK_MASTERED", "你已掌握《" + bookName + "》。", settlement);
            }
        }
        markIllness(context);
        prepareTriggeredExam(context, turnResult);
        advanceIllness(context);
        CharacterState finalCharacter = characterState(context.character());
        changes = new ActionChanges(changes.progressGain(), changes.abilityGain(),
                finalCharacter.characterPilao() - characterBefore.characterPilao(),
                finalCharacter.characterJiankang() - characterBefore.characterJiankang(), changes.diceRoll());
        JSONObject result = JSONUtil.parseObj(new ActionResult(
                buildDetail(context), changes,
                renderFeedback(action.getStr("feedbackTextCode"), feedbackValues)
        ));
        eventRecordService.recordOperation(saveId, context.character().getId(), command.requestId(), payload,
                command.actionCode(), gameSave.getTotalTurnNumber(), result);
        return result;
    }

    @Override
    public ExamRecord prepareExamThought(String saveId, String examId) {
        ExamAttempt before = prepareExamAttempt(saveId, null, examId);
        String thought = examRecordService.generateThought(before.exam(), before.characterContext());
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadPlayer(saveId, true);
            return examRecordService.saveThought(context.save(), context.character().getId(), before.exam(), thought);
        });
    }

    @Override
    public ExamResult completeAutoExam(String saveId, String examId) {
        return completeCharacterExam(saveId, null, examId);
    }

    @Override
    public ExamResult completeCharacterExam(String saveId, String actorId, String examId) {
        ExamAttempt before = prepareExamAttempt(saveId, actorId, examId);
        ExamRecordService.ExamResolution resolution = examRecordService.resolveAuto(before.exam(), before.characterContext());
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadActor(saveId, actorId, true);
            ExamRecordService.ExamSettlement settlement = examRecordService.settleResolved(
                    context.save(), context.character().getId(), before.exam(), resolution);
            return finishExam(context, settlement);
        });
    }

    @Override
    public JSONObject completePlayerExam(String saveId, String examId, PlayerExamCommand command) {
        if (command == null || command.text() == null || command.text().isBlank() || command.text().length() > 8000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交1至8000字符的答案");
        }
        JSONObject payload = new JSONObject().set("operation", "EXAM_PLAYER").set("examId", examId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        ExamAttempt before = prepareExamAttempt(saveId, null, examId);
        ExamRecordService.ExamResolution resolution = examRecordService.resolvePlayer(
                before.exam(), command.text(), before.characterContext());
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadPlayer(saveId, true);
            JSONObject replayed = eventRecordService.replay(saveId, command.requestId(), payload);
            if (replayed != null) {
                return replayed;
            }
            ExamRecordService.ExamSettlement settlement = examRecordService.settleResolved(
                    context.save(), context.character().getId(), before.exam(), resolution);
            JSONObject result = JSONUtil.parseObj(finishExam(context, settlement));
            eventRecordService.recordOperation(saveId, context.character().getId(), command.requestId(), payload,
                    "EXAM_PLAYER", context.save().getTotalTurnNumber(), result);
            return result;
        });
    }

    /** 短事务内取得一致的考试与人物事实；返回后才允许发起模型请求。 */
    private ExamAttempt prepareExamAttempt(String saveId, String actorId, String examId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActorContext context = loadActor(saveId, actorId, true);
            ExamRecord exam = examRecordService.loadForCharacter(context.save(), context.character().getId(), examId);
            if (!"READY".equals(exam.getStatus())) {
                return new ExamAttempt(exam, "{}");
            }
            CharacterState character = characterState(context.character());
            ScholarState scholar = scholarState(context.scholar());
            List<JSONObject> learnedBooks = bookService.listLibrary(context.save(), context.character().getId(), character, scholar)
                    .stream().filter(book -> book.currentProgress() > 0)
                    .map(book -> new JSONObject().set("bookName", book.bookName())
                            .set("currentProgress", book.currentProgress()).set("requiredProgress", book.requiredProgress())
                            .set("completed", book.completed()).set("knowledgeSummary", book.knowledgeSummary()))
                    .toList();
            String facts = new JSONObject().set("characterName", context.character().getName())
                    .set("currentYear", context.save().getCurrentYear())
                    .set("age", actorAge(context))
                    .set("character", character).set("scholar", scholar).set("learnedBooks", learnedBooks).toString();
            return new ExamAttempt(exam, facts);
        });
    }

    /** 两种作答方式共用阶段切换、领域活跃记录及重病恢复，只在首次结算时执行。 */
    private ExamResult finishExam(ActorContext context, ExamRecordService.ExamSettlement settlement) {
        ExamRecord exam = settlement.exam();
        String feedback = renderFeedback(
                "COMPLETED_PASS".equals(exam.getStatus()) ? "TEXT_EXAM_PASS" : "TEXT_EXAM_FAIL", Map.of()
        );
        if (settlement.newlySettled()) {
            boolean playerExam = context.character().getType() == PLAYER_CHARACTER_TYPE;
            if (playerExam) {
                applyTurnState(context.save(), turnEngine.completeExam(turnState(context.save()), exam.getExamType()));
                updateById(context.save());
            }
            context.scholar().setLastActiveTurnNumber(context.save().getTotalTurnNumber());
            careerProfileShushengService.updateById(context.scholar());
            recordMilestone(context, exam.getExamType() + "_RESULT", feedback, Map.of("exam", exam));
            if (playerExam) {
                advanceIllness(context);
            }
        }
        return new ExamResult(buildDetail(context), exam, settlement.newlySettled(), feedback);
    }

    /**
     * 加载存档及其玩家、书生档案；写入时先锁定存档行，使同一存档的结算串行执行。
     *
     * @param forUpdate 是否在当前事务内取得存档行锁
     * @return 本次请求共用的持久化对象
     */
    private ActorContext loadPlayer(String saveId, boolean forUpdate) {
        return loadActor(saveId, null, forUpdate);
    }

    /**
     * 按同一套规则加载玩家或NPC及其书生档案。
     *
     * @param actorId 行动人物ID，空时选择玩家
     * @param forUpdate 是否在当前事务内锁定存档
     * @return 本次行动的人物、领域与存档上下文
     */
    private ActorContext loadActor(String saveId, String actorId, boolean forUpdate) {
        GameSave gameSave = forUpdate
                ? lambdaQuery().eq(GameSave::getId, saveId).last("FOR UPDATE").one()
                : getById(saveId);
        if (gameSave == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        Character character = characterService.lambdaQuery()
                .eq(Character::getSaveId, saveId)
                .eq(actorId == null, Character::getType, PLAYER_CHARACTER_TYPE)
                .eq(actorId != null, Character::getId, actorId)
                .eq(Character::getEnabled, true)
                .one();
        if (character == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存档中不存在可用的行动人物");
        }
        CareerProfileShusheng scholar = careerProfileShushengService.lambdaQuery()
                .eq(CareerProfileShusheng::getCharacterId, character.getId())
                .one();
        if (scholar == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存档缺少书生领域档案");
        }
        return new ActorContext(gameSave, character, scholar);
    }

    /**
     * 组装供页面展示和继续操作的存档详情，读取书库时不会创建进度或补发物品。
     *
     * @param context 已加载的本次请求上下文
     * @return 当前状态、全部考试和已发生的人生节点
     */
    private SaveDetail buildDetail(ActorContext context) {
        String saveId = context.save().getId();
        return new SaveDetail(
                context.save(), context.character(),
                familyBackgroundService.lambdaQuery().eq(FamilyBackground::getSaveId, saveId).one(),
                context.scholar(),
                bookService.listLibrary(
                        context.save(), context.character().getId(),
                        characterState(context.character()), scholarState(context.scholar())
                ),
                examRecordService.listForSave(saveId),
                eventRecordService.lambdaQuery()
                        .eq(EventRecord::getSaveId, saveId)
                        .eq(EventRecord::getLifeMilestone, true)
                        .orderByAsc(EventRecord::getOccurredTurnNumber)
                        .orderByAsc(EventRecord::getId)
                        .list(),
                bookService.totalKnowledge(context.character().getId()),
                equipmentRecordService.backpack(saveId, context.character().getId()),
                characterService.lambdaQuery().eq(Character::getSaveId, saveId).eq(Character::getType, 0).list()
        );
    }

    /**
     * 保存已经结算成立的人生节点，普通回合不调用此方法。
     *
     * @param context 已更新为行动后状态的上下文
     * @param summary 由规则或模板产生的事实摘要
     * @param settlement 已完成的数值结算，不包含模型推测
     */
    private void recordMilestone(
            ActorContext context, String eventCode, String summary, Map<String, ?> settlement
    ) {
        eventRecordService.save(new EventRecord()
                .setSaveId(context.save().getId())
                .setEventCode(eventCode)
                .setEventSummary(summary)
                .setRelatedCharacterIdJson(JSONUtil.toJsonStr(List.of(context.character().getId())))
                .setOccurredTurnNumber(context.save().getTotalTurnNumber())
                .setSettlementResultJson(JSONUtil.toJsonStr(settlement))
                .setLifeMilestone(true));
    }

    private String renderFeedback(String textCode, Map<String, String> values) {
        String template = feedbackTemplates.get(textCode);
        if (template == null) {
            throw new IllegalStateException("未配置反馈模板：" + textCode);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    private int actorAge(ActorContext context) {
        return context.save().getCurrentYear() - LocalDate.parse(context.character().getBirthday()).getYear();
    }

    private CharacterState characterState(Character character) {
        return new CharacterState(
                character.getCharacterZhili(), character.getCharacterDaode(), character.getCharacterZhengzhi(),
                character.getCharacterJiaoji(), character.getCharacterTineng(),
                character.getCharacterJiankang(), character.getCharacterPilao()
        );
    }

    private ScholarState scholarState(CareerProfileShusheng scholar) {
        return new ScholarState(
                scholar.getAbilityShizi(), scholar.getAbilityJingyi(), scholar.getAbilityWenzhang(),
                scholar.getAbilityCelun(), scholar.getAbilityWenxue()
        );
    }

    private TurnEngine.TurnState turnState(GameSave gameSave) {
        return new TurnEngine.TurnState(
                gameSave.getBirthYear(), gameSave.getCurrentYear(), gameSave.getCurrentMonth(),
                gameSave.getTurnInMonth(), gameSave.getAge(), gameSave.getTotalTurnNumber(),
                gameSave.getStatus(), gameSave.getGrowthStage()
        );
    }

    private void applyCharacterState(Character character, CharacterState state) {
        character.setCharacterZhili(state.characterZhili())
                .setCharacterDaode(state.characterDaode())
                .setCharacterZhengzhi(state.characterZhengzhi())
                .setCharacterJiaoji(state.characterJiaoji())
                .setCharacterTineng(state.characterTineng())
                .setCharacterJiankang(state.characterJiankang())
                .setCharacterPilao(state.characterPilao());
    }

    private void applyScholarState(CareerProfileShusheng scholar, ScholarState state) {
        scholar.setAbilityShizi(state.abilityShizi())
                .setAbilityJingyi(state.abilityJingyi())
                .setAbilityWenzhang(state.abilityWenzhang())
                .setAbilityCelun(state.abilityCelun())
                .setAbilityWenxue(state.abilityWenxue());
    }

    private void applyTurnState(GameSave gameSave, TurnEngine.TurnState state) {
        gameSave.setBirthYear(state.birthYear())
                .setCurrentYear(state.currentYear())
                .setCurrentMonth(state.currentMonth())
                .setTurnInMonth(state.turnInMonth())
                .setAge(state.age())
                .setTotalTurnNumber(state.totalTurnNumber())
                .setStatus(state.status())
                .setGrowthStage(state.growthStage());
    }

    private ScholarState abilityDifference(ScholarState before, ScholarState after) {
        return new ScholarState(
                after.abilityShizi() - before.abilityShizi(),
                after.abilityJingyi() - before.abilityJingyi(),
                after.abilityWenzhang() - before.abilityWenzhang(),
                after.abilityCelun() - before.abilityCelun(),
                after.abilityWenxue() - before.abilityWenxue()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<SaveSummary> listSaves() {
        List<GameSave> saves = lambdaQuery().orderByDesc(GameSave::getCreatedAt).orderByDesc(GameSave::getId).list();
        if (saves.isEmpty()) {
            return List.of();
        }
        Map<String, Character> players = characterService.lambdaQuery().eq(Character::getType, PLAYER_CHARACTER_TYPE)
                .in(Character::getSaveId, saves.stream().map(GameSave::getId).toList()).list().stream()
                .collect(Collectors.toMap(Character::getSaveId, Function.identity()));
        return saves.stream().filter(save -> players.containsKey(save.getId())).map(save -> {
            Character player = players.get(save.getId());
            return new SaveSummary(save.getId(), player.getName(), save.getAge(), save.getCurrentYear(), save.getStatus(),
                    save.getCreatedAt(), player.getOfficialPosition(), player.getOfficialRank(), player.getDegree(),
                    JSONUtil.parseArray(player.getTitlesJson()).toList(String.class));
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ActionContext prepareAction(String saveId, String actorId, String sceneCode) {
        ActorContext context = loadActor(saveId, actorId, false);
        if (!"STUDYING".equals(context.save().getStatus()) || context.character().getCharacterJiankang() <= 0
                || context.character().getSickTurnsRemaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成考试或重病休养");
        }
        JSONObject scene = requireScene(sceneCode, context.character(), "FREE_ACTION");
        List<String> presentNpcCodes = scene.getJSONArray("availableNpcCode").toList(String.class);
        CharacterState character = characterState(context.character());
        ScholarState scholar = scholarState(context.scholar());
        int actorAge = actorAge(context);
        JSONObject calendar = new JSONObject().set("currentYear", context.save().getCurrentYear())
                .set("currentMonth", context.save().getCurrentMonth()).set("turnInMonth", context.save().getTurnInMonth())
                .set("totalTurnNumber", context.save().getTotalTurnNumber());
        String facts = new JSONObject().set("calendar", calendar)
                .set("actor", context.character()).set("actorAge", actorAge).set("scholar", scholar)
                .set("playerFamilyBackground", familyBackgroundService.lambdaQuery()
                        .eq(FamilyBackground::getSaveId, saveId).one())
                .set("scene", scene)
                .set("books", bookService.listLibrary(context.save(), context.character().getId(),
                        character, scholar))
                .set("npcs", presentNpcCodes.isEmpty() ? List.of() : characterService.lambdaQuery()
                        .eq(Character::getSaveId, saveId).eq(Character::getType, 0)
                        .eq(Character::getEnabled, true).in(Character::getNpcCode, presentNpcCodes).list()).toString();
        return new ActionContext(saveId, context.character().getId(), context.save().getTotalTurnNumber(), sceneCode,
                character, scholar, facts);
    }

    @Override
    public JSONObject executeFreeAction(String saveId, String actorId, FreeActionCommand command) {
        if (command == null || command.text() == null || command.text().isBlank()
                || command.requestId() == null || command.requestId().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写自定义行动及不超过100字符的请求编号");
        }
        JSONObject payload = new JSONObject().set("operation", "FREE_ACTION").set("actorId", actorId).set("command", command);
        JSONObject previous = eventRecordService.replay(saveId, command.requestId(), payload);
        if (previous != null) {
            return previous;
        }
        ActionContext before = prepareAction(saveId, actorId, command.sceneCode());
        if (!Objects.equals(command.expectedTurnNumber(), before.turnNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回合已变化，请读档后重试");
        }
        String memories = memoryRecordService.recall(saveId, actorId, null,
                GameRuleConstant.MEMORY_CONTEXT_MAX_CHARACTERS);
        String facts = JSONUtil.parseObj(before.contextSummary()).set("memoryContext", JSONUtil.parseArray(memories)).toString();
        FreeActionWorkflow.FreeActionResult resolved = freeActionWorkflow.execute(
                new FreeActionWorkflow.FreeActionCommand(command.text(), facts,
                        before.character(), before.scholar()));
        return new TransactionTemplate(transactionManager).execute(status -> settleAiAction(before, command.requestId(),
                payload, resolved.settlement(), resolved.acquisitions(), true, resolved.eventSummary(), resolved.lifeMilestone()));
    }

    @Override
    @Transactional
    public JSONObject settleAiAction(ActionContext before, String requestId, Object payload, DriverResult settlement,
                                      List<AcquisitionIntent> acquisitions, boolean endTurn, String summary, boolean milestone) {
        ActorContext context = loadActor(before.saveId(), before.actorId(), true);
        JSONObject previous = eventRecordService.replay(before.saveId(), requestId, payload);
        if (previous != null) {
            return previous;
        }
        if (!"STUDYING".equals(context.save().getStatus())
                || context.save().getTotalTurnNumber() != before.turnNumber()
                || !characterState(context.character()).equals(before.character())
                || !scholarState(context.scholar()).equals(before.scholar())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人物或回合已变化，本次AI结果未结算，请重新发起");
        }
        List<JSONObject> trades = new ArrayList<>();
        List<AcquisitionIntent> intents = acquisitions == null ? List.of() : acquisitions;
        if (intents.stream().anyMatch(Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI返回的物品获取列表包含无效内容，请重新发起");
        }
        for (int i = 0; i < intents.size(); i++) {
            AcquisitionIntent intent = intents.get(i);
            trades.add(equipmentRecordService.acquire(before.saveId(), before.actorId(),
                    new AcquisitionCommand(requestId + "/" + i, before.sceneCode(),
                            intent.supplierNpcCode(), intent.equipmentCode(), intent.quantity())));
        }
        // 获取行为可能改变钱包，重新读取后再保存属性，避免覆盖刚刚完成的扣款。
        context = loadActor(before.saveId(), before.actorId(), true);
        if (settlement != null) {
            applyCharacterState(context.character(), settlement.character());
            applyScholarState(context.scholar(), settlement.scholar());
            context.scholar().setLastActiveTurnNumber(before.turnNumber() + (endTurn ? 1 : 0));
            characterService.updateById(context.character());
            careerProfileShushengService.updateById(context.scholar());
        }
        TurnEngine.TurnResult turn = turnEngine.advance(turnState(context.save()), endTurn);
        applyTurnState(context.save(), turn.state());
        updateById(context.save());
        markIllness(context);
        prepareTriggeredExam(context, turn);
        if (milestone && settlement != null) {
            recordMilestone(context, "FREE_ACTION_MILESTONE", summary == null ? "一次重要经历" : summary,
                    Map.of("character", context.character(), "scholarProfile", context.scholar(),
                            "sceneCode", before.sceneCode(), "executedTrades", trades));
        }
        advanceIllness(context);
        JSONObject result = new JSONObject().set("requestId", requestId).set("actorId", before.actorId())
                .set("summary", summary == null ? "" : summary).set("trades", trades)
                .set("detail", buildDetail(context));
        eventRecordService.recordOperation(before.saveId(), before.actorId(), requestId, payload,
                "AI_ACTION", context.save().getTotalTurnNumber(), result);
        return result;
    }

    /**
     * 创建固定NPC及其同结构的领域档案，不调用模型、不向玩家发教材。
     *
     * @param gameSave 新存档
     * @param player 用于复制共同开局字段的玩家对象
     * @param initialScholar 初始书生能力
     */
    private void createNpcs(GameSave gameSave, Character player, ScholarState initialScholar) {
        for (JSONObject definition : jsonLoader.load("game/npc.json", JSONObject.class)
                .getJSONArray("npc").toList(JSONObject.class)) {
            Character existing = characterService.lambdaQuery().eq(Character::getSaveId, gameSave.getId())
                    .eq(Character::getNpcCode, definition.getStr("npcCode")).one();
            if (existing != null) {
                // 仅替换旧模板身份，保留已有钱包、能力、记忆和自定义姓名。
                String legacyName = switch (definition.getStr("npcCode")) {
                    case "NPC_XIANSHENG" -> "私塾先生";
                    case "NPC_JIAHAO" -> "隔壁班嘉豪";
                    case "NPC_SHANGREN" -> "书商陆掌柜";
                    default -> null;
                };
                if (legacyName != null && legacyName.equals(existing.getName())) {
                    existing.setName(definition.getStr("displayName"))
                            .setPersonalitySummary(definition.getStr("personalitySummary"));
                    characterService.updateById(existing);
                }
                continue;
            }
            Character npc = JSONUtil.toBean(JSONUtil.toJsonStr(player), Character.class)
                    .setId(null).setType(0).setNpcCode(definition.getStr("npcCode"))
                    .setName(definition.getStr("displayName")).setPersonalitySummary(definition.getStr("personalitySummary"))
                    .setWallet(GameRuleConstant.INITIAL_WALLET).setSickTurnsRemaining(0)
                    .setOfficialPosition(null).setOfficialRank(null).setDegree(null).setTitlesJson("[]")
                    .setEnabled(definition.getBool("enabled"))
                    .setBirthday((gameSave.getBirthYear() - ("NPC_JIAHAO".equals(definition.getStr("npcCode")) ? 0 : 20)) + "-01-01");
            applyCharacterState(npc, characterEngine.startLife(player.getBirthRegionId()).character());
            npc.setCurrentState(null);
            characterService.save(npc);
            CareerProfileShusheng scholar = new CareerProfileShusheng().setCharacterId(npc.getId())
                    .setUnlockTurnNumber(0L).setLastActiveTurnNumber(null);
            applyScholarState(scholar, initialScholar);
            careerProfileShushengService.save(scholar);
        }
    }

    /**
     * 只在首次健康归零时扣除属性，并保存待强制经过的回合数。
     *
     * @param context 已完成本轮属性结算的持久化上下文
     */
    private void markIllness(ActorContext context) {
        if (context.character().getCharacterJiankang() <= 0 && context.character().getSickTurnsRemaining() == 0) {
            applyCharacterState(context.character(), characterEngine.enterIllness(characterState(context.character())));
            context.character().setSickTurnsRemaining(GameRuleConstant.SICK_TURNS);
            characterService.updateById(context.character());
        }
    }

    /**
     * 逐回合休养；考试节点先处理考试，剩余重病回合不丢失。
     *
     * @param context 当前人物、领域和存档时间，推进结果写回这些对象
     */
    private void advanceIllness(ActorContext context) {
        markIllness(context);
        while ("STUDYING".equals(context.save().getStatus()) && context.character().getSickTurnsRemaining() > 0) {
            int remaining = context.character().getSickTurnsRemaining() - 1;
            applyCharacterState(context.character(), characterEngine.recoverIllnessTurn(
                    characterState(context.character()), remaining == 0));
            context.character().setSickTurnsRemaining(remaining);
            characterService.updateById(context.character());
            TurnEngine.TurnResult turn = turnEngine.advance(turnState(context.save()), true);
            applyTurnState(context.save(), turn.state());
            updateById(context.save());
            prepareTriggeredExam(context, turn);
        }
    }

    /**
     * 共享时间到达考试节点时，用玩家当前属性与学识建立快照，不取最后行动的NPC。
     *
     * @param context 已保存行动结果和共享时间的当前行动者上下文
     * @param turn 时间引擎返回的推进及考试触发结果
     */
    private void prepareTriggeredExam(ActorContext context, TurnEngine.TurnResult turn) {
        if (turn.triggeredExamType() != null) {
            ActorContext player = context.character().getType() == PLAYER_CHARACTER_TYPE
                    ? context : loadPlayer(context.save().getId(), false);
            examRecordService.prepare(context.save(), player.character().getId(), characterState(player.character()),
                    scholarState(player.scholar()), bookService.totalKnowledge(player.character().getId()));
        }
    }

    /** 同时检查场景的行动许可及NPC行动者的在场关系，玩家可通过界面切换房间。 */
    private JSONObject requireScene(String sceneCode, Character actor, String actionCode) {
        JSONObject scene = jsonLoader.load("game/scene.json", JSONObject.class).getJSONArray("scene")
                .toList(JSONObject.class).stream()
                .filter(value -> Objects.equals(sceneCode, value.getStr("sceneCode"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景不存在"));
        if (!scene.getJSONArray("availableActionCode").contains(actionCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前场景不能执行该行动");
        }
        if (actor.getType() == 0 && !scene.getJSONArray("availableNpcCode").contains(actor.getNpcCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "行动人物不在当前场景");
        }
        return scene;
    }

    @Override
    @Transactional
    public void prepareContent(String saveId) {
        ActorContext context = loadPlayer(saveId, true);
        bookService.importDefinitions();
        createNpcs(context.save(), context.character(), characterEngine.startLife(context.character().getBirthRegionId()).scholar());
    }

    private record PlayerReadingAttempt(String actorId, long turnNumber, String sceneCode, CharacterState character,
                                        ScholarState scholar, LibraryBook book, String characterContext) {
    }

    private record PlayerReadingSubmission(PlayerReadingAttempt attempt, JSONObject question, JSONObject payload,
                                           String requestId, JSONObject previous) {
    }

    private record ExamAttempt(ExamRecord exam, String characterContext) {
    }

    private record ActorContext(GameSave save, Character character, CareerProfileShusheng scholar) {
    }
}
