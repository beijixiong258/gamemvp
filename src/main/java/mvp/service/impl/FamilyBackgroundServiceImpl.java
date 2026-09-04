package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.FamilyBackground;
import mvp.mapper.FamilyBackgroundMapper;
import mvp.service.FamilyBackgroundService;
import org.springframework.stereotype.Service;

@Service
public class FamilyBackgroundServiceImpl extends ServiceImpl<FamilyBackgroundMapper, FamilyBackground> implements FamilyBackgroundService {
}
