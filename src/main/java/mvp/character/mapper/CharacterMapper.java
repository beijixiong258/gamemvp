package mvp.character.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import mvp.character.entity.Character;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CharacterMapper extends BaseMapper<Character> {
}
