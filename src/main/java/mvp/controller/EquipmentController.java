package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.EquipmentRecordService;
import mvp.service.EquipmentService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/equipment")
public class EquipmentController {
    private final EquipmentService equipmentService;
    private final EquipmentRecordService equipmentRecordService;
}
