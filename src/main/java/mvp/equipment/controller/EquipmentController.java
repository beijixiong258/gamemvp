package mvp.equipment.controller;

import mvp.equipment.service.EquipmentService;
import mvp.equipment.service.EquipmentRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
public class EquipmentController {
    private final EquipmentService equipmentService;
    private final EquipmentRecordService equipmentRecordService;
}
