package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.CareerProfileService;
import mvp.service.CareerProfileShushengService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/career")
public class CareerController {
    private final CareerProfileService careerProfileService;
    private final CareerProfileShushengService careerProfileShushengService;
}
