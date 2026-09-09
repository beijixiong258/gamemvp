package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import mvp.entity.Region;
import mvp.mapper.RegionMapper;
import mvp.service.RegionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RegionServiceImpl extends ServiceImpl<RegionMapper, Region> implements RegionService {

    // 与schema.sql中惠州节点的主键一致，出生范围只依赖父子关系。
    private static final String HUIZHOU_REGION_ID = "3";

    @Override
    public List<Region> listMvpBirthRegions() {
        Region huizhou = getById(HUIZHOU_REGION_ID);
        if (huizhou == null || !Boolean.TRUE.equals(huizhou.getEnabled())) {
            return List.of();
        }
        return lambdaQuery()
                .eq(Region::getParentId, huizhou.getId())
                .eq(Region::getEnabled, true)
                .list();
    }
}
