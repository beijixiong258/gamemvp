package mvp.save.controller;

import mvp.save.service.GameSaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/save")
public class GameSaveController {
    private final GameSaveService gameSaveService;
}
