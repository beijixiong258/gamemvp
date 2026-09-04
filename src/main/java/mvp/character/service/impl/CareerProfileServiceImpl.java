package mvp.character.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.character.entity.CareerProfile;
import mvp.character.mapper.CareerProfileMapper;
import mvp.character.service.CareerProfileService;
import org.springframework.stereotype.Service;

@Service
public class CareerProfileServiceImpl extends ServiceImpl<CareerProfileMapper, CareerProfile> implements CareerProfileService {
}
