package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

class InventoryMenuTest {
    @Test
    void playerMenuCancelsClickAndDragTransfers() {
        HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = view(inventory, player);
        when(inventory.getHolder()).thenReturn(new PetMenus.MenuHolder(PetMenus.MenuType.PET,
            UUID.randomUUID(), UUID.randomUUID(), 0));
        when(view.getInventory(0)).thenReturn(inventory);

        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        new PetMenus(plugin).onClick(click);
        assertTrue(click.isCancelled());

        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        new PetMenus(plugin).onDrag(drag);
        verify(drag).setCancelled(true);
    }

    @Test
    void administratorClickRechecksPermissionBeforeChangingSettings() {
        HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
        Messages messages = mock(Messages.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = view(inventory, player);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        NamespacedKey actionKey = new NamespacedKey("happypets", "menu_action");
        when(plugin.menuActionKey()).thenReturn(actionKey);
        when(plugin.messages()).thenReturn(messages);
        when(player.hasPermission("happypets.admin")).thenReturn(false);
        PetMenus.MenuHolder holder = new PetMenus.MenuHolder(PetMenus.MenuType.ADMIN, playerId, null, 0);
        when(inventory.getHolder()).thenReturn(holder);
        when(view.getInventory(0)).thenReturn(inventory);
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(actionKey, PersistentDataType.STRING)).thenReturn("setting:enabled");
        when(view.getItem(0)).thenReturn(item);

        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.NOTHING);
        new PetMenus(plugin).onClick(click);

