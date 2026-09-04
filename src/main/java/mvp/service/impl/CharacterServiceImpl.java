package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.Character;
import mvp.mapper.CharacterMapper;
import mvp.service.CharacterService;
import org.springframework.stereotype.Service;

@Service
public class CharacterServiceImpl extends ServiceImpl<CharacterMapper, Character> implements CharacterService {
}
