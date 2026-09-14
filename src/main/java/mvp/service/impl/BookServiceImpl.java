package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;
import mvp.ai.GameClient;
import mvp.engine.CharacterEngine.BookProgress;
import mvp.engine.CharacterEngine.BookRule;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ReadBookResult;
import mvp.engine.CharacterEngine.ReadingReward;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.CharacterEngine;
import mvp.entity.Book;
import mvp.entity.BookRecord;
import mvp.entity.Character;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;
import mvp.entity.GameSave;
import mvp.entity.EventRecord;
import mvp.mapper.BookMapper;
import mvp.service.BookRecordService;
import mvp.service.BookService;
import mvp.service.CharacterService;
import mvp.service.EquipmentRecordService;
import mvp.service.EquipmentService;
import mvp.service.EventRecordService;
import mvp.utils.ClasspathJsonLoader;
import mvp.utils.Calculator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import static mvp.engine.GameRuleConstant.BOOK_COMPLETION_PROGRESS;

@Service
@RequiredArgsConstructor
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements BookService {

    private static final String SCHOLAR_DOMAIN = "DOMAIN_SHUSHENG";
    private static final String OWNED = "OWNED";

    private final ClasspathJsonLoader jsonLoader;
    private final GameClient gameClient;
    private final CharacterEngine characterEngine;
    private final CharacterService characterService;
    private final EquipmentService equipmentService;
    private final EquipmentRecordService equipmentRecordService;
    private final BookRecordService bookRecordService;
    private final EventRecordService eventRecordService;
    private static final String REWARD_RECONCILED = "BOOK_REWARDS_RECONCILED";
    private static final String REWARD_REQUEST_PREFIX = "SYSTEM/BOOK_REWARDS/";
    private static final List<String> REWARD_FIELDS = List.of("characterZhili", "characterDaode",
            "characterZhengzhi", "characterJiaoji", "characterTineng", "abilityShizi",
            "abilityJingyi", "abilityWenzhang", "abilityCelun", "abilityWenxue");
    private Map<String, ReadingReward> readingRewards;

    @PostConstruct
    public void loadReadingRewards() {
        Map<String, ReadingReward> rewards = new HashMap<>();
        JSONArray definitions = jsonLoader.load("game/book.json", JSONObject.class).getJSONArray("book");
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalStateException("书籍配置缺少book数组");
        }
        for (JSONObject definition : definitions.toList(JSONObject.class)) {
            String code = definition.getStr("equipmentCode");
            ReadingReward reward = parseReadingReward(definition.getJSONObject("readingReward"), 100);
            if (code == null || code.isBlank() || rewards.putIfAbsent(code, reward) != null) {
                throw new IllegalStateException("书籍成长配置编码为空或重复：" + code);
            }
        }
        readingRewards = Map.copyOf(rewards);
    }

    private ReadingReward parseReadingReward(JSONObject json, int maximum) {
        if (json == null) {
            throw new IllegalStateException("缺少书籍成长数据");
        }
        int[] values = new int[REWARD_FIELDS.size()];
        for (int i = 0; i < values.length; i++) {
            BigDecimal value = json.getBigDecimal(REWARD_FIELDS.get(i));
            if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(maximum)) > 0) {
                throw new IllegalStateException("书籍成长字段无效：" + REWARD_FIELDS.get(i));
            }
            values[i] = value.intValueExact();
        }
        return new ReadingReward(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9]);
    }

    private ReadingReward configuredReward(String bookCode) {
        ReadingReward reward = readingRewards.get(bookCode);
        if (reward == null) {
            throw new IllegalStateException("书籍缺少完整成长配置：" + bookCode);
        }
        return reward;
    }

    private EventRecord rewardReconciliation(String saveId, String characterId) {
        return eventRecordService.lambdaQuery().eq(EventRecord::getSaveId, saveId)
                .eq(EventRecord::getEventCode, REWARD_RECONCILED)
                .apply("JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0}))", characterId)
                .orderByDesc(EventRecord::getEventSequence).last("LIMIT 1").one();
    }

    private Map<String, ReadingReward> recordedRewards(JSONObject json, int maximum) {
        if (json == null) {
            throw incompleteReadingHistory();
        }
        Map<String, ReadingReward> result = new HashMap<>();
        try {
            for (String code : json.keySet()) {
                result.put(code, parseReadingReward(json.getJSONObject(code), maximum));
            }
        } catch (RuntimeException exception) {
            throw incompleteReadingHistory();
        }
        return result;
    }

    /** 最新核算记录保留已消耗额度；超过现行额度的部分在后续进度中抵足。 */
    private ReadingReward legacyReward(String saveId, String characterId, String bookCode) {
        EventRecord reconciliation = rewardReconciliation(saveId, characterId);
        if (reconciliation == null) {
            throw new IllegalStateException("请先完成存档阅读成长核算");
        }
        return recordedRewards(JSONUtil.parseObj(reconciliation.getSettlementResultJson())
                .getJSONObject("legacyRewards"), Integer.MAX_VALUE).getOrDefault(bookCode, ReadingReward.ZERO);
    }

    @Override
    public ReadingReconciliation reconcileReadingRewards(GameSave save, String characterId,
                                                          CharacterState character, ScholarState scholar) {
        // 调用者已锁存档；按最新额度快照判断，切回曾使用的配置也不能重放旧补发。
        EventRecord previous = rewardReconciliation(save.getId(), characterId);
        Map<String, ReadingReward> previousBudgets = null;
        Map<String, ReadingReward> previousCredits = Map.of();
        boolean restoreEmptyMarker = false;
        if (previous != null) {
            JSONObject result = JSONUtil.parseObj(previous.getSettlementResultJson());
            previousCredits = recordedRewards(result.getJSONObject("legacyRewards"), Integer.MAX_VALUE);
            JSONObject budgets = result.getJSONObject("rewardBudgets");
            if (budgets != null) {
                previousBudgets = recordedRewards(budgets, 100);
                if (readingRewards.equals(previousBudgets)) {
                    return null;
                }
            } else {
                // 没有额度快照时，仅可从尚未读书、也未补发的空标记重建后续回执。
                try {
                    if (!previousCredits.isEmpty() || !ReadingReward.ZERO.equals(
                            parseReadingReward(result.getJSONObject("grantedReward"), Integer.MAX_VALUE))) {
                        throw incompleteReadingBudget();
                    }
                } catch (RuntimeException exception) {
                    throw incompleteReadingBudget();
                }
                restoreEmptyMarker = true;
            }
        }
        LibraryContext context = loadLibrary(save, characterId);
        Map<String, ReadingReward> legacyRewards = new HashMap<>();
        Map<String, Integer> readCounts = new HashMap<>();
        Map<String, Integer> progressTotals = new HashMap<>();
        List<EventRecord> events = eventRecordService.lambdaQuery().eq(EventRecord::getSaveId, save.getId())
                .in(EventRecord::getEventCode, List.of("READ_BOOK", "BOOK_PLAYER_ANSWER"))
                .apply("JSON_CONTAINS(related_character_id_json, JSON_QUOTE({0}))", characterId)
                .orderByAsc(EventRecord::getEventSequence).list();
        for (EventRecord event : events) {
            JSONObject result = JSONUtil.parseObj(event.getSettlementResultJson());
            JSONObject detail = result.getJSONObject("detail");
            JSONObject actor = detail == null ? null : detail.getJSONObject("character");
            if (actor == null || !characterId.equals(actor.getStr("id"))) {
                throw incompleteReadingHistory();
            }
            JSONObject payload = JSONUtil.parseObj(event.getRequestPayloadJson());
            JSONObject command = payload.getJSONObject("command");
            String code = "READ_BOOK".equals(event.getEventCode())
                    ? command == null ? null : command.getStr("bookCode") : payload.getStr("bookCode");
            JSONObject changes = result.getJSONObject("changes");
            Integer progressGain = changes == null ? null : changes.getInt("progressGain");
            if (code == null || progressGain == null || progressGain < 0 || progressGain > BOOK_COMPLETION_PROGRESS
                    || changes.getJSONObject("abilityGain") == null) {
                throw incompleteReadingHistory();
            }
            JSONObject paid = changes.getJSONObject("readingRewardGain");
            if (paid == null) {
                paid = new JSONObject();
                for (String field : REWARD_FIELDS) {
                    paid.set(field, field.startsWith("ability")
                            ? changes.getJSONObject("abilityGain").getInt(field) : 0);
                }
            }
            try {
                ReadingReward credit = parseReadingReward(paid, Integer.MAX_VALUE);
                if (restoreEmptyMarker) {
                    ReadingReward allocated = recordedReadingCredit(previous, event, detail, code, progressGain);
                    if (!allocated.maximum(credit).equals(allocated)) {
                        throw incompleteReadingBudget();
                    }
                    credit = allocated;
                }
                legacyRewards.merge(code, credit, ReadingReward::plus);
                readCounts.merge(code, 1, Math::addExact);
                progressTotals.merge(code, progressGain, Math::addExact);
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw incompleteReadingHistory();
            }
        }
        Set<String> progressedBooks = context.progressByEquipmentId().values().stream()
                .map(progress -> context.equipmentById().get(progress.getEquipmentId()).getEquipmentCode())
                .collect(Collectors.toSet());
        if (!progressedBooks.containsAll(readCounts.keySet()) || !progressedBooks.containsAll(previousCredits.keySet())) {
            throw incompleteReadingHistory();
        }
        ReadingReward due = ReadingReward.ZERO;
        for (BookRecord progress : context.progressByEquipmentId().values()) {
            String code = context.equipmentById().get(progress.getEquipmentId()).getEquipmentCode();
            if (!Objects.equals(readCounts.getOrDefault(code, 0), progress.getTotalReadTurnNumber())
                    || !Objects.equals(progressTotals.getOrDefault(code, 0), progress.getCurrentProgress())) {
                throw incompleteReadingHistory();
            }
            ReadingReward legacy = legacyRewards.getOrDefault(code, ReadingReward.ZERO);
            if (previousBudgets != null) {
                ReadingReward oldBudget = previousBudgets.get(code);
                if (oldBudget == null && progress.getTotalReadTurnNumber() > 0) {
                    throw incompleteReadingBudget();
                }
                ReadingReward consumed = previousCredits.getOrDefault(code, ReadingReward.ZERO).maximum(
                        (oldBudget == null ? ReadingReward.ZERO : oldBudget).atProgress(progress.getCurrentProgress()));
                if (!consumed.maximum(legacy).equals(consumed)) {
                    throw incompleteReadingHistory();
                }
                legacy = consumed;
                legacyRewards.put(code, consumed);
            }
            due = due.plus(configuredReward(code).atProgress(progress.getCurrentProgress()).subtractPositive(legacy));
        }
        CharacterState characterAfter = characterEngine.applyReadingAttributes(character, due);
        ScholarState scholarAfter = characterEngine.applyReadingAbilities(scholar, due);
        ReadingReward granted = characterEngine.readingRewardDifference(character, scholar, characterAfter, scholarAfter);
        Map<String, Object> result = Map.of("legacyRewards", legacyRewards, "rewardBudgets", readingRewards,
                "grantedReward", granted);
        String requestId = REWARD_REQUEST_PREFIX + characterId
                + (previous == null ? "" : "/" + previous.getEventSequence());
        eventRecordService.recordOperation(save.getId(), characterId, requestId,
                new JSONObject().set("operation", REWARD_RECONCILED).set("actorId", characterId),
                REWARD_RECONCILED, save.getTotalTurnNumber(), result);
        return new ReadingReconciliation(characterAfter, scholarAfter, granted);
    }

    /** 旧空标记之后的回执含逐书额度与进度，足以还原包括属性封顶在内的已消耗额度。 */
    private ReadingReward recordedReadingCredit(EventRecord marker, EventRecord event, JSONObject detail,
                                                 String bookCode, int progressGain) {
        if (event.getEventSequence() <= marker.getEventSequence()) {
            throw incompleteReadingBudget();
        }
        JSONArray books = detail.getJSONArray("books");
        JSONObject book = books == null ? null : books.toList(JSONObject.class).stream()
                .filter(candidate -> bookCode.equals(candidate.getStr("bookCode"))).findFirst().orElse(null);
        Integer progress = book == null ? null : book.getInt("currentProgress");
        if (progress == null || progress < progressGain || progress > BOOK_COMPLETION_PROGRESS
                || book.getJSONObject("readingReward") == null) {
            throw incompleteReadingBudget();
        }
        ReadingReward budget = parseReadingReward(book.getJSONObject("readingReward"), 100);
        return budget.atProgress(progress).subtractPositive(budget.atProgress(progress - progressGain));
    }

    private ResponseStatusException incompleteReadingBudget() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "旧存档的成长核算记录缺少逐书额度，无法准确处理调参；请核对原始记录，本次未改动进度或属性");
    }

    private ResponseStatusException incompleteReadingHistory() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "旧存档的阅读结算记录不完整，无法准确补齐成长；本次未改动进度或属性");
    }

    @Override
    @Transactional
    public void importDefinitions() {
        JSONArray bookDefinitions = jsonLoader.load("game/book.json", JSONObject.class).getJSONArray("book");
        if (bookDefinitions == null || bookDefinitions.isEmpty()) {
            throw new IllegalStateException("私塾书籍配置缺少book数组");
        }
        Map<String, Equipment> equipmentByCode = equipmentService.importDefinitions();
        Set<String> importedCodes = new HashSet<>();
        for (int index = 0; index < bookDefinitions.size(); index++) {
            JSONObject definition = bookDefinitions.getJSONObject(index);
            String code = definition.getStr("equipmentCode");
            Equipment equipment = equipmentByCode.get(code);
            if (equipment == null || !"BOOK".equals(equipment.getEquipmentType()) || !importedCodes.add(code)) {
                throw new IllegalStateException("书籍装备缺失、类型错误或编码重复：" + code);
            }
            Book configuredBook = definition.toBean(Book.class).setEquipmentId(equipment.getId());
            JSONArray requirements = definition.getJSONArray("readingRequirement");
            if (requirements == null) {
                throw new IllegalStateException("书籍缺少readingRequirement数组：" + code);
            }
            configuredBook.setReadingRequirementJson(requirements.toString());
            bookRule(configuredBook, code);
            if (getById(equipment.getId()) == null) {
                try {
                    baseMapper.insert(configuredBook);
                } catch (DuplicateKeyException exception) {
                    // 公共定义共用固定主键，其他存档先导入时同步同一行即可。
                    if (baseMapper.updateById(configuredBook) != 1) {
                        throw exception;
                    }
                }
            } else {
                baseMapper.updateById(configuredBook);
            }
        }
    }

    @Override
    public List<LibraryBook> listLibrary(
            GameSave save, String characterId, CharacterState character, ScholarState scholar
    ) {
        LibraryContext context = loadLibrary(save, characterId);
        return context.books().stream()
                .map(book -> describeBook(book, save, character, scholar, context))
                .toList();
    }

    @Override
    public BookActionResult read(
            GameSave save, String characterId, CharacterState character,
            ScholarState scholar, String bookCode, long settlementTurnNumber
    ) {
        return settleBook(save, characterId, character, scholar, bookCode, settlementTurnNumber, null);
    }

    @Override
    public BookActionResult readAsPlayer(
            GameSave save, String characterId, CharacterState character,
            ScholarState scholar, String bookCode, long settlementTurnNumber, int score
    ) {
        return settleBook(save, characterId, character, scholar, bookCode, settlementTurnNumber, score);
    }

    private BookActionResult settleBook(
            GameSave save, String characterId, CharacterState character,
            ScholarState scholar, String bookCode, long settlementTurnNumber, Integer score
    ) {
        LibraryContext context = loadLibrary(save, characterId);
        Book book = context.books().stream()
                .filter(candidate -> context.equipmentById().get(candidate.getEquipmentId())
                        .getEquipmentCode().equals(bookCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "书籍编码不存在：" + bookCode));
        LibraryBook description = describeBook(book, save, character, scholar, context);
        if (score != null) {
            requirePlayerReading(description);
        }
        if (!description.readable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.join("；", description.blockedReasons()));
        }
        BookRecord record = context.progressByEquipmentId().get(book.getEquipmentId());
        BookProgress progress = new BookProgress(description.currentProgress(), description.totalReadTurnNumber(),
                description.completed(), record == null ? null : record.getLastReadTurnNumber(),
                legacyReward(save.getId(), characterId, bookCode));
        ReadBookResult settlement = score == null
                ? characterEngine.readBook(character, scholar, bookRule(book, bookCode), progress,
                        settlementTurnNumber, ThreadLocalRandom.current().nextInt(1, 101))
                : characterEngine.readBookAsPlayer(character, scholar, bookRule(book, bookCode), progress,
                        settlementTurnNumber, score);
        if (record == null) {
            record = new BookRecord().setCharacterId(characterId).setEquipmentId(book.getEquipmentId());
        }
        record.setCurrentProgress(settlement.progress().currentProgress())
                .setTotalReadTurnNumber(settlement.progress().totalReadTurnNumber())
                .setCompleted(settlement.progress().completed())
                .setLastReadTurnNumber(settlement.progress().lastReadTurnNumber());
        bookRecordService.saveOrUpdate(record);
        return new BookActionResult(description.bookName(), settlement);
    }

    @Override
    public LibraryBook requirePlayerReadingBook(GameSave save, String characterId, CharacterState character,
                                               ScholarState scholar, String bookCode) {
        LibraryBook book = listLibrary(save, characterId, character, scholar).stream()
                .filter(candidate -> candidate.bookCode().equals(bookCode)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "书籍编码不存在：" + bookCode));
        requirePlayerReading(book);
        return book;
    }

    private void requirePlayerReading(LibraryBook book) {
        if (!book.playerReadingEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这本书不支持以身入局读书");
        }
        if (!book.readable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.join("；", book.blockedReasons()));
        }
    }

    @Override
    public String generatePlayerReadingQuestion(LibraryBook book, String characterContext) {
        JSONObject facts = new JSONObject().set("bookName", book.bookName())
                .set("knowledgeSummary", book.knowledgeSummary()).set("currentProgress", book.currentProgress())
                .set("characterFacts", JSONUtil.parseObj(characterContext));
        ReadingQuestionOutput output = gameClient.chat("PROMPT_BOOK_PLAYER_QUESTION", facts.toString(),
                ReadingQuestionOutput.class);
        if (output.question() == null || output.question().isBlank() || output.question().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI未返回有效的读书问题，请重试");
        }
        return output.question().strip();
    }

    @Override
    public PlayerReadingEvaluation evaluatePlayerReading(LibraryBook book, String question, String text) {
        JSONObject facts = new JSONObject().set("bookName", book.bookName())
                .set("knowledgeSummary", book.knowledgeSummary()).set("question", question).set("playerInput", text);
        ReadingEvaluationOutput output = gameClient.chat("PROMPT_BOOK_PLAYER_EVALUATION", facts.toString(),
                ReadingEvaluationOutput.class);
        if (output.score() == null || output.evaluation() == null || output.evaluation().isBlank()
                || output.evaluation().length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI未返回有效的读书评分，请重试");
        }
        int score = Calculator.clamp(BigDecimal.ZERO, BigDecimal.valueOf(100), output.score())
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return new PlayerReadingEvaluation(score, output.evaluation().strip());
    }

    /**
     * 一次读取书目关联数据，供列表展示和实际阅读共用。
     *
     * @return 人物年龄、书目、装备定义、个人进度和持有数量
     */
    private LibraryContext loadLibrary(GameSave save, String characterId) {
        Character person = characterService.getById(characterId);
        if (person == null || !save.getId().equals(person.getSaveId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前存档不存在该人物");
        }
        int age = save.getCurrentYear() - LocalDate.parse(person.getBirthday()).getYear();
        List<Book> books = list();
        if (books.isEmpty()) {
            return new LibraryContext(age, List.of(), Map.of(), Map.of(), Map.of());
        }
        List<String> equipmentIds = books.stream().map(Book::getEquipmentId).toList();
        Map<String, Equipment> equipmentById = equipmentService.listByIds(equipmentIds).stream()
                .collect(Collectors.toMap(Equipment::getId, Function.identity()));
        if (equipmentById.size() != equipmentIds.size()) {
            throw new IllegalStateException("书籍定义缺少对应装备");
        }
        books.sort(Comparator.comparing(book -> equipmentById.get(book.getEquipmentId()).getEquipmentCode()));
        Map<String, BookRecord> progressByEquipmentId = bookRecordService.lambdaQuery()
                .eq(BookRecord::getCharacterId, characterId)
                .in(BookRecord::getEquipmentId, equipmentIds)
                .list().stream().collect(Collectors.toMap(BookRecord::getEquipmentId, Function.identity()));
        return new LibraryContext(age, books, equipmentById, progressByEquipmentId,
                ownedQuantities(save.getId(), characterId, save.getTotalTurnNumber()));
    }

    /**
     * 按装备定义汇总人物背包数量，同名物品的多条获取记录可叠加。
     *
     * @return 装备定义ID与持有数量
     */
    private Map<String, Integer> ownedQuantities(String saveId, String characterId, long turnNumber) {
        return equipmentRecordService.lambdaQuery()
                .eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getCharacterId, characterId)
                .eq(EquipmentRecord::getStatus, OWNED)
                .isNotNull(EquipmentRecord::getEquipmentId)
                .le(EquipmentRecord::getAcquiredTurnNumber, turnNumber)
                .list().stream().collect(Collectors.toMap(EquipmentRecord::getEquipmentId,
                        EquipmentRecord::getQuantity, Integer::sum));
    }

    /**
     * 根据阅读条件构造书籍展示，学识按实际进度连续计算。
     *
     * @param context 当前书目和人物阅读记录
     * @return 阅读状态、全部限制原因和当前知识摘要
     */
    private LibraryBook describeBook(
            Book book, GameSave save, CharacterState character, ScholarState scholar, LibraryContext context
    ) {
        Equipment equipment = context.equipmentById().get(book.getEquipmentId());
        bookRule(book, equipment.getEquipmentCode());
        BookRecord progress = context.progressByEquipmentId().get(book.getEquipmentId());
        int currentProgress = progress == null ? 0 : progress.getCurrentProgress();
        boolean completed = currentProgress >= BOOK_COMPLETION_PROGRESS;
        List<String> reasons = new ArrayList<>();
        if (completed) {
            reasons.add("这本书已完成，不能继续阅读");
        }
        if (!"STUDYING".equals(save.getStatus())) {
            reasons.add("当前存档不处于自由成长阶段");
        }
        if (character.characterJiankang() <= 0) {
            reasons.add("重病休养中，当前不能阅读");
        }
        if (context.ownedQuantities().getOrDefault(book.getEquipmentId(), 0) <= 0) {
            reasons.add("背包中没有这本书，请先获取或购买");
        }
        JSONArray requirements = JSONUtil.parseArray(book.getReadingRequirementJson());
        for (int index = 0; index < requirements.size(); index++) {
            JSONObject requirement = requirements.getJSONObject(index);
            Integer minimum = requirement.getInt("value");
            if (minimum == null || minimum < 0) {
                throw new IllegalStateException("阅读条件缺少非负整数 value：" + equipment.getEquipmentCode());
            }
            String type = requirement.getStr("type");
            String target = requirement.getStr("target");
            if (type == null) {
                throw new IllegalStateException("阅读条件缺少 type：" + equipment.getEquipmentCode());
            }
            switch (type) {
                case "MIN_AGE" -> addMinimumReason(reasons, "年龄", context.age(), minimum);
                case "MIN_GENERAL_ATTRIBUTE" -> addMinimumReason(reasons, readingRequirementLabel(target),
                        attributeValue(character, target), minimum);
                case "MIN_CAREER_ABILITY" -> addMinimumReason(reasons, readingRequirementLabel(target),
                        scholarAbility(scholar, target), minimum);
                case "BOOK_PROGRESS" -> {
                    Equipment prerequisite = context.equipmentById().values().stream()
                            .filter(candidate -> candidate.getEquipmentCode().equals(target)).findFirst()
                            .orElseThrow(() -> new IllegalStateException("前置书籍编码不存在：" + target));
                    BookRecord prerequisiteProgress = context.progressByEquipmentId().get(prerequisite.getId());
                    addMinimumReason(reasons, "《" + prerequisite.getEquipmentName() + "》阅读进度",
                            prerequisiteProgress == null ? 0 : prerequisiteProgress.getCurrentProgress(), minimum);
                }
                default -> throw new IllegalStateException("不支持的阅读条件：" + type);
            }
        }
        return new LibraryBook(equipment.getEquipmentCode(), equipment.getEquipmentName(), equipment.getId(),
                equipment.getRarityCode(), equipment.getRarityName(), equipment.getRarityColor(),
                currentProgress, BOOK_COMPLETION_PROGRESS, progress == null ? 0 : progress.getTotalReadTurnNumber(),
                completed, reasons.isEmpty(),
                Boolean.TRUE.equals(book.getPlayerReadingEnabled()), List.copyOf(reasons),
                book.getKnowledgeSummary(), context.ownedQuantities().getOrDefault(book.getEquipmentId(), 0),
                equipment.getPrice(), equipment.getSupplierNpcCode(), book.getTotalKnowledge(),
                characterEngine.knowledgeContribution(book.getTotalKnowledge(), currentProgress),
                configuredReward(equipment.getEquipmentCode()));
    }

    /**
     * 检查书籍数值并组合资源中的完整成长额度，未知领域不得套用书生算法。
     *
     * @param book 数据库或资源中的书籍规则
     * @return 阅读引擎需要的数值快照
     */
    private BookRule bookRule(Book book, String bookCode) {
        if (!SCHOLAR_DOMAIN.equals(book.getGrowthDomainCode())) {
            throw new IllegalStateException("当前阅读引擎不支持成长领域：" + book.getGrowthDomainCode());
        }
        if (book.getTotalKnowledge() == null || book.getTotalKnowledge() < 0
                || book.getFatigueCost() == null || book.getFatigueCost() < 0) {
            throw new IllegalStateException("书籍阅读数值配置错误：" + bookCode);
        }
        return new BookRule(configuredReward(bookCode), book.getFatigueCost());
    }

    private String readingRequirementLabel(String target) {
        if (target == null) {
            throw new IllegalStateException("阅读条件缺少 target");
        }
        return switch (target) {
            case "characterZhili" -> "智力";
            case "characterDaode" -> "道德";
            case "characterZhengzhi" -> "政治";
            case "characterJiaoji" -> "交际";
            case "characterTineng" -> "体能";
            case "characterJiankang" -> "健康";
            case "characterPilao" -> "疲劳";
            case "abilityShizi" -> "识字";
            case "abilityJingyi" -> "经义";
            case "abilityWenzhang" -> "文章";
            case "abilityCelun" -> "策论";
            case "abilityWenxue" -> "文学";
            default -> throw new IllegalStateException("未知的阅读条件属性：" + target);
        };
    }

    private void addMinimumReason(List<String> reasons, String label, int current, int minimum) {
        if (current < minimum) {
            reasons.add(label + "需达到 " + minimum + "，当前为 " + current);
        }
    }

    private int attributeValue(CharacterState character, String target) {
        if (target == null) {
            throw new IllegalStateException("通用属性阅读条件缺少 target");
        }
        return switch (target) {
            case "characterZhili" -> character.characterZhili();
            case "characterDaode" -> character.characterDaode();
            case "characterZhengzhi" -> character.characterZhengzhi();
            case "characterJiaoji" -> character.characterJiaoji();
            case "characterTineng" -> character.characterTineng();
            case "characterJiankang" -> character.characterJiankang();
            case "characterPilao" -> character.characterPilao();
            default -> throw new IllegalStateException("未知的通用属性：" + target);
        };
    }

    private int scholarAbility(ScholarState scholar, String target) {
        if (target == null) {
            throw new IllegalStateException("书生领域阅读条件缺少 target");
        }
        return switch (target) {
            case "abilityShizi" -> scholar.abilityShizi();
            case "abilityJingyi" -> scholar.abilityJingyi();
            case "abilityWenzhang" -> scholar.abilityWenzhang();
            case "abilityCelun" -> scholar.abilityCelun();
            case "abilityWenxue" -> scholar.abilityWenxue();
            default -> throw new IllegalStateException("未知的书生领域能力：" + target);
        };
    }

    @Override
    public BigDecimal totalKnowledge(String characterId) {
        Map<String, Book> books = list().stream().collect(Collectors.toMap(Book::getEquipmentId, Function.identity()));
        return bookRecordService.lambdaQuery().eq(BookRecord::getCharacterId, characterId).list().stream()
                .filter(record -> books.containsKey(record.getEquipmentId()))
                .map(record -> characterEngine.knowledgeContribution(
                        books.get(record.getEquipmentId()).getTotalKnowledge(), record.getCurrentProgress()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record ReadingQuestionOutput(String question) {
    }

    public record ReadingEvaluationOutput(BigDecimal score, String evaluation) {
    }

    private record LibraryContext(
            int age, List<Book> books, Map<String, Equipment> equipmentById,
            Map<String, BookRecord> progressByEquipmentId, Map<String, Integer> ownedQuantities
    ) {
    }
}
