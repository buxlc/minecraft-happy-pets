package cn.bucheng.happypets;

import static org.mockito.Mockito.*;

import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.EntityType;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.mockito.MockedStatic;
import java.util.UUID;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PetListenerTest {
    HappyPetsPlugin plugin;
    PetService service;
    PetListener listener;
    @BeforeEach void setup() {
        plugin = mock(HappyPetsPlugin.class);
        service = mock(PetService.class);
        when(plugin.pets()).thenReturn(service);
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("protection.invulnerable", false);
        PetSettings settings = SettingsFixtures.settings(config);
        when(plugin.settings()).thenReturn(settings);
        listener = new PetListener(plugin);
    }

    @Test void horseInventoryCannotBeOpenedToBypassPetStorage() {
        AbstractHorse horse = mock(AbstractHorse.class);
        when(service.isPet(horse)).thenReturn(true);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(horse);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        listener.nativeInventory(event);
        verify(event).setCancelled(true);
    }

    @Test void ordinaryHorseInventoriesRemainAccessible() {
        AbstractHorse horse = mock(AbstractHorse.class);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(horse);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        listener.nativeInventory(event);
        verify(event, never()).setCancelled(true);
    }

    @Test void petProjectilesCannotDamagePlayersEvenWithInvulnerabilityDisabled() {
        Mob pet = mock(Mob.class);
        Projectile projectile = mock(Projectile.class);
        Player target = mock(Player.class);
        when(projectile.getShooter()).thenReturn(pet);
        when(service.isPet(pet)).thenReturn(true);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(projectile);
        when(event.getEntity()).thenReturn(target);
        listener.damage(event);
        verify(event).setCancelled(true);
    }

    @Test void transformationsCannotSplitOneStoredPetIntoMultipleEntities() {
        Mob pet = mock(Mob.class);
        when(service.isPet(pet)).thenReturn(true);
        EntityTransformEvent event = mock(EntityTransformEvent.class);
        when(event.getEntity()).thenReturn(pet);
        listener.transform(event);
        verify(event).setCancelled(true);
    }

    @Test void aSneakingAdministratorCanOpenAnotherOwnersPetWithoutGameplayPermission() {
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.isSneaking()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(admin.getInventory()).thenReturn(inventory);
        Mob mob = mock(Mob.class);
        PetData pet = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "Cow");
        when(service.isPet(mob)).thenReturn(true);
        when(service.petFor(mob)).thenReturn(pet);
        when(service.canAdmin(admin)).thenReturn(true);
        PetMenus menus = mock(PetMenus.class);
        when(plugin.menus()).thenReturn(menus);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(admin, mob, EquipmentSlot.HAND);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class))).thenReturn(mock(BukkitTask.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            listener.interact(event);
        }
        org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());
        verify(menus).openPet(admin, pet.id());
        verify(service, never()).usable(admin);
        verify(service, never()).ride(admin, pet.id());
    }

    @Test void aNormalPlayerCannotOpenAnotherOwnersPetBySneaking() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isSneaking()).thenReturn(true);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        Mob mob = mock(Mob.class);
        PetData pet = new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "Cow");
        when(service.isPet(mob)).thenReturn(true);
        when(service.petFor(mob)).thenReturn(pet);
        when(service.usable(player)).thenReturn(true);
        PetMenus menus = mock(PetMenus.class);
        Messages messages = mock(Messages.class);
        when(plugin.menus()).thenReturn(menus);
        when(plugin.messages()).thenReturn(messages);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class))).thenReturn(mock(BukkitTask.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            listener.interact(new PlayerInteractEntityEvent(player, mob, EquipmentSlot.HAND));
        }
        verifyNoInteractions(menus);
        verify(messages).send(player, "error.not-owner");
    }
}
