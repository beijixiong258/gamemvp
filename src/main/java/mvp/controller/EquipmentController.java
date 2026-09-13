package mvp.controller;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import mvp.service.EquipmentRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
public class EquipmentController {
    private final EquipmentRecordService equipmentRecordService;

    @GetMapping("/{saveId}/{characterId}")
    public List<EquipmentRecordService.InventoryItem> backpack(@PathVariable String saveId,
                                                               @PathVariable String characterId) {
        return equipmentRecordService.backpack(saveId, characterId);
    }

    @GetMapping("/{saveId}/scene/{sceneCode}")
    public List<EquipmentRecordService.SceneItem> sceneItems(@PathVariable String saveId,
                                                            @PathVariable String sceneCode) {
        return equipmentRecordService.sceneItems(saveId, sceneCode);
    }

    @PostMapping("/{saveId}/{characterId}/acquire")
    public JSONObject acquire(@PathVariable String saveId, @PathVariable String characterId,
                              @RequestBody EquipmentRecordService.AcquisitionCommand command) {
        return equipmentRecordService.acquire(saveId, characterId, command);
    }

    @PostMapping("/{saveId}/{characterId}/pickup")
    public JSONObject pickup(@PathVariable String saveId, @PathVariable String characterId,
                             @RequestBody EquipmentRecordService.ItemCommand command) {
        return equipmentRecordService.pickup(saveId, characterId, command);
    }

    @PostMapping("/{saveId}/{characterId}/use")
    public JSONObject use(@PathVariable String saveId, @PathVariable String characterId,
                          @RequestBody EquipmentRecordService.ItemCommand command) {
        return equipmentRecordService.use(saveId, characterId, command);
    }
}
