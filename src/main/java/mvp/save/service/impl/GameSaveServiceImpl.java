package mvp.save.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.save.entity.GameSave;
import mvp.save.mapper.GameSaveMapper;
import mvp.save.service.GameSaveService;
import org.springframework.stereotype.Service;

@Service
public class GameSaveServiceImpl extends ServiceImpl<GameSaveMapper, GameSave> implements GameSaveService {
}
