package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.EventRecord;

import java.util.List;

public interface EventRecordService extends IService<EventRecord> {
    /**
     * 查询同一请求的既有结果，并拒绝编号被不同参数复用。
     *
     * @param requestId 客户端在重传时保持不变的请求编号
     * @return 原结果；尚未结算时为空
     */
    JSONObject replay(String saveId, String requestId, Object payload);

    /** 在调用方事务中记录已经完成的操作。 */
    void recordOperation(String saveId, String actorId, String requestId, Object payload,
                         String code, long turnNumber, Object result);

    /** 参与者必须是主流程实际确认在场或知情的人物，且属于同一存档。 */
    void recordOperation(String saveId, String actorId, String requestId, Object payload,
                         String code, long turnNumber, Object result, List<String> participantIds);
}
