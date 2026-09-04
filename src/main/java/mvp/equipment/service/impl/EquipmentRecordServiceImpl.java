package mvp.equipment.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.equipment.entity.EquipmentRecord;
import mvp.equipment.mapper.EquipmentRecordMapper;
import mvp.equipment.service.EquipmentRecordService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentRecordServiceImpl extends ServiceImpl<EquipmentRecordMapper, EquipmentRecord> implements EquipmentRecordService {
}
