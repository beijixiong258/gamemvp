package mvp.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.EventRecord;

public interface EventRecordService extends IService<EventRecord> {
    /**
     * 查询同一请求的既有结果，并拒绝编号被不同参数复用。
     *
     * @param saveId 存档ID
     * @param requestId 客户端在重传时保持不变的请求编号
     * @param payload 本次业务参数
     * @return 原结果；尚未结算时为空
     */
    JSONObject replay(String saveId, String requestId, Object payload);

    /**
     * 在调用方事务中记录已经完成的操作。
     *
     * @param saveId 存档ID
     * @param actorId 行为人物ID
     * @param requestId 请求编号
     * @param payload 原始参数
     * @param code 行为编码
     * @param turnNumber 结算回合
     * @param result 已完成结果
     */
    void recordOperation(String saveId, String actorId, String requestId, Object payload,
                         String code, long turnNumber, Object result);
}
