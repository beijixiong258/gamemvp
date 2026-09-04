package mvp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import mvp.entity.CareerProfile;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;

import java.util.List;

public interface GameSaveService extends IService<GameSave> {

    /**
     * 查询开始人生页面允许选择的出生地区。
     *
     * @return 按显示顺序排列的惠州县级地区
     */
    List<Region> listBirthRegions();

    /**
     * 根据固定开局规则创建六岁玩家及完整初始存档。
     *
     * @param command 玩家姓名和出生地区
     * @return 已持久化的存档、玩家、家庭背景和书生职业档案
     */
    StartLifeResult startLife(StartLifeCommand command);

    record StartLifeCommand(
            String characterName,
            String birthRegionId
    ) {
    }

    record StartLifeResult(
            GameSave save,
            Character character,
            FamilyBackground familyBackground,
            CareerProfile careerProfile,
            CareerProfileShusheng scholarProfile
    ) {
    }
}
