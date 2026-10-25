package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PetServiceTest {
    HappyPetsPlugin plugin;
    PetSettings settings;
    Messages messages;
    PetStore store;
    Player player;
    World world;
    PetData pet;
    PetService pets;

    @BeforeEach void setup() {
        plugin = mock(HappyPetsPlugin.class);
        when(plugin.getName()).thenReturn("HappyPets");
        settings = SettingsFixtures.settings();
        when(plugin.settings()).thenReturn(settings);
        messages = mock(Messages.class);
        when(plugin.messages()).thenReturn(messages);
        store = mock(PetStore.class);
        player = mock(Player.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("happypets.use")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 0, 65, 0));
        pet = new PetData(UUID.randomUUID(), player.getUniqueId(), EntityType.COW, "Moo");
        pets = new PetService(plugin, store, List.of(pet));
    }

    @Test void bossesAndNonMobsCannotBeCaptured() {
        for (EntityType type : List.of(EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.ELDER_GUARDIAN,
                EntityType.WARDEN, EntityType.ARMOR_STAND, EntityType.PLAYER, EntityType.ITEM)) {
            assertFalse(PetService.capturableType(type), type.name());
        }
        for (EntityType type : List.of(EntityType.COW, EntityType.GHAST, EntityType.CREAKING,
                EntityType.VILLAGER, EntityType.COD, EntityType.BREEZE)) assertTrue(PetService.capturableType(type));
        Mob boss = mock(Mob.class);
        when(boss.getType()).thenReturn(EntityType.WITHER);
        assertFalse(pets.capture(player, boss));
        verifyNoInteractions(store);
    }

    @Test void anotherPlayerCannotRenameOrSpendTheOwnersPoints() {
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.getWorld()).thenReturn(world);
        when(other.hasPermission("happypets.use")).thenReturn(true);
        pet.setPoints(5);
        assertFalse(pets.rename(other, pet.id(), "stolen"));
        assertFalse(pets.upgrade(other, pet.id(), "speed"));
        assertEquals("Moo", pet.name());
        assertEquals(5, pet.points());
    }

    @Test void revokedPermissionRejectsMutations() {
        when(player.hasPermission("happypets.use")).thenReturn(false);
        assertFalse(pets.rename(player, pet.id(), "changed"));
        assertEquals("Moo", pet.name());
        verifyNoInteractions(store);
    }

    @Test void renameRejectsColorsControlCharactersAndOverlongNames() {
        for (String value : List.of(" ", "bad\nname", "§cspoof", "&cspoof", "宠".repeat(33))) assertFalse(pets.rename(player, pet.id(), value));
        assertTrue(pets.rename(player, pet.id(), " 小奶牛 🐮 "));
        assertEquals("小奶牛 🐮", pet.name());
    }

    @Test void feedingConsumesExactlyOneRealFoodAndCannotBypassCooldown() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack food = markedFood((byte) 1);
        when(food.getAmount()).thenReturn(3);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inventory.getItem(4)).thenReturn(food);
        assertTrue(pets.feed(player, pet.id()));
        assertFalse(pets.feed(player, pet.id()));
        verify(food, times(1)).setAmount(2);
        assertEquals(settings.feedXp(), pet.xp());
        assertEquals(settings.feedBond(), pet.bond());
        assertTrue(pet.lastFeed() > 0);
    }

    @Test void aRenamedVanillaItemDoesNotCountAsPetFood() {
        assertFalse(pets.isFood(null));
        assertFalse(pets.isFood(markedFood((byte) 0)));
        assertTrue(pets.isFood(markedFood((byte) 1)));
    }

    @Test void failedFeedDoesNotStartTheCooldown() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        assertFalse(pets.feed(player, pet.id()));
        assertEquals(0, pet.lastFeed());
        assertEquals(0, pet.xp());
    }

    @Test void gamesRequireANearbyActivePetAndIssueASingleRewardTicket() throws Exception {
        assertFalse(pets.beginPlay(player, pet.id()));
        assertEquals(0, pet.lastPlay());
        Mob entity = mock(Mob.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 1, 65, 1));
        activeEntities().put(pet.id(), entity);
        assertTrue(pets.beginPlay(player, pet.id()));
        assertFalse(pets.beginPlay(player, pet.id()));
        pets.rewardPlay(player, pet.id(), 1);
        pets.rewardPlay(player, pet.id(), 1);
        assertEquals(settings.playXp(), pet.xp());
        assertEquals(settings.playBond(), pet.bond());
    }

    @Test void forgedGameCompletionWithoutStartingCannotGiveRewards() {
        pets.rewardPlay(player, pet.id(), 2);
        assertEquals(0, pet.xp());
        assertEquals(0, pet.bond());
    }

    @Test void gameplayRewardsDoNotReduceAnAdministratorsHigherBondValue() throws Exception {
        pet.setBond(8000);
        Mob entity = mock(Mob.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 1, 65, 1));
        activeEntities().put(pet.id(), entity);
        assertTrue(pets.beginPlay(player, pet.id()));
        pets.rewardPlay(player, pet.id(), 1);
        assertEquals(8000, pet.bond());
    }

    @Test void youngPetsCannotBeMounted() {
        assertFalse(pets.ride(player, pet.id()));
        verify(player, never()).getCurrentInput();
    }

    @Test void spendingUpgradePointsChecksStatAndBalance() {
        assertFalse(pets.upgrade(player, pet.id(), "speed"));
        pet.setPoints(2);
        assertFalse(pets.upgrade(player, pet.id(), "unknown"));
        assertTrue(pets.upgrade(player, pet.id(), "speed"));
        assertEquals(1, pet.speed());
        assertEquals(1, pet.points());
    }

    private ItemStack markedFood(byte marker) {
        ItemStack food = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(food.hasItemMeta()).thenReturn(true);
        when(food.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.BYTE))).thenReturn(marker);
        return food;
    }

    @SuppressWarnings("unchecked") private Map<UUID, Mob> activeEntities() throws Exception {
        Field field = PetService.class.getDeclaredField("entities");
        field.setAccessible(true);
        return (Map<UUID, Mob>) field.get(pets);
    }
}
