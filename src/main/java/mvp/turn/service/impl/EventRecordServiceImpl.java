package mvp.turn.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.turn.entity.EventRecord;
import mvp.turn.mapper.EventRecordMapper;
import mvp.turn.service.EventRecordService;
import org.springframework.stereotype.Service;

@Service
public class EventRecordServiceImpl extends ServiceImpl<EventRecordMapper, EventRecord> implements EventRecordService {
}
