package cn.bucheng.happypets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/** Runs small, self-contained inventory games that reward one pet interaction. */
public final class MiniGames implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final Material[] FOODS = {
        Material.APPLE, Material.CARROT, Material.COOKIE, Material.BREAD, Material.MELON_SLICE,
        Material.POTATO, Material.BEETROOT, Material.SWEET_BERRIES, Material.GLOW_BERRIES
    };

    private final HappyPetsPlugin plugin;
    private final Map<UUID, GameSession> sessions = new HashMap<>();

    public MiniGames(HappyPetsPlugin plugin) { this.plugin = plugin; }

    /** Opens the game selection screen after checking feature access and pet ownership. */
    public void open(Player player, UUID petId) {
        if (!plugin.pets().usable(player)) return;
        if (!plugin.settings().gamesEnabled()) {
            plugin.messages().send(player, "games.unavailable");
            return;
        }
        PetData pet = ownedPet(player, petId);
        if (pet == null) {
            plugin.messages().send(player, "pet.not-owner");
            return;
        }
        GameHolder holder = new GameHolder(GameType.SELECT, player.getUniqueId(), petId);
        Inventory inventory = inventory(holder, 27, text("games.menu.title", "name", pet.name()));
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(10, action(Material.CARROT, text("games.menu.find"), text("games.menu.find-lore"), "start:FIND"));
        inventory.setItem(13, action(Material.PAPER, text("games.menu.memory"), text("games.menu.memory-lore"), "start:MEMORY"));
        inventory.setItem(16, action(Material.CLOCK, text("games.menu.reaction"), text("games.menu.reaction-lore"), "start:REACTION"));
        inventory.setItem(22, action(Material.BARRIER, text("menu.list.close"), null, "close"));
        player.openInventory(inventory);
    }

    /** Cancels all active games and their scheduled callbacks during shutdown or reload. */
    public void closeAll() {
        for (GameSession session : new ArrayList<>(sessions.values())) end(session, false, "games.closed", 1.0, false);
        sessions.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GameHolder) player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GameHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (!player.getUniqueId().equals(holder.playerId())) return;
        ItemStack stack = event.getCurrentItem();
        if (stack == null || !stack.hasItemMeta()) return;
        String action = stack.getItemMeta().getPersistentDataContainer().get(plugin.menuActionKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (holder.type() == GameType.SELECT) {
            if (action != null && action.startsWith("start:")) {
                GameType type;
                try { type = GameType.valueOf(action.substring(6)); }
                catch (IllegalArgumentException ignored) { player.closeInventory(); return; }
                if (type == GameType.SELECT) { player.closeInventory(); return; }
                deferFromMenu(player, holder, () -> start(player, holder.petId(), type));
            }
            else if ("close".equals(action)) player.closeInventory();
            return;
        }
        GameSession session = sessions.get(player.getUniqueId());
        if (session == null || session.finished || !session.petId.equals(holder.petId()) || session.type != holder.type()) return;
        if (action == null) return;
        deferFromMenu(player, holder, () -> {
            if (!session.isCurrent(sessions)) return;
            switch (session.type) {
                case FIND -> clickFind(session, player, action);
                case MEMORY -> clickMemory(session, player, action);
                case REACTION -> clickReaction(session, player, action);
                default -> { }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GameHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GameSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) end(session, false, "games.closed", 1.0, false);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !(event.getInventory().getHolder() instanceof GameHolder holder)) return;
        if (holder.type() == GameType.SELECT) return;
        GameSession session = sessions.get(player.getUniqueId());
        if (session == null || session.finished || session.type != holder.type() || !session.petId.equals(holder.petId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) return;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof GameHolder open
                && open.petId().equals(session.petId) && open.type() == session.type) return;
            end(session, false, "games.closed", 1.0, false);
        });
    }

    private void start(Player player, UUID petId, GameType type) {
        if (!plugin.pets().usable(player) || !plugin.settings().gamesEnabled()) {
            plugin.messages().send(player, "games.unavailable");
            player.closeInventory();
            return;
        }
        PetData pet = ownedPet(player, petId);
        if (pet == null || !plugin.pets().beginPlay(player, petId)) {
            plugin.messages().send(player, pet == null ? "pet.not-owner" : "games.cooldown");
            player.closeInventory();
            return;
        }
        GameSession previous = sessions.get(player.getUniqueId());
        if (previous != null) end(previous, false, "games.closed", 1.0, false);
        GameSession session = new GameSession(player.getUniqueId(), petId, type);
        sessions.put(player.getUniqueId(), session);
        session.timeout = Bukkit.getScheduler().runTaskLater(plugin,
            () -> end(session, false, "games.timeout", 1.0, true), 20L * 45L);
        switch (type) {
            case FIND -> openFind(player, session);
            case MEMORY -> openMemory(player, session);
            case REACTION -> openReaction(player, session);
            default -> { }
        }
    }

    private void openFind(Player player, GameSession session) {
        session.phase = Phase.INPUT;
        Inventory inventory = inventory(new GameHolder(GameType.FIND, player.getUniqueId(), session.petId), 45,
            text("games.find.title"));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        int targetIndex = ThreadLocalRandom.current().nextInt(GRID.length);
        session.target = FOODS[ThreadLocalRandom.current().nextInt(FOODS.length)];
        inventory.setItem(4, item(session.target, text("games.find.target", "item", itemName(session.target)), null));
        List<Material> distractions = new ArrayList<>(List.of(FOODS));
        distractions.remove(session.target);
        for (int index = 0; index < GRID.length; index++) {
            Material food = index == targetIndex ? session.target : distractions.get(ThreadLocalRandom.current().nextInt(distractions.size()));
            inventory.setItem(GRID[index], action(food, text("games.find.option", "item", itemName(food)), null, "find:" + index));
        }
        player.openInventory(inventory);
        player.sendMessage(Messages.color(text("games.find.instruction", "item", itemName(session.target))));
    }

    private void openMemory(Player player, GameSession session) {
        session.phase = Phase.WATCH;
        session.sequence = MemorySequence.random();
        Inventory inventory = inventory(new GameHolder(GameType.MEMORY, player.getUniqueId(), session.petId), 45,
            text("games.memory.title"));
        player.openInventory(inventory);
        player.sendMessage(Messages.color(text("games.memory.instruction")));
        showMemoryStep(player, session, 0);
    }

    private void showMemoryStep(Player player, GameSession session, int sequenceIndex) {
        if (!session.isCurrent(sessions) || !player.isOnline()) return;
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof GameHolder holder) || holder.type() != GameType.MEMORY || !holder.petId().equals(session.petId)) return;
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        if (sequenceIndex >= session.sequence.size()) {
            session.phase = Phase.INPUT;
            for (int index = 0; index < GRID.length; index++) inventory.setItem(GRID[index], action(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                text("games.memory.tile", "number", index + 1), null, "memory:" + index));
            return;
        }
        int tile = session.sequence.tile(sequenceIndex);
        inventory.setItem(GRID[tile], item(Material.LIME_STAINED_GLASS_PANE,
            text("games.memory.flash", "number", tile + 1), null));
        session.timer = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!session.isCurrent(sessions) || !player.isOnline()) return;
            Inventory open = player.getOpenInventory().getTopInventory();
            if (!(open.getHolder() instanceof GameHolder current) || current.type() != GameType.MEMORY || !current.petId().equals(session.petId)) return;
            open.setItem(GRID[tile], item(Material.GRAY_STAINED_GLASS_PANE, " ", null));
            session.timer = Bukkit.getScheduler().runTaskLater(plugin, () -> showMemoryStep(player, session, sequenceIndex + 1), 6L);
        }, 8L);
    }

    private void openReaction(Player player, GameSession session) {
        session.phase = Phase.WAIT;
        Inventory inventory = inventory(new GameHolder(GameType.REACTION, player.getUniqueId(), session.petId), 27,
            text("games.reaction.title"));
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(13, action(Material.REDSTONE_BLOCK, text("games.reaction.wait"), null, "react"));
        player.openInventory(inventory);
        player.sendMessage(Messages.color(text("games.reaction.instruction")));
        int delayTicks = ThreadLocalRandom.current().nextInt(30, 81);
        session.timer = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!session.isCurrent(sessions) || !player.isOnline()) return;
            Inventory open = player.getOpenInventory().getTopInventory();
            if (!(open.getHolder() instanceof GameHolder holder) || holder.type() != GameType.REACTION || !holder.petId().equals(session.petId)) return;
            session.phase = Phase.READY;
            session.readyAtNanos = System.nanoTime();
            open.setItem(13, action(Material.LIME_CONCRETE, text("games.reaction.ready"), null, "react"));
        }, delayTicks);
    }

    private void clickFind(GameSession session, Player player, String action) {
        if (!action.startsWith("find:")) return;
        int index = parseIndex(action.substring(5));
        if (index < 0 || index >= GRID.length) return;
        ItemStack clicked = player.getOpenInventory().getTopInventory().getItem(GRID[index]);
        if (clicked == null || clicked.getType() != session.target) {
            end(session, false, "games.find.fail", 1.0, true);
            return;
        }
        end(session, true, "games.find.success", 1.0, true);
    }

    private void clickMemory(GameSession session, Player player, String action) {
        if (session.phase != Phase.INPUT || !action.startsWith("memory:")) return;
        int tile = parseIndex(action.substring(7));
        if (tile < 0 || tile >= GRID.length) return;
        if (!session.sequence.press(tile)) {
            end(session, false, "games.memory.fail", 1.0, true);
            return;
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.3f);
        if (session.sequence.complete()) end(session, true, "games.memory.success", 1.15, true);
    }

    private void clickReaction(GameSession session, Player player, String action) {
        if (!action.equals("react")) return;
        if (session.phase != Phase.READY) {
            end(session, false, "games.reaction.early", 1.0, true);
            return;
        }
        double seconds = (System.nanoTime() - session.readyAtNanos) / 1_000_000_000.0;
        double multiplier = Math.max(0.65, Math.min(1.35, 1.5 - seconds * 0.3));
        end(session, true, "games.reaction.success", multiplier, true, "seconds", String.format(java.util.Locale.ROOT, "%.2f", seconds));
    }

    private void end(GameSession session, boolean success, String key, double multiplier, boolean notify, Object... placeholders) {
        if (!session.finish()) return;
        sessions.remove(session.playerId, session);
        cancelTasks(session);
        Player player = Bukkit.getPlayer(session.playerId);
        if (success && player != null) plugin.pets().rewardPlay(player, session.petId, multiplier);
        if (player != null && player.isOnline()) {
            if (notify) plugin.messages().send(player, key, placeholders);
            if (notify && plugin.ready() && player.getOpenInventory().getTopInventory().getHolder() instanceof GameHolder current
                && current.petId().equals(session.petId) && current.type() == session.type) {
                player.closeInventory();
                if (plugin.pets().find(session.petId) != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && plugin.pets().find(session.petId) != null) {
                            plugin.menus().openPet(player, session.petId);
                        }
                    });
                }
            }
        }
    }

    private void deferFromMenu(Player player, GameHolder current, Runnable open) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory().getHolder() != current) return;
            open.run();
        });
    }

    private void cancelTasks(GameSession session) {
        if (session.timeout != null) session.timeout.cancel();
        if (session.timer != null) session.timer.cancel();
    }

    private PetData ownedPet(Player player, UUID petId) {
        PetData pet = petId == null ? null : plugin.pets().find(petId);
        return pet != null && pet.owner().equals(player.getUniqueId()) ? pet : null;
    }

    private Inventory inventory(GameHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, Messages.color(title));
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack action(Material material, String name, String lore, String value) {
        ItemStack stack = item(material, name, lore);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(plugin.menuActionKey(), org.bukkit.persistence.PersistentDataType.STRING, value);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(Material material, String name, String lore) {
        ItemStack stack = new ItemStack(material == null || material.isAir() ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Messages.color(name));
            if (lore != null && !lore.isBlank()) meta.setLore(List.of(Messages.color(lore).split("\\n", -1)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void fill(Inventory inventory, Material material) {
        ItemStack filler = item(material, " ", null);
        for (int index = 0; index < inventory.getSize(); index++) inventory.setItem(index, filler.clone());
    }

    private String text(String key, Object... placeholders) { return plugin.messages().text(key, placeholders); }

    private String itemName(Material material) { return text("item." + material.name().toLowerCase(java.util.Locale.ROOT)); }

    private int parseIndex(String raw) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return -1; }
    }

    enum GameType { SELECT, FIND, MEMORY, REACTION }
    private enum Phase { WAIT, READY, WATCH, INPUT }

    static final class GameHolder implements InventoryHolder {
        private final GameType type;
        private final UUID playerId;
        private final UUID petId;
        private Inventory inventory;
        GameHolder(GameType type, UUID playerId, UUID petId) { this.type = type; this.playerId = playerId; this.petId = petId; }
        private GameType type() { return type; }
        private UUID playerId() { return playerId; }
        private UUID petId() { return petId; }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    static final class GameSession {
        private final UUID playerId;
        private final UUID petId;
        private final GameType type;
        private boolean finished;
        private Phase phase;
        private Material target;
        private MemorySequence sequence;
        private long readyAtNanos;
        private BukkitTask timeout;
        private BukkitTask timer;
        GameSession(UUID playerId, UUID petId, GameType type) { this.playerId = playerId; this.petId = petId; this.type = type; }
        boolean isCurrent(Map<UUID, GameSession> active) { return !finished && active.get(playerId) == this; }
        boolean finish() {
            if (finished) return false;
            finished = true;
            return true;
        }
    }
}
