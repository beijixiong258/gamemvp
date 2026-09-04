package mvp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.entity.Book;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookMapper extends BaseMapper<Book> {
}
