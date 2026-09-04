package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.BookRecord;
import mvp.mapper.BookRecordMapper;
import mvp.service.BookRecordService;
import org.springframework.stereotype.Service;

@Service
public class BookRecordServiceImpl extends ServiceImpl<BookRecordMapper, BookRecord> implements BookRecordService {
}
