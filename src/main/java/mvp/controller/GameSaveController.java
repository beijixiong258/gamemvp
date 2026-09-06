package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.entity.Region;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.SaveDetail;
import mvp.service.GameSaveService.StartLifeCommand;
import mvp.service.GameSaveService.StartLifeResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** 返回新开局/读档页面使用的存档概要，不加载完整事件。 */
    @GetMapping
    public List<GameSaveService.SaveSummary> listSaves() {
        return gameSaveService.listSaves();
    }

    /** 已有存档显式补齐NPC和教材定义，不自动获取任何物品。 */
    @PostMapping("/{saveId}/content")
    public void prepareContent(@PathVariable String saveId) {
        gameSaveService.prepareContent(saveId);
    }

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

    /**
     * 读取存档当前状态，用于刷新页面或继续游戏。
     *
     * @param saveId 存档ID
     * @return 人物、领域档案、书籍、考试和人生节点
     */
    @GetMapping("/{saveId}")
    public SaveDetail loadDetail(@PathVariable String saveId) {
        return gameSaveService.loadDetail(saveId);
    }
}
