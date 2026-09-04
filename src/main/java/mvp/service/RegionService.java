package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import java.util.List;
import mvp.entity.Region;

public interface RegionService extends IService<Region> {

    /**
     * 查询MVP出生页可展示的惠州非城区县级节点。
     *
     * @return 按同级顺序排列的出生地区列表
     */
    List<Region> listMvpBirthRegions();
}