        assertTrue(click.isCancelled());
        verify(player).hasPermission("happypets.admin");
        verify(player).closeInventory();
        verify(plugin, never()).updateSetting("enabled", false);
        verify(messages).send(player, "menu.admin.unavailable");
    }

    @Test
    void administratorPetMenusRecheckPermissionOnEveryClick() {
        for (PetMenus.MenuType type : List.of(PetMenus.MenuType.ALL_PETS, PetMenus.MenuType.ADMIN_PET)) {
            HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
            Messages messages = mock(Messages.class);
            Player player = mock(Player.class);
            Inventory inventory = mock(Inventory.class);
            InventoryView view = view(inventory, player);
            UUID playerId = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(playerId);
            NamespacedKey actionKey = new NamespacedKey("happypets", "menu_action");
            when(plugin.menuActionKey()).thenReturn(actionKey);
            when(plugin.messages()).thenReturn(messages);
            when(player.hasPermission("happypets.admin")).thenReturn(false);
            PetMenus.MenuHolder holder = new PetMenus.MenuHolder(type, playerId, UUID.randomUUID(), 0);
            when(inventory.getHolder()).thenReturn(holder);
            when(view.getInventory(0)).thenReturn(inventory);
            ItemStack item = mock(ItemStack.class);
            ItemMeta meta = mock(ItemMeta.class);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            when(item.hasItemMeta()).thenReturn(true);
            when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(data);
            when(data.get(actionKey, PersistentDataType.STRING)).thenReturn(type == PetMenus.MenuType.ALL_PETS ? "pet:" + UUID.randomUUID() : "field:bond");
            when(view.getItem(0)).thenReturn(item);

            InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.NOTHING);
            new PetMenus(plugin).onClick(click);

            assertTrue(click.isCancelled());
            verify(player).hasPermission("happypets.admin");
            verify(player).closeInventory();
            verify(messages).send(player, "menu.admin.unavailable");
        }
    }

    @Test
    void allPetsPagesUseFortyFiveEntriesAndKeepOneEmptyPage() {
        assertEquals(1, PetMenus.pageCount(0));
        assertEquals(1, PetMenus.pageCount(1));
        assertEquals(1, PetMenus.pageCount(45));
        assertEquals(2, PetMenus.pageCount(46));
        assertEquals(3, PetMenus.pageCount(91));
    }

    @Test
    void chatCancelConsumesOnlyThePromptAndCancelsTimeoutOnMainThread() {
        PromptFixture fixture = renamePrompt("cancel");

        assertTrue(fixture.event().isCancelled());
        verify(fixture.timeout(), never()).cancel();
        fixture.dispatch().run();
        verify(fixture.timeout()).cancel();
        verify(fixture.messages()).send(fixture.player(), "rename.cancelled");
    }

    @Test
    void slashCommandsAreNotConsumedByRenamePrompt() {
        PromptFixture fixture = renamePrompt("/spawn");

        assertFalse(fixture.event().isCancelled());
        fixture.dispatch().run();
        verify(fixture.timeout()).cancel();
        verify(fixture.messages()).send(fixture.player(), "rename.cancelled");
    }

    @Test
    void gameGuiCancelsDragAndRejectsCallbacksAfterSessionEnds() {
        HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = view(inventory, player);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        MiniGames.GameHolder holder = new MiniGames.GameHolder(MiniGames.GameType.FIND, playerId, UUID.randomUUID());
        when(inventory.getHolder()).thenReturn(holder);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        new MiniGames(plugin).onDrag(drag);
        verify(drag).setCancelled(true);

        UUID sessionPlayerId = UUID.randomUUID();
        MiniGames.GameSession game = new MiniGames.GameSession(sessionPlayerId, UUID.randomUUID(), MiniGames.GameType.FIND);
        Map<UUID, MiniGames.GameSession> active = new java.util.HashMap<>();
        active.put(sessionPlayerId, game);
        assertTrue(game.isCurrent(active));
        assertTrue(game.finish());
        assertTrue(!game.isCurrent(active));
        assertTrue(!game.finish());

        MiniGames.GameSession replacement = new MiniGames.GameSession(sessionPlayerId, UUID.randomUUID(), MiniGames.GameType.FIND);
        active.put(sessionPlayerId, replacement);
        assertTrue(!game.isCurrent(active));
    }

    @Test
    void closeAllClosesGameSelectorSoItsIconsCannotBeTaken() {
        HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = view(inventory, player);
        MiniGames.GameHolder holder = new MiniGames.GameHolder(MiniGames.GameType.SELECT,
            UUID.randomUUID(), UUID.randomUUID());
        when(inventory.getHolder()).thenReturn(holder);
        when(player.getOpenInventory()).thenReturn(view);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            new MiniGames(plugin).closeAll();
        }

        verify(player).closeInventory();
    }

    private InventoryView view(Inventory top, Player player) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(top.getSize()).thenReturn(54);
        when(view.getPlayer()).thenReturn(player);
        return view;
    }

    private PromptFixture renamePrompt(String chat) {
        HappyPetsPlugin plugin = mock(HappyPetsPlugin.class);
        PetService pets = mock(PetService.class);
        Messages messages = mock(Messages.class);
        Player player = mock(Player.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask timeout = mock(BukkitTask.class);
        UUID playerId = UUID.randomUUID();
        PetData pet = new PetData(UUID.randomUUID(), playerId, EntityType.COW, "Moo");
        when(plugin.pets()).thenReturn(pets);
        when(plugin.messages()).thenReturn(messages);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("happypets.admin")).thenReturn(false);
        when(pets.find(pet.id())).thenReturn(pet);
        when(pets.canManage(player, pet)).thenReturn(true);
        when(pets.usable(player)).thenReturn(true);
        PetMenus menus = new PetMenus(plugin);

        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, chat, Set.of());
        ArgumentCaptor<Runnable> scheduled = ArgumentCaptor.forClass(Runnable.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskLater(same(plugin), any(Runnable.class), eq(1200L))).thenReturn(timeout);
            menus.requestRename(player, pet.id());
            when(scheduler.runTask(same(plugin), any(Runnable.class))).thenReturn(mock(BukkitTask.class));
            menus.onRenameChat(event);
            verify(scheduler).runTask(same(plugin), scheduled.capture());
        }
        return new PromptFixture(event, timeout, scheduled.getValue(), player, messages);
    }

    private record PromptFixture(AsyncPlayerChatEvent event, BukkitTask timeout, Runnable dispatch,
                                 Player player, Messages messages) { }
}
