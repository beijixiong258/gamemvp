package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.Character;
import mvp.entity.GameSave;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

public interface CharacterService extends IService<Character> {

    /** 只读返回可见NPC；到期但仍参与活跃对话的人物继续保留，不续期、不创建。 */
    List<Character> listVisibleNpcs(String saveId, long turnNumber);

    /** 固定NPC遵循场景配置，动态NPC使用程序保存的实际场景。 */
    List<Character> listPresentNpcs(String saveId, String sceneCode, long turnNumber);

    /** 核对人物启用状态、生命周期和实际场景，活跃对话保护到期的动态人物。 */
    boolean isPresent(Character character, String sceneCode, long turnNumber);

    /** 在存档锁下归档到期的动态身份；保留行及所有历史引用，活跃对话中的人物除外。 */
    void archiveExpired(String saveId, long turnNumber);

    /**
     * 在调用方完成请求回放、快照校验并持有存档锁的事务内应用本次NPC意图。
     * settledSave提供结算后的时间，observedTurnNumber/observedNpcIds限定模型实际见过的人物。
     * 新建身份最多2个，总意图最多4个；仅真实参与者续期或升级，固定NPC仅确认参与。
     */
    NpcChanges applyNpcIntents(GameSave settledSave, Character actor, String sceneCode, String requestId,
                              long observedTurnNumber, Set<String> observedNpcIds, List<NpcIntent> intents);

    record NpcIntent(String characterId, String name, Integer age, String description, String personality,
                     String level, Integer retentionTurns, String reason, boolean participated) implements Serializable {
    }

    record NpcChanges(List<Character> characters, List<String> participantIds) {
    }
}
