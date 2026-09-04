package mvp.equipment.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.equipment.entity.Equipment;
import mvp.equipment.mapper.EquipmentMapper;
import mvp.equipment.service.EquipmentService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, Equipment> implements EquipmentService {
}
