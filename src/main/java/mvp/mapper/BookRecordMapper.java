package mvp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.entity.BookRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BookRecordMapper extends BaseMapper<BookRecord> {
}
