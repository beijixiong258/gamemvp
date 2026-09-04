package mvp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import mvp.engine.CharacterEngine;
import mvp.engine.TurnEngine;
import mvp.entity.CareerProfile;
import mvp.entity.CareerProfileShusheng;
import mvp.entity.Character;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.mapper.GameSaveMapper;
import mvp.service.CareerProfileService;
import mvp.service.CareerProfileShushengService;
import mvp.service.CharacterService;
import mvp.service.FamilyBackgroundService;
import mvp.service.GameSaveService;
import mvp.service.RegionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GameSaveServiceImpl extends ServiceImpl<GameSaveMapper, GameSave> implements GameSaveService {

    private static final int PLAYER_CHARACTER_TYPE = 1;
    private static final String SCHOLAR_CAREER_CODE = "CAREER_SHUSHENG";
    private static final String ACTIVE_CAREER_STATUS = "ACTIVE";
    private static final String INITIAL_AVAILABLE_STAGE_JSON = "[\"STUDYING\"]";

    private final CharacterEngine characterEngine;
    private final TurnEngine turnEngine;
    private final CharacterService characterService;
    private final FamilyBackgroundService familyBackgroundService;
    private final CareerProfileService careerProfileService;
    private final CareerProfileShushengService careerProfileShushengService;
    private final RegionService regionService;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Region> listBirthRegions() {
        return regionService.listMvpBirthRegions();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public StartLifeResult startLife(StartLifeCommand command) {
        Region birthRegion = listBirthRegions().stream()
                .filter(region -> Objects.equals(region.getId(), command.birthRegionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("出生地区不在MVP可选范围内"));

        CharacterEngine.StartLifeResult characterResult = characterEngine.startLife(birthRegion.getId());
        TurnEngine.TurnState turnState = turnEngine.startLife();

        GameSave gameSave = new GameSave()
                .setStatus(turnState.status())
                .setBirthYear(turnState.birthYear())
                .setCurrentYear(turnState.currentYear())
                .setCurrentMonth(turnState.currentMonth())
                .setTurnInMonth(turnState.turnInMonth())
                .setAge(turnState.age())
                .setTotalTurnNumber(turnState.totalTurnNumber())
                .setGrowthStage(turnState.growthStage());
        save(gameSave);

        CharacterEngine.CharacterState characterState = characterResult.character();
        Character character = new Character()
                .setSaveId(gameSave.getId())
                .setName(command.characterName())
                .setType(PLAYER_CHARACTER_TYPE)
                .setBirthRegionId(characterResult.birthRegionId())
                .setCurrentRegionId(characterResult.currentRegionId())
                .setCharacterZhili(characterState.characterZhili())
                .setCharacterDaode(characterState.characterDaode())
                .setCharacterZhengzhi(characterState.characterZhengzhi())
                .setCharacterJiaoji(characterState.characterJiaoji())
                .setCharacterTineng(characterState.characterTineng())
                .setCharacterJiankang(characterState.characterJiankang())
                .setCharacterPilao(characterState.characterPilao())
                .setCurrentMainCareerCode(SCHOLAR_CAREER_CODE)
                .setBirthday(turnState.birthYear() + "-01-01")
                .setAvailableStageCodeJson(INITIAL_AVAILABLE_STAGE_JSON)
                .setEnabled(true);
        characterService.save(character);

        FamilyBackground familyBackground = new FamilyBackground()
                .setSaveId(gameSave.getId())
                .setInitialWealth(characterResult.initialFamilyWealth())
                .setBackgroundSummary(characterResult.familyBackgroundSummary());
        familyBackgroundService.save(familyBackground);

        CareerProfile careerProfile = new CareerProfile()
                .setCharacterId(character.getId())
                .setCareerCode(SCHOLAR_CAREER_CODE)
                .setUnlockTurnNumber(turnState.totalTurnNumber())
                .setLastActiveTurnNumber(turnState.totalTurnNumber())
                .setStatus(ACTIVE_CAREER_STATUS);
        careerProfileService.save(careerProfile);

        CharacterEngine.ScholarState scholarState = characterResult.scholar();
        CareerProfileShusheng scholarProfile = new CareerProfileShusheng()
                .setCareerProfileId(careerProfile.getId())
                .setAbilityShizi(scholarState.abilityShizi())
                .setAbilityJingyi(scholarState.abilityJingyi())
                .setAbilityWenzhang(scholarState.abilityWenzhang())
                .setAbilityCelun(scholarState.abilityCelun())
                .setAbilityWenxue(scholarState.abilityWenxue());
        careerProfileShushengService.save(scholarProfile);

        return new StartLifeResult(
                gameSave,
                character,
                familyBackground,
                careerProfile,
                scholarProfile
        );
    }
}
