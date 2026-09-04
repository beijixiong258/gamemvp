package mvp.character.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.character.entity.Character;
import mvp.character.mapper.CharacterMapper;
import mvp.character.service.CharacterService;
import org.springframework.stereotype.Service;

@Service
public class CharacterServiceImpl extends ServiceImpl<CharacterMapper, Character> implements CharacterService {
}
