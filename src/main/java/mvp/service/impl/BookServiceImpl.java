package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.Book;
import mvp.mapper.BookMapper;
import mvp.service.BookService;
import org.springframework.stereotype.Service;

@Service
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements BookService {
}
