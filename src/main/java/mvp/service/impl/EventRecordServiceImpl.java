package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.EventRecord;
import mvp.mapper.EventRecordMapper;
import mvp.service.EventRecordService;
import org.springframework.stereotype.Service;

@Service
public class EventRecordServiceImpl extends ServiceImpl<EventRecordMapper, EventRecord> implements EventRecordService {
}
