package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.CareerProfile;
import mvp.mapper.CareerProfileMapper;
import mvp.service.CareerProfileService;
import org.springframework.stereotype.Service;

@Service
public class CareerProfileServiceImpl extends ServiceImpl<CareerProfileMapper, CareerProfile> implements CareerProfileService {
}
