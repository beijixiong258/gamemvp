package mvp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.Character;
import mvp.entity.EventRecord;
import mvp.entity.GameSave;
import mvp.mapper.CharacterMapper;
import mvp.mapper.EventRecordMapper;
import mvp.mapper.GameSaveMapper;
import mvp.service.EventRecordService;
import mvp.service.MemoryRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class EventRecordServiceImpl extends ServiceImpl<EventRecordMapper, EventRecord> implements EventRecordService {
    private final JsonMapper jsonMapper;
    private final MemoryRecordService memoryRecordService;
    private final GameSaveMapper gameSaveMapper;
    private final CharacterMapper characterMapper;

    public EventRecordServiceImpl(JsonMapper jsonMapper, MemoryRecordService memoryRecordService,
                                  GameSaveMapper gameSaveMapper, CharacterMapper characterMapper) {
        this.jsonMapper = jsonMapper;
        this.memoryRecordService = memoryRecordService;
        this.gameSaveMapper = gameSaveMapper;
        this.characterMapper = characterMapper;
    }

    /** 只有事实事件提交后才尝试生成记忆，摘要失败不撤销游戏结算。 */
    @Override
    @Transactional
    public boolean save(EventRecord event) {
        // 同一存档先锁后插入，数据库自增顺序与本存档实际提交顺序一致。
        GameSave save = gameSaveMapper.selectOne(Wrappers.<GameSave>lambdaQuery()
                .eq(GameSave::getId, event.getSaveId()).last("FOR UPDATE"));
        if (save == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        }
        boolean saved = super.save(event);
        if (saved) {
            // UUID仍是主键，自增的非主键字段需在插入后显式读回。
            EventRecord inserted = baseMapper.selectById(event.getId());
            if (inserted == null || inserted.getEventSequence() == null || inserted.getEventSequence() <= 0) {
                throw new IllegalStateException("事件顺序未分配");
            }
            event.setEventSequence(inserted.getEventSequence());
            memoryRecordService.rememberAfterCommit(event);
        }
        return saved;
    }

    @Override
    public JSONObject replay(String saveId, String requestId, Object payload) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供不超过120字符的稳定requestId");
        }
        EventRecord record = lambdaQuery().eq(EventRecord::getSaveId, saveId)
                .eq(EventRecord::getRequestId, requestId).one();
        if (record == null) {
            return null;
        }
        if (!JSONUtil.parseObj(record.getRequestPayloadJson()).equals(JSONUtil.parseObj(JSONUtil.toJsonStr(payload)))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一requestId不能用于不同操作");
        }
        return JSONUtil.parseObj(record.getSettlementResultJson());
    }

    @Override
    @Transactional
    public void recordOperation(String saveId, String actorId, String requestId, Object payload,
                                String code, long turnNumber, Object result) {
        recordOperation(saveId, actorId, requestId, payload, code, turnNumber, result, List.of());
    }

    @Override
    @Transactional
    public void recordOperation(String saveId, String actorId, String requestId, Object payload,
                                String code, long turnNumber, Object result, List<String> participantIds) {
        Set<String> participants = new LinkedHashSet<>();
        participants.add(actorId);
        if (participantIds != null) {
            participants.addAll(participantIds);
        }
        for (String id : participants) {
            Character character = id == null ? null : characterMapper.selectById(id);
            if (character == null || !Objects.equals(saveId, character.getSaveId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件参与者必须属于当前存档");
            }
        }
        save(new EventRecord().setSaveId(saveId).setRequestId(requestId)
                .setRequestPayloadJson(JSONUtil.toJsonStr(payload)).setEventCode(code)
                .setEventSummary("已执行：" + code)
                .setRelatedCharacterIdJson(JSONUtil.toJsonStr(participants))
                .setOccurredTurnNumber(turnNumber).setSettlementResultJson(jsonMapper.writeValueAsString(result))
                .setLifeMilestone(false));
    }
}
