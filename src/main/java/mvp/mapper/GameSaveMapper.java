package mvp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.entity.GameSave;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GameSaveMapper extends BaseMapper<GameSave> {
}
