package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.MemoryRecord;
import mvp.mapper.MemoryRecordMapper;
import mvp.service.MemoryRecordService;
import org.springframework.stereotype.Service;

@Service
public class MemoryRecordServiceImpl extends ServiceImpl<MemoryRecordMapper, MemoryRecord> implements MemoryRecordService {
}
