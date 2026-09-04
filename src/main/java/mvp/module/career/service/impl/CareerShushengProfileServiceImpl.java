package mvp.module.career.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.module.career.entity.CareerShushengProfile;
import mvp.module.career.mapper.CareerShushengProfileMapper;
import mvp.module.career.service.CareerShushengProfileService;
import org.springframework.stereotype.Service;

@Service
public class CareerShushengProfileServiceImpl extends ServiceImpl<CareerShushengProfileMapper, CareerShushengProfile> implements CareerShushengProfileService {
}
