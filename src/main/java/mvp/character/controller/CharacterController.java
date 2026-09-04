package mvp.character.controller;

import mvp.character.service.CharacterService;
import mvp.character.service.CareerProfileService;
import mvp.character.service.FamilyProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/character")
public class CharacterController {
    private final CharacterService characterService;
    private final CareerProfileService careerProfileService;
    private final FamilyProfileService familyProfileService;
}
