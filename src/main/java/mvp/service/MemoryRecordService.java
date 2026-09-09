package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.EventRecord;
import mvp.entity.MemoryRecord;

public interface MemoryRecordService extends IService<MemoryRecord> {

    /** 事务提交后为实际参与者生成记忆；失败不撤销事件，后续回忆时补齐。 */
    void rememberAfterCommit(EventRecord event);

    /**
     * 读取该人物自己的压缩记忆，优先与指定人物相关的近期事件。
     * 返回有效的JSON数组，整个字符串不超过min(maxCharacters, 5000)个Unicode码点；
     * 小于2时返回空字符串。调用者应在业务事务之外调用。
     */
    String recall(String saveId, String ownerCharacterId, String relatedCharacterId, int maxCharacters);
}
