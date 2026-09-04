package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.CharacterService;
import mvp.service.FamilyBackgroundService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/character")
public class CharacterController {
    private final CharacterService characterService;
    private final FamilyBackgroundService familyBackgroundService;
}
