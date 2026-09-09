package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.FamilyBackground;

public interface FamilyBackgroundService extends IService<FamilyBackground> {
    /**
     * 独立生成并保存六岁入学前的童年叙事；已生成时返回原背景，不再次调用模型。
     * 不改变存档时间、人物属性或初始财富，生成失败后可使用同一存档重试。
     *
     * @param saveId 已存在的存档ID
     * @return 带童年叙事的初始家庭背景
     */
    FamilyBackground prepareNarrative(String saveId);
}
