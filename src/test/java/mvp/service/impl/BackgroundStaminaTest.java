package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import mvp.ai.GameClient;
import mvp.entity.Character;
import mvp.entity.FamilyBackground;
import mvp.entity.GameSave;
import mvp.entity.Region;
import mvp.mapper.GameSaveMapper;
import mvp.service.CharacterService;
import mvp.service.EventRecordService;
import mvp.service.RegionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundStaminaTest {
    private FamilyBackgroundServiceImpl service;
    private GameClient ai;
    private EventRecordService events;
    private CharacterService characters;
    private Character player;
    private FamilyBackground background;

    @BeforeEach
    void setup() {
        var saves = mock(GameSaveMapper.class);
        when(saves.selectOne(any())).thenReturn(new GameSave().setId("save").setBirthYear(1541)
                .setStatus("STUDYING").setTotalTurnNumber(0L));
        player = new Character().setId("player").setSaveId("save").setBirthRegionId("region")
                .setName("测试").setCharacterZhili(20).setCharacterDaode(20).setCharacterZhengzhi(20)
                .setCharacterJiaoji(20).setCharacterTineng(20).setCharacterJiankang(75)
                .setCharacterPilao(0).setSickTurnsRemaining(0);
        background = new FamilyBackground().setId("family").setSaveId("save")
                .setInitialWealth(100000).setBackgroundSummary("已有家庭事实");
        characters = mock(CharacterService.class);
        doReturn(chain(player)).when(characters).lambdaQuery();
        var regions = mock(RegionService.class);
        when(regions.getById("region")).thenReturn(new Region().setId("region").setRegionName("博罗"));
        events = mock(EventRecordService.class);
        ai = mock(GameClient.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = spy(new FamilyBackgroundServiceImpl(saves, characters, regions, events, ai,
                transactions));
        doReturn(chain(background)).when(service).lambdaQuery();
        doReturn(true).when(service).updateById(any(FamilyBackground.class));
    }

    @Test
    void successfulBackgroundAndCachedReadAreFree() {
        when(ai.chat(anyString(), anyString(), eq(FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput.class)))
                .thenReturn(new FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput("童年故事"));
        assertSame(background, service.prepareNarrative("save"));
        assertEquals(54, player.getStamina());
        when(events.replay(anyString(), anyString(), any())).thenReturn(new JSONObject()
                .set("familyBackgroundId", "family").set("generated", true));
        service.prepareNarrative("save");
        assertEquals(54, player.getStamina());
        verify(ai, times(1)).chat(anyString(), anyString(), any());
        verify(characters, never()).updateById(any(Character.class));
    }

    @Test
    void failedAiDoesNotCharge() {
        when(ai.chat(anyString(), anyString(), any())).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, () -> service.prepareNarrative("save"));
        assertEquals(54, player.getStamina());
        verify(characters, never()).updateById(any(Character.class));
        verify(service, never()).updateById(any(FamilyBackground.class));
    }

    @Test
    void zeroStaminaStillAllowsFreeBackgroundGeneration() {
        player.setCharacterPilao(player.getMaxStamina());
        when(ai.chat(anyString(), anyString(), eq(FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput.class)))
                .thenReturn(new FamilyBackgroundServiceImpl.ChildhoodBackgroundOutput("童年故事"));
        assertSame(background, service.prepareNarrative("save"));
        assertEquals(0, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        verify(characters, never()).updateById(any(Character.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> LambdaQueryChainWrapper<T> chain(T one) {
        LambdaQueryChainWrapper<T> query = mock(LambdaQueryChainWrapper.class, call -> switch (call.getMethod().getName()) { case "eq", "last", "orderByAsc", "in" -> call.getMock(); default -> RETURNS_DEFAULTS.answer(call); });
        when(query.one()).thenReturn(one);
        when(query.list()).thenReturn(List.of());
        return query;
    }
}
