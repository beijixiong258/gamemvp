package mvp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.EventRecord;
import mvp.mapper.EventRecordMapper;
import mvp.service.EventRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class EventRecordServiceImpl extends ServiceImpl<EventRecordMapper, EventRecord> implements EventRecordService {
    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public void recordOperation(String saveId, String actorId, String requestId, Object payload,
                                String code, long turnNumber, Object result) {
        save(new EventRecord().setSaveId(saveId).setRequestId(requestId)
                .setRequestPayloadJson(JSONUtil.toJsonStr(payload)).setEventCode(code)
                .setEventSummary("已执行：" + code)
                .setRelatedCharacterIdJson(JSONUtil.toJsonStr(List.of(actorId)))
                .setOccurredTurnNumber(turnNumber).setSettlementResultJson(JSONUtil.toJsonStr(result))
                .setLifeMilestone(false));
    }
}
