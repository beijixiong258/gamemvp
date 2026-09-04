package mvp.module.book.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.module.book.entity.BookRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookRecordMapper extends BaseMapper<BookRecord> {
}
