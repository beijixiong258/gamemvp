package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.ai.GameClient;
import mvp.engine.GameRuleConstant;
import mvp.entity.Character;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.mapper.FamilyBackgroundMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.EventRecordService;
import mvp.service.FamilyBackgroundService;
import mvp.service.RegionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FamilyBackgroundServiceImpl extends ServiceImpl<FamilyBackgroundMapper, FamilyBackground> implements FamilyBackgroundService {

    private static final String BACKGROUND_OPERATION = "CHILDHOOD_BACKGROUND";
    private static final int MAX_NARRATIVE_LENGTH = 1_000;

    private final GameSaveMapper gameSaveMapper;
    private final CharacterService characterService;
    private final RegionService regionService;
    private final EventRecordService eventRecordService;
    private final GameClient gameClient;
    private final PlatformTransactionManager transactionManager;

    @Override
    public FamilyBackground prepareNarrative(String saveId) {
        if (saveId == null || saveId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供存档ID");
        }
        TransactionTemplate readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        BackgroundSnapshot before = Objects.requireNonNull(readTransaction.execute(status -> loadSnapshot(saveId, false)));
        if (before.generated()) {
            return before.background();
        }

        // 只把不会随游戏推进变化的出生、家庭信息提供给模型，生成过程不持有存档锁。
        ChildhoodBackgroundOutput output = gameClient.chat("PROMPT_CHILDHOOD_BACKGROUND", before.facts().toString(),
                ChildhoodBackgroundOutput.class);
        String narrative = output.backgroundSummary() == null ? "" : output.backgroundSummary().strip();
        if (narrative.isBlank() || narrative.codePointCount(0, narrative.length()) > MAX_NARRATIVE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI童年背景为空或过长，请重试");
        }

        return new TransactionTemplate(transactionManager).execute(status -> {
            BackgroundSnapshot current = loadSnapshot(saveId, true);
            if (current.generated()) {
                return current.background();
            }
            if (!Objects.equals(before.background().getId(), current.background().getId())
                    || !before.facts().equals(current.facts())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "初始家庭信息已变化，请重试生成童年背景");
            }
            FamilyBackground background = current.background().setBackgroundSummary(narrative);
            if (!updateById(background)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "家庭背景保存失败，请重试");
            }
            // 标记与正文在同一事务保存；事件只存索引，不复制叙事，也不作为新的人生节点。
            eventRecordService.recordOperation(saveId, current.playerId(), requestId(saveId), payload(saveId),
                    BACKGROUND_OPERATION, 0L, Map.of("familyBackgroundId", background.getId(), "generated", true));
            return background;
        });
    }

    /** 由调用方的短事务保护；最终保存前按现有写入约定锁定同一存档。 */
    private BackgroundSnapshot loadSnapshot(String saveId, boolean lock) {
        GameSave gameSave = gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery().eq(GameSave::getId, saveId)
                .last(lock ? "FOR UPDATE" : ""));
        if (gameSave == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        FamilyBackground background = lambdaQuery().eq(FamilyBackground::getSaveId, saveId).one();
        Character player = characterService.lambdaQuery().eq(Character::getSaveId, saveId)
                .eq(Character::getType, 1).one();
        if (background == null || player == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存档缺少玩家或初始家庭背景");
        }
        JSONObject cached = eventRecordService.replay(saveId, requestId(saveId), payload(saveId));
        if (cached != null) {
            if (!Objects.equals(background.getId(), cached.getStr("familyBackgroundId"))
                    || !Boolean.TRUE.equals(cached.getBool("generated"))
                    || background.getBackgroundSummary() == null || background.getBackgroundSummary().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "童年背景生成记录与家庭背景不一致");
            }
            return new BackgroundSnapshot(background, player.getId(), null, true);
        }
        Region birthRegion = regionService.getById(player.getBirthRegionId());
        if (birthRegion == null || gameSave.getBirthYear() == null || background.getInitialWealth() == null
                || background.getBackgroundSummary() == null || background.getBackgroundSummary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存档缺少完整的出生或初始家庭信息");
        }
        List<JSONObject> parents = characterService.lambdaQuery().eq(Character::getSaveId, saveId)
                .eq(Character::getType, 0).in(Character::getNpcCode, "NPC_FUQIN", "NPC_MUQIN")
                .orderByAsc(Character::getNpcCode).list().stream()
                .map(parent -> new JSONObject().set("id", parent.getId()).set("npcCode", parent.getNpcCode())
                        .set("name", parent.getName())).toList();
        JSONObject facts = new JSONObject()
                .set("player", new JSONObject().set("name", player.getName())
                        .set("birthRegionId", birthRegion.getId()).set("birthRegionName", birthRegion.getRegionName())
                        .set("birthYear", gameSave.getBirthYear()))
                .set("childhood", new JSONObject().set("startAge", 0).set("endAge", GameRuleConstant.SCHOOL_START_AGE)
                        .set("schoolStartYear", gameSave.getBirthYear() + GameRuleConstant.SCHOOL_START_AGE)
                        .set("initialFamilyWealth", background.getInitialWealth())
                        .set("initialFamilySummary", background.getBackgroundSummary()))
                .set("parents", parents);
        return new BackgroundSnapshot(background, player.getId(), facts, false);
    }

    private String requestId(String saveId) {
        return BACKGROUND_OPERATION + "/" + saveId;
    }

    private Map<String, String> payload(String saveId) {
        return Map.of("operation", BACKGROUND_OPERATION, "saveId", saveId);
    }

    private record BackgroundSnapshot(FamilyBackground background, String playerId, JSONObject facts, boolean generated) {
    }

    public record ChildhoodBackgroundOutput(String backgroundSummary) {
    }
}
