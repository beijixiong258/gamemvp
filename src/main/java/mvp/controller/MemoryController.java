package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.MemoryRecordService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/memory")
public class MemoryController {
    private final MemoryRecordService memoryRecordService;
}
