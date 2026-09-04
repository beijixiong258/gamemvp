package mvp.module.exam.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.module.exam.ExamRecord;
import mvp.module.exam.mapper.ExamRecordMapper;
import mvp.module.exam.service.ExamRecordService;
import org.springframework.stereotype.Service;

@Service
public class ExamRecordServiceImpl extends ServiceImpl<ExamRecordMapper, ExamRecord> implements ExamRecordService {
}
