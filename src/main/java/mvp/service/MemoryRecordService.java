package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.EventRecord;
import mvp.entity.MemoryRecord;

public interface MemoryRecordService extends IService<MemoryRecord> {

    /** 事务提交后为实际参与者评估分级记忆；失败不撤销事件，归档记录不再补齐。 */
    void rememberAfterCommit(EventRecord event);

    /**
     * 按当前游戏回合归档到期记忆，读取本人的关系、约定、重要往事与有关近事；读取不续期。
     * 返回有效的JSON数组，整个字符串不超过min(maxCharacters, 5000)个Unicode码点；
     * 小于2时返回空字符串。调用者应在业务事务之外调用。
     */
    String recall(String saveId, String ownerCharacterId, String relatedCharacterId, int maxCharacters);
}
