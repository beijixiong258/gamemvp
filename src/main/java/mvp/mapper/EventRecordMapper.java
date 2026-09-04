package mvp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.entity.EventRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventRecordMapper extends BaseMapper<EventRecord> {
}
