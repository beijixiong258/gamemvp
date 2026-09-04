package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.EquipmentRecord;
import mvp.mapper.EquipmentRecordMapper;
import mvp.service.EquipmentRecordService;
import org.springframework.stereotype.Service;

@Service
public class EquipmentRecordServiceImpl extends ServiceImpl<EquipmentRecordMapper, EquipmentRecord> implements EquipmentRecordService {
}
