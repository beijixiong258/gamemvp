package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.ScholarState;
import mvp.engine.CharacterEngine;
import mvp.entity.Book;
import mvp.entity.GameSave;

import java.math.BigDecimal;
import java.util.List;

public interface BookService extends IService<Book> {

    /**
     * 开局时只导入缺失的公共书籍定义，不向任何人物发放物品。
     */
    void importDefinitions();

    /**
     * 汇总人物全部书籍的学识，持有多本同名物品不重复累计。
     *
     * @param characterId 人物ID
     * @return 不封顶的学识总量
     */
    BigDecimal totalKnowledge(String characterId);

    /**
     * 查询全部已导入书籍的阅读进度、持有数量和当前阅读条件，不写入数据。
     *
     * @param save 当前存档快照
     * @param characterId 玩家人物ID
     * @param character 当前人物状态
     * @param scholar 当前书生领域能力
     * @return 按书籍编码排序的书目及不可读原因
     */
    List<LibraryBook> listLibrary(GameSave save, String characterId, CharacterState character, ScholarState scholar);

    /**
     * 在外层行动事务中结算阅读并保存进度，人物、领域能力和时间由调用方保存。
     *
     * @param save 行动前存档快照
     * @param characterId 玩家人物ID
     * @param character 行动前人物状态
     * @param scholar 行动前书生领域能力
     * @param bookCode 所读书籍的稳定装备编码
     * @param settlementTurnNumber 本次行动推进后的总回合编号
     * @return 书名及引擎计算出的完整阅读结果
     */
    BookActionResult read(
            GameSave save, String characterId, CharacterState character,
            ScholarState scholar, String bookCode, long settlementTurnNumber
    );

    record LibraryBook(
            String bookCode,
            String bookName,
            String equipmentId,
            int currentProgress,
            int requiredProgress,
            int totalReadTurnNumber,
            boolean completed,
            boolean readable,
            List<String> blockedReasons,
            String knowledgeSummary,
            int ownedQuantity,
            int price,
            String supplierNpcCode,
            int totalKnowledge,
            BigDecimal acquiredKnowledge
    ) {
    }

    record BookActionResult(String bookName, CharacterEngine.ReadBookResult settlement) {
    }
}
