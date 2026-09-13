package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.Equipment;

import java.util.Map;

public interface EquipmentService extends IService<Equipment> {

    /** 按稳定编码同步固定目录，保留已有定义ID和全部实例历史。 */
    Map<String, Equipment> importDefinitions();
}
