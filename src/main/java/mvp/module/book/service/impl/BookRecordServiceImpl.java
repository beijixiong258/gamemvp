package mvp.module.book.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.module.book.entity.BookRecord;
import mvp.module.book.mapper.BookRecordMapper;
import mvp.module.book.service.BookRecordService;
import org.springframework.stereotype.Service;

@Service
public class BookRecordServiceImpl extends ServiceImpl<BookRecordMapper, BookRecord> implements BookRecordService {
}
