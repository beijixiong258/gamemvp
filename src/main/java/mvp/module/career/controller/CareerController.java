package mvp.module.career.controller;

import mvp.character.service.CareerProfileService;
import mvp.module.career.service.CareerShushengProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/career")
public class CareerController {
    private final CareerProfileService careerProfileService;
    private final CareerShushengProfileService careerShushengProfileService;
}
