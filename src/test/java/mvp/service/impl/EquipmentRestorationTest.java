package mvp.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import mvp.engine.CharacterEngine;
import mvp.entity.Character;
import mvp.entity.Equipment;
import mvp.entity.EquipmentRecord;
import mvp.entity.GameSave;
import mvp.mapper.GameSaveMapper;
import mvp.mapper.EquipmentMapper;
import mvp.service.CharacterService;
import mvp.service.EquipmentRecordService;
import mvp.service.EquipmentService;
import mvp.service.EventRecordService;
import mvp.utils.ClasspathJsonLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquipmentRestorationTest {
    private EquipmentRecordServiceImpl service;
    private CharacterService characters;
    private EventRecordService events;
    private ClasspathJsonLoader loader;
    private Character player;
    private EquipmentRecord item;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var saves = mock(GameSaveMapper.class);
        when(saves.selectOne(any())).thenReturn(new GameSave().setId("save")
                .setStatus("STUDYING").setTotalTurnNumber(0L));
        player = new Character().setId("player").setSaveId("save").setType(1).setEnabled(true)
                .setCharacterZhili(20).setCharacterDaode(20).setCharacterZhengzhi(20)
                .setCharacterJiaoji(20).setCharacterTineng(20).setCharacterJiankang(75)
                .setCharacterPilao(40).setSickTurnsRemaining(0);
        item = new EquipmentRecord().setId("item").setSaveId("save").setCharacterId("player")
                .setEquipmentId("definition").setStatus("OWNED").setQuantity(1);
        Equipment definition = new Equipment().setId("definition").setEquipmentCode("ITEM_TEST")
                .setEquipmentName("测试恢复物品").setEquipmentType("CONSUMABLE");
        characters = mock(CharacterService.class);
        when(characters.getById("player")).thenReturn(player);
        var equipment = mock(EquipmentService.class);
        when(equipment.getById("definition")).thenReturn(definition);
        when(equipment.listByIds(anyCollection())).thenReturn(List.of(definition));
        events = mock(EventRecordService.class);
        loader = mock(ClasspathJsonLoader.class);
        when(loader.load("game/scene.json", JSONObject.class)).thenReturn(new JSONObject().set("scene", List.of(
                new JSONObject().set("sceneCode", "scene").set("availableActionCode", List.of("FREE_ACTION")))));
        service = spy(new EquipmentRecordServiceImpl(saves, characters, equipment, events, loader, new CharacterEngine()));
        doReturn(item).when(service).getById("item");
        doReturn(true).when(service).updateById(any(EquipmentRecord.class));
        LambdaQueryChainWrapper<EquipmentRecord> query = mock(LambdaQueryChainWrapper.class, call ->
                switch (call.getMethod().getName()) {
                    case "eq", "gt", "orderByAsc" -> call.getMock();
                    default -> RETURNS_DEFAULTS.answer(call);
                });
        when(query.list()).thenReturn(List.of(item));
        doReturn(query).when(service).lambdaQuery();
        LambdaUpdateChainWrapper<EquipmentRecord> update = mock(LambdaUpdateChainWrapper.class, call ->
                switch (call.getMethod().getName()) {
                    case "eq", "isNotNull", "le", "set" -> call.getMock();
                    default -> RETURNS_DEFAULTS.answer(call);
                });
        doReturn(update).when(service).lambdaUpdate();
    }

    @Test
    void configuredItemCapsRecoveryAtTwentyAndReplayDoesNotChargeAgain() {
        configure(new JSONObject().set("staminaRecovery", 99).set("healthCost", 7));
        assertTrue(service.backpack("save", "player").get(0).usable());
        JSONObject result = service.use("save", "player", command());
        assertEquals(34, player.getStamina());
        assertEquals(68, player.getCharacterJiankang());
        assertEquals(0, item.getQuantity());
        assertEquals(-20, result.getInt("fatigueChange"));
        assertEquals(-7, result.getInt("healthChange"));
        assertNull(player.getMajorActionTurn());
        when(events.replay(eq("save"), eq("use"), any())).thenReturn(result);
        assertSame(result, service.use("save", "player", command()));
        assertEquals(68, player.getCharacterJiankang());
        verify(characters, times(1)).updateById(any(Character.class));
    }

    @Test
    void fullStaminaRejectsWithoutSpendingItemOrHealth() {
        configure(new JSONObject().set("staminaRecovery", 20).set("healthCost", 7));
        player.setCharacterPilao(0);
        assertFalse(service.backpack("save", "player").get(0).usable());
        var failure = assertThrows(ResponseStatusException.class, () -> service.use("save", "player", command()));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals(75, player.getCharacterJiankang());
        assertEquals(1, item.getQuantity());
        verify(characters, never()).updateById(any(Character.class));
        verify(service, never()).updateById(any(EquipmentRecord.class));
    }

    @Test
    void unconfiguredItemCannotBeUsed() {
        configure(null);
        assertFalse(service.backpack("save", "player").get(0).usable());
        assertThrows(ResponseStatusException.class, () -> service.use("save", "player", command()));
        assertEquals(14, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        assertEquals(1, item.getQuantity());
    }

    @Test
    void configuredHealthCostCannotHealOrDropHealthBelowZero() {
        configure(new JSONObject().set("staminaRecovery", 10).set("healthCost", -5));
        service.use("save", "player", command());
        assertEquals(24, player.getStamina());
        assertEquals(75, player.getCharacterJiankang());
        item.setQuantity(1).setStatus("OWNED");
        configure(new JSONObject().set("staminaRecovery", 10).set("healthCost", 200));
        JSONObject result = service.use("save", "player", new EquipmentRecordService.ItemCommand("use2", "item", "scene", 0L));
        assertEquals(34, player.getStamina());
        assertEquals(0, player.getCharacterJiankang());
        assertEquals(-75, result.getInt("healthChange"));
    }

    @Test
    void importAcceptsConfiguredConsumableBeyondTeaAndWritesItsDefinition() {
        configureCatalog(20, 7);
        EquipmentMapper mapper = mock(EquipmentMapper.class);
        when(mapper.insert(any(Equipment.class))).thenAnswer(call -> {
            call.<Equipment>getArgument(0).setId("imported");
            return 1;
        });
        Equipment definition = importer(mapper).importDefinitions().get("ITEM_TEST");
        assertEquals("imported", definition.getId());
        assertEquals("CONSUMABLE", definition.getEquipmentType());
        assertEquals("RELIEVE_FATIGUE", definition.getUseEffectCode());
        verify(mapper).insert(same(definition));
    }

    @Test
    void importRejectsFractionalMissingOrOutOfRangeRestorationBeforeWriting() {
        EquipmentMapper mapper = mock(EquipmentMapper.class);
        EquipmentServiceImpl importer = importer(mapper);
        Object[][] invalid = {{new BigDecimal("10.5"), 0}, {21, 0}, {-1, 0}, {10, new BigDecimal("0.5")},
                {10, -1}, {10, 101}, {10, null}};
        for (Object[] values : invalid) {
            configureCatalog(values[0], values[1]);
            assertThrows(IllegalStateException.class, importer::importDefinitions);
        }
        configureCatalog(10, 0);
        JSONObject catalog = loader.load("game/equipment.json", JSONObject.class);
        catalog.getJSONArray("equipment").getJSONObject(0).remove("restoration");
        assertThrows(IllegalStateException.class, importer::importDefinitions);
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    private EquipmentServiceImpl importer(EquipmentMapper mapper) {
        EquipmentServiceImpl importer = spy(new EquipmentServiceImpl(loader));
        ReflectionTestUtils.setField(importer, "baseMapper", mapper);
        LambdaQueryChainWrapper<Equipment> query = mock(LambdaQueryChainWrapper.class, call ->
                "eq".equals(call.getMethod().getName()) ? call.getMock() : RETURNS_DEFAULTS.answer(call));
        doReturn(query).when(importer).lambdaQuery();
        return importer;
    }

    private void configureCatalog(Object staminaRecovery, Object healthCost) {
        JSONObject definition = new JSONObject().set("equipmentCode", "ITEM_TEST")
                .set("equipmentName", "测试恢复物品").set("equipmentType", "CONSUMABLE")
                .set("rarityCode", "COMMON").set("price", 20).set("supplierNpcCode", "NPC_SHANGREN")
                .set("description", "配置驱动的消耗品。").set("useEffectCode", "RELIEVE_FATIGUE")
                .set("restoration", new JSONObject().set("staminaRecovery", staminaRecovery).set("healthCost", healthCost));
        when(loader.load("game/equipment.json", JSONObject.class))
                .thenReturn(new JSONObject().set("equipment", List.of(definition)));
    }

    private void configure(JSONObject restoration) {
        JSONObject definition = new JSONObject().set("equipmentCode", "ITEM_TEST");
        if (restoration != null) {
            definition.set("restoration", restoration);
        }
        when(loader.load("game/equipment.json", JSONObject.class))
                .thenReturn(new JSONObject().set("equipment", List.of(definition)));
    }

    private EquipmentRecordService.ItemCommand command() {
        return new EquipmentRecordService.ItemCommand("use", "item", "scene", 0L);
    }
}
