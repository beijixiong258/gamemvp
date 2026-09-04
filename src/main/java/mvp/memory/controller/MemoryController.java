package mvp.memory.controller;

import mvp.memory.service.MemoryRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/memory")
public class MemoryController {
    private final MemoryRecordService memoryRecordService;
}
