package mvp.character.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.character.entity.FamilyProfile;
import mvp.character.mapper.FamilyProfileMapper;
import mvp.character.service.FamilyProfileService;
import org.springframework.stereotype.Service;

@Service
public class FamilyProfileServiceImpl extends ServiceImpl<FamilyProfileMapper, FamilyProfile> implements FamilyProfileService {
}
