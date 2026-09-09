package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverResult;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.ExamRecord;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.service.BookService.LibraryBook;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.EquipmentRecordService.InventoryItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface GameSaveService extends IService<GameSave> {

    /**
     * 查询开始人生页面允许选择的出生地区。
     *
     * @return 惠州下启用的出生地区，当前为博罗和海丰
     */
    List<Region> listBirthRegions();

    /**
     * 创建六岁玩家、NPC和存档，只准备教材定义，不自动发放物品。
     *
     * @param command 玩家姓名和出生地区
     * @return 已持久化的存档、玩家、家庭背景和书生领域档案
     */
    StartLifeResult startLife(StartLifeCommand command);

    /**
     * 读取可继续游玩的存档，包括书库、考试和人生节点，不推进时间。
     *
     * @return 当前存档完整状态
     */
    SaveDetail loadDetail(String saveId);

    /**
     * 读取全部书目及当前人物的阅读条件、进度和学识贡献。
     *
     * @return 按书籍编码排列的书库
     */
    List<LibraryBook> listBooks(String saveId);

    /** 为当前回合的指定书籍生成并缓存体会题；重复请求复用原题，不消耗回合。 */
    JSONObject preparePlayerReading(String saveId, String bookCode, PlayerReadingQuestionCommand command);

    /** 评阅玩家体会并结算一个读书回合；questionId同时作为本题唯一交卷依据。 */
    JSONObject completePlayerReading(String saveId, String bookCode, PlayerReadingAnswerCommand command);

    /**
     * 结算一次固定行动，并在同一事务中保存数值、日历及触发的考试。
     *
     * @param command 稳定请求编号、行动、场景、可选书籍及预期累计回合
     * @return 行动后的存档、实际变化、骰点和反馈；重传返回原结果
     */
    JSONObject executeFixedAction(String saveId, FixedActionCommand command);

    /**
     * 按考试快照计分并生成系统代行答卷与总结，阶段考试恢复求学，县试结束本局。
     *
     * @param examId 已触发的考试记录ID
     * @return 成绩、更新后的存档及是否为首次结算
     */
    ExamResult completeAutoExam(String saveId, String examId);

    /** 获取或生成当前玩家考试的思维提示，缓存至考试记录，不计分或推进回合。 */
    ExamRecord prepareExamThought(String saveId, String examId);

    /**
     * 评价玩家答案、由引擎计分并保存考试；相同requestId和原文返回原响应。
     * 答案不超过8000字符，内容修正最多正负10分，失败不保存部分结果。
     */
    JSONObject completePlayerExam(String saveId, String examId, PlayerExamCommand command);

    /**
     * 查询存档入口列表，不读取完整人生事件。
     *
     * @return 按创建时间倒序排列的存档与人物关键身份
     */
    List<SaveSummary> listSaves();

    /**
     * 补齐存档缺失的NPC和公共教材定义，并更新旧NPC模板的默认称呼与身份。
     * 不发放物品、不覆盖公共装备定义，不改写人物成长数据、自定义姓名或历史记忆。
     *
     * @param saveId 已完成表结构升级的存档ID
     */
    void prepareContent(String saveId);

    /**
     * 玩家与NPC共用的固定行动结算入口。
     *
     * @param actorId 行动人物ID，空时使用玩家
     * @param command 稳定请求编号、行动参数与预期回合
     * @return 已保存的结算结果
     */
    JSONObject executeCharacterAction(String saveId, String actorId, FixedActionCommand command);

    /**
     * 使用指定人物的考试快照完成系统代行；只有玩家考试可以切换存档阶段或结束本局。
     *
     * @param actorId 应考人物ID，空时使用玩家
     * @param examId 已触发的考试ID
     * @return 考试成绩与最新存档
     */
    ExamResult completeCharacterExam(String saveId, String actorId, String examId);

    /**
     * 为自由行动或对话读取日期、行动者年龄与能力、玩家初始家庭背景及场景事实。
     *
     * @param actorId 玩家或NPC的ID
     * @return 模型调用前的只读数值快照和事实JSON，家庭背景不代表NPC背景或人物钱包
     */
    ActionContext prepareAction(String saveId, String actorId, String sceneCode);

    /**
     * 在事务外运行自由行动图，再核对快照并保存实际结果。
     *
     * @param actorId 玩家或NPC的ID
     * @param command 原文、场景、预期回合及稳定请求编号
     * @return 已保存的自由行动结果
     */
    JSONObject executeFreeAction(String saveId, String actorId, FreeActionCommand command);

    /**
     * 在短事务中核对模型调用前快照，保存属性、交易和可选的回合推进。
     *
     * @param requestId 结算请求编号
     * @param settlement 引擎计算结果，非结束对话时为空
     * @param acquisitions 本次明确执行的获取行为
     * @param endTurn 是否推进普通回合
     * @param summary 行为摘要
     * @param milestone 是否记录人生节点
     * @return 已执行结果；重复请求返回原结果
     */
    JSONObject settleAiAction(ActionContext before, String requestId, Object payload, DriverResult settlement,
                             List<AcquisitionIntent> acquisitions, boolean endTurn, String summary, boolean milestone);

    record StartLifeCommand(
            String characterName,
            String birthRegionId
    ) {
    }

    record StartLifeResult(
            GameSave save,
            Character character,
            FamilyBackground familyBackground,
            CareerProfileShusheng scholarProfile
    ) {
    }

    record SaveDetail(
            GameSave save,
            Character character,
            FamilyBackground familyBackground,
            CareerProfileShusheng scholarProfile,
            List<LibraryBook> books,
            List<ExamRecord> exams,
            List<EventRecord> milestones,
            BigDecimal knowledgeTotal,
            List<InventoryItem> backpack,
            List<Character> npcs
    ) {
    }

    record FixedActionCommand(
            String requestId,
            String actionCode,
            String sceneCode,
            String bookCode,
            Long expectedTurnNumber
    ) {
    }

    record ActionChanges(
            int progressGain,
            ScholarState abilityGain,
            int fatigueChange,
            int healthChange,
            Integer diceRoll
    ) {
    }

    record ActionResult(SaveDetail detail, ActionChanges changes, String feedback) {
    }

    record ExamResult(SaveDetail detail, ExamRecord exam, boolean newlySettled, String feedback) {
    }

    record PlayerExamCommand(String requestId, String text) {
    }

    record PlayerReadingQuestionCommand(String sceneCode, Long expectedTurnNumber) {
    }

    record PlayerReadingAnswerCommand(String questionId, String text) {
    }

    record FreeActionCommand(String requestId, String sceneCode, String text, Long expectedTurnNumber) {
    }

    record ActionContext(String saveId, String actorId, long turnNumber, String sceneCode,
                         CharacterState character, ScholarState scholar, String contextSummary) {
    }

    record SaveSummary(String saveId, String characterName, int age, int currentYear, String status,
                       LocalDateTime createdAt, String officialPosition, String officialRank,
                       String degree, List<String> titles) {
    }
}
