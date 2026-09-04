package mvp.module.book.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.module.book.entity.Book;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookMapper extends BaseMapper<Book> {
}
