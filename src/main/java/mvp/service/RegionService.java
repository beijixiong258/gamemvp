package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import java.util.List;
import mvp.entity.Region;

public interface RegionService extends IService<Region> {

    /**
     * 查询MVP出生页可展示的惠州直属子节点，当前为博罗和海丰。
     *
     * @return 启用的出生地区列表，不指定显示顺序
     */
    List<Region> listMvpBirthRegions();
}
