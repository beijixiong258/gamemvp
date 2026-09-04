package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.util.List;
import mvp.entity.Region;
import mvp.mapper.RegionMapper;
import mvp.service.RegionService;
import org.springframework.stereotype.Service;

@Service
public class RegionServiceImpl extends ServiceImpl<RegionMapper, Region> implements RegionService {

    private static final String HUIZHOU_REGION_CODE = "REGION_HUIZHOU";
    private static final String COUNTY_LEVEL = "COUNTY";

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Region> listMvpBirthRegions() {
        Region huizhou = lambdaQuery()
                .select(Region::getId)
                .eq(Region::getRegionCode, HUIZHOU_REGION_CODE)
                .eq(Region::getEnabled, true)
                .one();
        if (huizhou == null) {
            return List.of();
        }
        return lambdaQuery()
                .eq(Region::getParentId, huizhou.getId())
                .eq(Region::getRegionLevel, COUNTY_LEVEL)
                .eq(Region::getEnabled, true)
                .orderByAsc(Region::getSortOrder)
                .list();
    }
}
