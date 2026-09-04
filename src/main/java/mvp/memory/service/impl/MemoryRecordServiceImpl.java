package mvp.memory.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.memory.entity.MemoryRecord;
import mvp.memory.mapper.MemoryRecordMapper;
import mvp.memory.service.MemoryRecordService;
import org.springframework.stereotype.Service;

@Service
public class MemoryRecordServiceImpl extends ServiceImpl<MemoryRecordMapper, MemoryRecord> implements MemoryRecordService {
}
