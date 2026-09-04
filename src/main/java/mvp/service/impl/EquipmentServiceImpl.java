package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.Equipment;
import mvp.mapper.EquipmentMapper;
import mvp.service.EquipmentService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, Equipment> implements EquipmentService {
}
