package mvp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.engine.CharacterEngine.BookProgress;
import mvp.engine.CharacterEngine.BookRule;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ReadBookResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.CharacterEngine;
import mvp.entity.Book;
import mvp.entity.BookRecord;
import mvp.entity.Character;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;
import mvp.entity.GameSave;
import mvp.mapper.BookMapper;
import mvp.service.BookRecordService;
import mvp.service.BookService;
import mvp.service.CharacterService;
import mvp.service.EquipmentRecordService;
import mvp.service.EquipmentService;
import mvp.utils.ClasspathJsonLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements BookService {

    private static final String SCHOLAR_DOMAIN = "DOMAIN_SHUSHENG";
    private static final String OWNED = "OWNED";

    private final ClasspathJsonLoader jsonLoader;
    private final CharacterEngine characterEngine;
    private final CharacterService characterService;
    private final EquipmentService equipmentService;
    private final EquipmentRecordService equipmentRecordService;
    private final BookRecordService bookRecordService;

    /** {@inheritDoc} */
    @Override
    public void importDefinitions() {
        JSONArray equipmentDefinitions = jsonLoader.load("game/equipment.json", JSONObject.class)
                .getJSONArray("equipment");
        JSONArray bookDefinitions = jsonLoader.load("game/book.json", JSONObject.class).getJSONArray("book");
        if (equipmentDefinitions == null || bookDefinitions == null || bookDefinitions.isEmpty()) {
            throw new IllegalStateException("私塾书籍配置缺少 equipment 或 book 数组");
        }
        Map<String, Equipment> equipmentByCode = new LinkedHashMap<>();
        for (int index = 0; index < equipmentDefinitions.size(); index++) {
            Equipment definition = equipmentDefinitions.getJSONObject(index).toBean(Equipment.class);
            String code = definition.getEquipmentCode();
            if (code == null || code.isBlank() || equipmentByCode.containsKey(code)) {
                throw new IllegalStateException("装备配置编码缺失或重复：" + code);
            }
            Equipment equipment = equipmentService.lambdaQuery().eq(Equipment::getEquipmentCode, code).one();
            if (equipment == null) {
                equipment = definition;
                equipmentService.save(equipment);
            }
            equipmentByCode.put(code, equipment);
        }
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
                throw new IllegalStateException("书籍缺少 readingRequirement 数组：" + code);
            }
            configuredBook.setReadingRequirementJson(requirements.toString());
            bookRule(configuredBook);
            if (getById(equipment.getId()) == null) {
                save(configuredBook);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<LibraryBook> listLibrary(
            GameSave save, String characterId, CharacterState character, ScholarState scholar
    ) {
        LibraryContext context = loadLibrary(save, characterId);
        return context.books().stream()
                .map(book -> describeBook(book, save, character, scholar, context))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public BookActionResult read(
            GameSave save, String characterId, CharacterState character,
            ScholarState scholar, String bookCode, long settlementTurnNumber
    ) {
        LibraryContext context = loadLibrary(save, characterId);
        Book book = context.books().stream()
                .filter(candidate -> context.equipmentById().get(candidate.getEquipmentId())
                        .getEquipmentCode().equals(bookCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "书籍编码不存在：" + bookCode));
        LibraryBook description = describeBook(book, save, character, scholar, context);
        if (!description.readable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.join("；", description.blockedReasons()));
        }
        BookRecord record = context.progressByEquipmentId().get(book.getEquipmentId());
        BookProgress progress = new BookProgress(description.currentProgress(), description.totalReadTurnNumber(),
                description.completed(), record == null ? null : record.getLastReadTurnNumber());
        ReadBookResult settlement = characterEngine.readBook(character, scholar, bookRule(book), progress,
                settlementTurnNumber, ThreadLocalRandom.current().nextInt(1, 101));
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

    /**
     * 一次读取书目关联数据，供列表展示和实际阅读共用。
     *
     * @param save 当前存档
     * @param characterId 行动人物ID
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
     * @param saveId 存档ID
     * @param characterId 人物ID
     * @param turnNumber 当前总回合编号
     * @return 装备定义ID与持有数量
     */
    private Map<String, Integer> ownedQuantities(String saveId, String characterId, long turnNumber) {
        return equipmentRecordService.lambdaQuery()
                .eq(EquipmentRecord::getSaveId, saveId)
                .eq(EquipmentRecord::getCharacterId, characterId)
                .eq(EquipmentRecord::getStatus, OWNED)
                .le(EquipmentRecord::getAcquiredTurnNumber, turnNumber)
                .list().stream().collect(Collectors.toMap(EquipmentRecord::getEquipmentId,
                        EquipmentRecord::getQuantity, Integer::sum));
    }

    /**
     * 根据阅读条件构造书籍展示，学识按实际进度连续计算。
     *
     * @param book 书籍规则
     * @param save 当前存档
     * @param character 当前人物状态
     * @param scholar 当前书生领域能力
     * @param context 当前书目和人物阅读记录
     * @return 阅读状态、全部限制原因和当前知识摘要
     */
    private LibraryBook describeBook(
            Book book, GameSave save, CharacterState character, ScholarState scholar, LibraryContext context
    ) {
        bookRule(book);
        Equipment equipment = context.equipmentById().get(book.getEquipmentId());
        BookRecord progress = context.progressByEquipmentId().get(book.getEquipmentId());
        int currentProgress = progress == null ? 0 : progress.getCurrentProgress();
        List<String> reasons = new ArrayList<>();
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
                case "MIN_GENERAL_ATTRIBUTE" -> addMinimumReason(reasons, "通用属性 " + target,
                        attributeValue(character, target), minimum);
                case "MIN_CAREER_ABILITY" -> addMinimumReason(reasons, "书生领域能力 " + target,
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
                currentProgress, book.getRequiredProgress(), progress == null ? 0 : progress.getTotalReadTurnNumber(),
                currentProgress >= book.getRequiredProgress(), reasons.isEmpty(), List.copyOf(reasons),
                book.getKnowledgeSummary(), context.ownedQuantities().getOrDefault(book.getEquipmentId(), 0),
                equipment.getPrice(), equipment.getSupplierNpcCode(), book.getTotalKnowledge(),
                characterEngine.knowledgeContribution(book.getTotalKnowledge(), currentProgress));
    }

    /**
     * 检查书籍规则的必要数值并生成引擎输入，未知成长领域不得套用书生算法。
     *
     * @param book 数据库或资源中的书籍规则
     * @return 阅读引擎需要的数值快照
     */
    private BookRule bookRule(Book book) {
        if (!SCHOLAR_DOMAIN.equals(book.getGrowthDomainCode())) {
            throw new IllegalStateException("当前阅读引擎不支持成长领域：" + book.getGrowthDomainCode());
        }
        BookRule rule = new BookRule(book.getDifficulty(), book.getRequiredProgress(), book.getBaseProgressPerTurn(),
                book.getAbilityShiziWeight(), book.getAbilityJingyiWeight(), book.getAbilityWenzhangWeight(),
                book.getAbilityCelunWeight(), book.getAbilityWenxueWeight(), book.getFatigueCost());
        if (rule.requiredProgress() != 100 || book.getTotalKnowledge() == null || book.getTotalKnowledge() < 0
                || rule.baseProgressPerTurn() <= 0 || rule.difficulty() < 0
                || rule.fatigueCost() < 0 || rule.abilityShiziWeight() < 0 || rule.abilityJingyiWeight() < 0
                || rule.abilityWenzhangWeight() < 0 || rule.abilityCelunWeight() < 0 || rule.abilityWenxueWeight() < 0
                || rule.abilityShiziWeight() + rule.abilityJingyiWeight() + rule.abilityWenzhangWeight()
                + rule.abilityCelunWeight() + rule.abilityWenxueWeight() != 100) {
            throw new IllegalStateException("书籍阅读数值或能力权重配置错误：" + book.getEquipmentId());
        }
        return rule;
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

    /** {@inheritDoc} */
    @Override
    public BigDecimal totalKnowledge(String characterId) {
        Map<String, Book> books = list().stream().collect(Collectors.toMap(Book::getEquipmentId, Function.identity()));
        return bookRecordService.lambdaQuery().eq(BookRecord::getCharacterId, characterId).list().stream()
                .filter(record -> books.containsKey(record.getEquipmentId()))
                .map(record -> characterEngine.knowledgeContribution(
                        books.get(record.getEquipmentId()).getTotalKnowledge(), record.getCurrentProgress()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record LibraryContext(
            int age, List<Book> books, Map<String, Equipment> equipmentById,
            Map<String, BookRecord> progressByEquipmentId, Map<String, Integer> ownedQuantities
    ) {
    }
}
