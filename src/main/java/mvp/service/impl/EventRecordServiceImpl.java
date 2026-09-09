package mvp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.EventRecord;
import mvp.mapper.EventRecordMapper;
import mvp.service.EventRecordService;
import mvp.service.MemoryRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Service
public class EventRecordServiceImpl extends ServiceImpl<EventRecordMapper, EventRecord> implements EventRecordService {
    private final JsonMapper jsonMapper;
    private final MemoryRecordService memoryRecordService;

    public EventRecordServiceImpl(JsonMapper jsonMapper, MemoryRecordService memoryRecordService) {
        this.jsonMapper = jsonMapper;
        this.memoryRecordService = memoryRecordService;
    }

    /** 只有事实事件提交后才尝试生成记忆，摘要失败不撤销游戏结算。 */
    @Override
    @Transactional
    public boolean save(EventRecord event) {
        boolean saved = super.save(event);
        if (saved) {
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
    public void recordOperation(String saveId, String actorId, String requestId, Object payload,
                                String code, long turnNumber, Object result) {
        save(new EventRecord().setSaveId(saveId).setRequestId(requestId)
                .setRequestPayloadJson(JSONUtil.toJsonStr(payload)).setEventCode(code)
                .setEventSummary("已执行：" + code)
                .setRelatedCharacterIdJson(JSONUtil.toJsonStr(List.of(actorId)))
                .setOccurredTurnNumber(turnNumber).setSettlementResultJson(jsonMapper.writeValueAsString(result))
                .setLifeMilestone(false));
    }
}
