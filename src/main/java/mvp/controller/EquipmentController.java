package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.EquipmentRecordService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import cn.hutool.json.JSONObject;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
public class EquipmentController {
    private final EquipmentRecordService equipmentRecordService;

    /** 获取人物叠加后的背包；saveId为存档，characterId为玩家或NPC。 */
    @GetMapping("/{saveId}/{characterId}")
    public List<EquipmentRecordService.InventoryItem> backpack(@PathVariable String saveId, @PathVariable String characterId) {
        return equipmentRecordService.backpack(saveId, characterId);
    }

    /** 按command中的稳定请求编号执行获取或购买，重传不重复扣款发货。 */
    @PostMapping("/{saveId}/{characterId}/acquire")
    public JSONObject acquire(@PathVariable String saveId, @PathVariable String characterId,
                              @RequestBody EquipmentRecordService.AcquisitionCommand command) {
        return equipmentRecordService.acquire(saveId, characterId, command);
    }
}
