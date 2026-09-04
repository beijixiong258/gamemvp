package mvp.ai;

import mvp.engine.CharacterEngine.CharacterState;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.engine.CharacterEngine.ScholarState;
import org.springframework.stereotype.Component;

import java.io.Serializable;

@Component
public class FreeActionResolver {

    private static final String PROMPT_CODE = "PROMPT_FREE_ACTION";

    private final GameClient gameClient;

    public FreeActionResolver(GameClient gameClient) {
        this.gameClient = gameClient;
    }

    /**
     * 把玩家自由行动解析为引擎驱动量和事件语义。
     *
     * @param playerText 玩家提交的自由行动原文
     * @param sceneCode 玩家当前场景编码
     * @param contextSummary 家庭、场景和相关记忆组成的补充上下文
     * @param character 行动前人物属性快照
     * @param scholar 行动前书生能力快照
     * @return 供确定性引擎使用的驱动量和事件语义
     */
    public FreeActionResolution resolve(
            String playerText,
            String sceneCode,
            String contextSummary,
            CharacterState character,
            ScholarState scholar
    ) {
        return gameClient.chat(
                PROMPT_CODE,
                buildUserMessage(playerText, sceneCode, contextSummary, character, scholar),
                FreeActionResolution.class
        );
    }

    /**
     * 把本次游戏快照组装为自由行动Resolver的用户消息。
     *
     * @param playerText 玩家自由行动原文
     * @param sceneCode 当前场景编码
     * @param contextSummary 补充上下文
     * @param character 人物属性快照
     * @param scholar 书生能力快照
     * @return 可直接放入Prompt的用户消息文本
     */
    private String buildUserMessage(
            String playerText,
            String sceneCode,
            String contextSummary,
            CharacterState character,
            ScholarState scholar
    ) {
        return """
                玩家自由行动：%s
                当前场景：%s
                补充上下文：%s

                当前人物属性：
                智力=%d，道德=%d，政治=%d，交际=%d，体能=%d，健康=%d，疲劳=%d

                当前书生能力：
                识字=%d，经义=%d，文章=%d，策论=%d，文学=%d

                请返回结构化结果：
                1. driverPatch只表达本次行动造成的原始变化量，不得直接给出结算后属性。
                2. 没有影响的数值必须填0，不得省略字段，也不得加入随机或运气修正。
                3. eventSummary只概括玩家做了什么以及直接发生的事情，不写具体数值。
                4. lifeMilestone仅表示该事件是否值得进入人物人生节点。
                """.formatted(
                playerText,
                sceneCode,
                contextSummary,
                character.characterZhili(),
                character.characterDaode(),
                character.characterZhengzhi(),
                character.characterJiaoji(),
                character.characterTineng(),
                character.characterJiankang(),
                character.characterPilao(),
                scholar.abilityShizi(),
                scholar.abilityJingyi(),
                scholar.abilityWenzhang(),
                scholar.abilityCelun(),
                scholar.abilityWenxue()
        );
    }

    public record FreeActionResolution(
            DriverPatch driverPatch,
            String eventSummary,
            boolean lifeMilestone
    ) implements Serializable {
    }
}
