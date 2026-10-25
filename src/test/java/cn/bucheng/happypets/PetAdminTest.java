package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class PetAdminTest {
    @TempDir Path folder;
    private HappyPetsPlugin plugin;
    private PetService service;
    private PetData pet;
    private Player admin;
    private PetStore store;
    private MockedStatic<Bukkit> bukkit;
    private final List<Runnable> saves = new ArrayList<>();

    @BeforeEach void setup() {
        plugin = mock(HappyPetsPlugin.class);
        when(plugin.getName()).thenReturn("HappyPets");
        PetSettings settings = SettingsFixtures.settings();
        when(plugin.settings()).thenReturn(settings);
        Messages messages = mock(Messages.class);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.getName()).thenReturn("Admin");
        when(admin.hasPermission("happypets.admin")).thenReturn(true);
        pet = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "Someone else's cow");
        store = mock(PetStore.class);
        service = new PetService(plugin, store, List.of(pet));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            saves.add(invocation.getArgument(1));
            return task;
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void administratorCanEditOfflinePetsWithoutBecomingTheirOwner() {
        UUID owner = pet.owner();
        assertTrue(service.adminSet(admin, pet.id(), "bond", "8888"));
        assertTrue(service.canManage(admin, pet));
        assertEquals(8888, pet.bond());
        assertEquals(owner, pet.owner());
        assertTrue(service.owned(admin.getUniqueId()).isEmpty());
        assertEquals(List.of(pet), service.owned(owner));
    }

    @Test void administratorNeedsNoGameplayPermissionOrEnabledWorldToEdit() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", false);
        config.set("capture.disabled-worlds", List.of("world"));
        PetSettings disabled = SettingsFixtures.settings(config);
        when(plugin.settings()).thenReturn(disabled);
        assertFalse(admin.hasPermission("happypets.use"));
        assertTrue(service.adminSet(admin, pet.id(), "level", "20"));
        assertEquals(20, Progression.level(pet, disabled));
        verify(admin, never()).getWorld();
    }

    @Test void nonAdminCannotDirectlyEditEvenTheirOwnPet() {
        Player owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(pet.owner());
        when(owner.hasPermission("happypets.use")).thenReturn(true);
        assertFalse(service.adminSet(owner, pet.id(), "bond", "10000"));
        assertFalse(service.adminAdjust(owner, pet.id(), "points", 1));
        assertEquals(0, pet.bond());
        assertEquals(0, pet.points());
        assertTrue(saves.isEmpty());
    }

    @Test void levelCanBeRaisedAndLoweredWithoutCreatingUpgradePoints() {
        pet.setPoints(7);
        assertTrue(service.adminSet(admin, pet.id(), "level", "30"));
        assertEquals(30, Progression.level(pet, plugin.settings()));
        assertTrue(service.adminSet(admin, pet.id(), "level", "1"));
        assertEquals(0, pet.xp());
        assertTrue(service.adminSet(admin, pet.id(), "level", "30"));
        assertEquals(7, pet.points());
    }

    @Test void rawExperienceSetsTheCorrespondingLevelWithoutLevelRewards() {
        long xp = Progression.xpAtLevel(15, plugin.settings()) + 1;
        assertTrue(service.adminSet(admin, pet.id(), "xp", Long.toString(xp)));
        assertEquals(15, Progression.level(pet, plugin.settings()));
        assertEquals(xp, pet.xp());
        assertEquals(0, pet.points());
    }

    @Test void directStrengthEditingDoesNotSpendPointsOrUseNormalUpgradeCaps() {
        assertTrue(service.adminSet(admin, pet.id(), "speed", "1000"));
        assertTrue(service.adminSet(admin, pet.id(), "health", "500"));
        assertTrue(service.adminSet(admin, pet.id(), "jump", "300"));
        assertEquals(1000, pet.speed());
        assertEquals(500, pet.health());
        assertEquals(300, pet.jump());
        assertEquals(0, pet.points());
        assertTrue(PetMotion.speed(pet, plugin.settings(), false, false) <= plugin.settings().maxRideSpeed());
    }

    @Test void baseAttributesAcceptFiniteDecimals() {
        assertTrue(service.adminSet(admin, pet.id(), "base-health", "99.5"));
        assertTrue(service.adminSet(admin, pet.id(), "base-speed", "0.8"));
        assertEquals(99.5, pet.baseHealth());
        assertEquals(.8, pet.baseSpeed());
    }

    @Test void invalidInputNeverPartlyMutatesOrSchedulesASave() {
        String[][] inputs = {{"level", "0"}, {"level", "51"}, {"xp", "-1"}, {"xp", "9223372036854775808"},
                {"bond", "2147483648"}, {"points", "-1"}, {"speed", "1001"}, {"health", "3.5"},
                {"base-health", "0"}, {"base-health", "NaN"}, {"base-speed", "Infinity"}, {"base-speed", "-1"}, {"owner", "steal"}};
        for (String[] input : inputs) assertFalse(service.adminSet(admin, pet.id(), input[0], input[1]), Arrays.toString(input));
        assertEquals(0, pet.xp());
        assertEquals(0, pet.bond());
        assertEquals(0, pet.speed());
        assertEquals(20, pet.baseHealth());
        assertTrue(saves.isEmpty());
    }

    @Test void repeatedClickAdjustmentsCannotOverflowAndCoalesceIntoOneSave() {
        assertTrue(service.adminSet(admin, pet.id(), "xp", Long.toString(Long.MAX_VALUE)));
        assertTrue(service.adminAdjust(admin, pet.id(), "xp", Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, pet.xp());
        assertTrue(service.adminSet(admin, pet.id(), "points", "2147483647"));
        assertTrue(service.adminAdjust(admin, pet.id(), "points", 100));
        assertEquals(Integer.MAX_VALUE, pet.points());
        assertEquals(1, saves.size());
        verifyNoInteractions(store);
        saves.getFirst().run();
        verify(store, times(1)).saveAsync(anyCollection());
    }

    @Test void loweringAYoungPetsLevelImmediatelyDismountsAndRefreshesItsEntity() throws Exception {
        Mob entity = mock(Mob.class);
        map("entities").put(pet.id(), entity);
        try (MockedStatic<PetEntityState> state = mockStatic(PetEntityState.class)) {
            assertTrue(service.adminSet(admin, pet.id(), "level", "1"));
            verify(entity).eject();
            state.verify(() -> PetEntityState.configure(entity, pet, plugin.settings()));
        }
    }

    @Test void revokingAdministratorPermissionPreventsFurtherEdits() {
        assertTrue(service.adminSet(admin, pet.id(), "bond", "100"));
        when(admin.hasPermission("happypets.admin")).thenReturn(false);
        assertFalse(service.canManage(admin, pet));
        assertFalse(service.adminSet(admin, pet.id(), "bond", "999"));
        assertFalse(service.rename(admin, pet.id(), "stolen"));
        assertEquals(100, pet.bond());
    }

    @Test void administratorCanRenameSomeoneElsesPet() {
        assertTrue(service.rename(admin, pet.id(), "New name"));
        assertEquals("New name", pet.name());
        assertNotEquals(admin.getUniqueId(), pet.owner());
    }

    @Test void recallingAnotherOwnersPetDoesNotRecallTheAdministratorsOwnPet() throws Exception {
        PetData personal = new PetData(UUID.randomUUID(), admin.getUniqueId(), EntityType.PIG, "My pig");
        service = new PetService(plugin, store, List.of(pet, personal));
        Mob target = mock(Mob.class), own = mock(Mob.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        map("entities").put(pet.id(), target);
        map("entities").put(personal.id(), own);
        map("activeByOwner").put(pet.owner(), pet.id());
        map("activeByOwner").put(personal.owner(), personal.id());
        assertTrue(service.adminRecall(admin, pet.id()));
        assertFalse(service.isActive(pet.id()));
        assertTrue(service.isActive(personal.id()));
        verify(target).remove();
        verifyNoInteractions(own);
    }

    @Test void editedValuesSurviveAnActualYamlSaveAndReload() {
        PetStore actual = new PetStore(folder, Logger.getAnonymousLogger());
        actual.load();
        service = new PetService(plugin, actual, List.of(pet));
        assertTrue(service.adminSet(admin, pet.id(), "level", "25"));
        assertTrue(service.adminSet(admin, pet.id(), "bond", "8000"));
        assertTrue(service.adminSet(admin, pet.id(), "speed", "1000"));
        assertTrue(service.adminSet(admin, pet.id(), "base-health", "100.5"));
        saves.getFirst().run();
        actual.close(service.all());
        PetStore reloaded = new PetStore(folder, Logger.getAnonymousLogger());
        List<PetData> loaded = reloaded.load();
        assertEquals(1, loaded.size());
        assertEquals(pet.owner(), loaded.getFirst().owner());
        assertEquals(25, Progression.level(loaded.getFirst(), plugin.settings()));
        assertEquals(8000, loaded.getFirst().bond());
        assertEquals(1000, loaded.getFirst().speed());
        assertEquals(100.5, loaded.getFirst().baseHealth());
        reloaded.close(loaded);
    }

    @SuppressWarnings("unchecked") private Map<UUID, Object> map(String name) throws Exception {
        Field field = PetService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(service);
    }
}
