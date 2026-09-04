package mvp.module.book.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.module.book.entity.Book;
import mvp.module.book.mapper.BookMapper;
import mvp.module.book.service.BookService;
import org.springframework.stereotype.Service;

@Service
public class BookServiceImpl extends ServiceImpl<BookMapper, Book> implements BookService {
}
