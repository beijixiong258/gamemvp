package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.ExamRecord;
import mvp.mapper.ExamRecordMapper;
import mvp.service.ExamRecordService;
import org.springframework.stereotype.Service;

@Service
public class ExamRecordServiceImpl extends ServiceImpl<ExamRecordMapper, ExamRecord> implements ExamRecordService {
}
