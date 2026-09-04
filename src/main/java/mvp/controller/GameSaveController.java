package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.entity.Region;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.StartLifeCommand;
import mvp.service.GameSaveService.StartLifeResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/save")
public class GameSaveController {

    private final GameSaveService gameSaveService;

    /**
     * 返回开始人生页面允许选择的出生地区。
     *
     * @return 惠州下启用的县级地区
     */
    @GetMapping("/birth-regions")
    public List<Region> listBirthRegions() {
        return gameSaveService.listBirthRegions();
    }

    /**
     * 创建六岁玩家和完整初始存档。
     *
     * @param command 玩家姓名和出生地区ID
     * @return 创建后的开局聚合结果
     */
    @PostMapping("/start")
    public StartLifeResult startLife(@RequestBody StartLifeCommand command) {
        return gameSaveService.startLife(command);
    }
}
