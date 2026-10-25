package cn.bucheng.happypets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/** Player and administrator inventory menus for HappyPets. */
public final class PetMenus implements Listener {
    private static final String ADMIN_PERMISSION = "happypets.admin";
    private static final int PETS_PER_PAGE = 45;
    private static final int ADMIN_SETTINGS_PER_PAGE = 18;
    private static final String[] EDIT_FIELDS = {
        "level", "xp", "bond", "points", "speed", "health", "jump", "base-health", "base-speed"
    };
    private static final int[] EDIT_SLOTS = {10, 12, 14, 16, 19, 21, 23, 28, 30};
    private static final String[] ADMIN_SETTING_PATHS = {
        "enabled", "games.enabled", "riding.enabled", "riding.flight-enabled", "protection.invulnerable",
        "limits.max-pets", "growth.max-level", "growth.adult-level", "growth.xp-base", "growth.xp-factor",
        "growth.points-per-level", "growth.max-bond", "rewards.feed.xp", "rewards.feed.bond", "rewards.feed.cooldown-seconds",
        "feed.recipe-enabled",
        "rewards.play.xp", "rewards.play.bond", "rewards.play.cooldown-seconds", "rewards.travel.xp", "rewards.travel.distance",
        "rewards.travel.interval-seconds", "performance.autosave-seconds", "performance.follow-ticks", "follow.distance",
        "follow.teleport-distance", "upgrades.speed.max", "upgrades.health.max", "upgrades.jump.max", "upgrades.speed.per-level",
        "upgrades.health.per-level", "upgrades.jump.per-level", "riding.ground-speed", "riding.fly-speed", "riding.swim-speed",
        "riding.max-speed", "riding.flying-ceiling"
    };

    private final HappyPetsPlugin plugin;
    private final Map<UUID, InputRequest> inputs = new ConcurrentHashMap<>();
    private long promptGeneration;

    public PetMenus(HappyPetsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Opens one page from the owner's collection. */
    public void openList(Player player, int page) {
        if (!canUse(player)) return;
        List<PetData> owned = plugin.pets().owned(player.getUniqueId());
        int pages = pageCount(owned.size());
        int actualPage = Math.max(0, Math.min(page, pages - 1));
        Inventory inventory = inventory(new MenuHolder(MenuType.LIST, player.getUniqueId(), null, actualPage), 54,
            text("menu.list.title", "page", actualPage + 1, "pages", pages));
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE, " ");
        int start = actualPage * PETS_PER_PAGE;
        for (int index = start; index < Math.min(start + PETS_PER_PAGE, owned.size()); index++) {
            PetData pet = owned.get(index);
            int slot = index - start;
            inventory.setItem(slot, item(icon(pet.type().name()), text("menu.list.pet-name", "name", pet.name()),
                text("menu.list.pet-info", "species", species(pet), "level", Progression.level(pet, plugin.settings()),
                    "xp", pet.xp(), "bond", pet.bond()), "pet:" + pet.id()));
        }
        if (owned.isEmpty()) inventory.setItem(22, item(Material.BARRIER, text("menu.list.empty")));
        navigation(inventory, actualPage, pages, "list");
        inventory.setItem(49, item(Material.BARRIER, text("menu.list.close"), "", "close"));
        player.openInventory(inventory);
    }

    /** Opens the action panel for a pet owned by the player. */
    public void openPet(Player player, UUID petId) {
        PetData pet = petId == null ? null : plugin.pets().find(petId);
        if (pet == null) {
            plugin.messages().send(player, "error.not-found");
            return;
        }
        if (!canManagePet(player, pet)) {
            if (player.hasPermission(ADMIN_PERMISSION)) openAllPets(player, 0);
            else openList(player, 0);
            return;
        }
        Inventory inventory = inventory(new MenuHolder(MenuType.PET, player.getUniqueId(), petId, 0), 45,
            text("menu.pet.title", "name", pet.name()));
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE, " ");
        boolean owner = pet.owner().equals(player.getUniqueId());
        boolean gameplayAvailable = owner && canUseSilently(player);
        boolean active = plugin.pets().isActive(petId);
        if (gameplayAvailable) {
            inventory.setItem(10, action(active ? Material.ENDER_PEARL : icon(pet.type().name()),
                active ? "menu.pet.recall" : "menu.pet.summon", active ? "recall" : "summon"));
        } else if (active && player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(10, action(Material.ENDER_PEARL, "menu.editor.recall", "admin-recall"));
        }
        boolean canRide = gameplayAvailable && Progression.adult(pet, plugin.settings()) && plugin.settings().ridingEnabled()
            && (!plugin.settings().flyingTypes().contains(pet.type()) || plugin.settings().flightEnabled());
        if (canRide) {
            inventory.setItem(11, action(Material.SADDLE, "menu.pet.ride", "ride"));
        }
        inventory.setItem(12, action(pet.mode() == PetData.Mode.FOLLOW ? Material.LEAD : Material.IRON_BARS,
            pet.mode() == PetData.Mode.FOLLOW ? "menu.pet.stay" : "menu.pet.follow", "mode"));
        inventory.setItem(13, item(icon(pet.type().name()), text("menu.list.pet-name", "name", pet.name()),
            text("menu.pet.info", "species", species(pet), "level", Progression.level(pet, plugin.settings()),
                "xp", pet.xp(), "next", Progression.requiredForNextLevel(Progression.level(pet, plugin.settings()), plugin.settings()),
                "bond", pet.bond(), "points", pet.points(), "speed", pet.speed(), "health", pet.health(), "jump", pet.jump())));
        if (gameplayAvailable) {
            inventory.setItem(14, action(plugin.settings().feedMaterial(), "menu.pet.feed", "feed"));
            if (plugin.settings().gamesEnabled()) inventory.setItem(15, action(Material.NOTE_BLOCK, "menu.pet.play", "play"));
        }
        inventory.setItem(19, action(Material.PAPER, "menu.pet.rename", "rename"));
        if (gameplayAvailable && pet.points() > 0) {
            if (pet.speed() < plugin.settings().maxSpeedUpgrades()) inventory.setItem(21, action(Material.SUGAR, "menu.pet.upgrade-speed", "upgrade:speed"));
            if (pet.health() < plugin.settings().maxHealthUpgrades()) inventory.setItem(22, action(Material.GOLDEN_CARROT, "menu.pet.upgrade-health", "upgrade:health"));
            if (pet.jump() < plugin.settings().maxJumpUpgrades()) inventory.setItem(23, action(Material.SLIME_BALL, "menu.pet.upgrade-jump", "upgrade:jump"));
        }
        inventory.setItem(25, action(Material.TNT, "menu.pet.delete", "delete"));
        if (player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(16, action(Material.WRITABLE_BOOK, "menu.editor.open", "editor"));
        }
        inventory.setItem(40, item(Material.ARROW, text("menu.pet.back"), "", "back"));
        player.openInventory(inventory);
    }

    /** Opens one page of all stored pets. Requires only administrator permission. */
    public void openAllPets(Player player, int page) {
        if (!canAdmin(player)) return;
        List<PetData> pets = new ArrayList<>(plugin.pets().all());
        pets.sort(Comparator.comparing((PetData pet) -> pet.owner().toString())
            .thenComparing(PetData::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(pet -> pet.id().toString()));
        int pages = pageCount(pets.size());
        int actualPage = Math.max(0, Math.min(page, pages - 1));
        Inventory inventory = inventory(new MenuHolder(MenuType.ALL_PETS, player.getUniqueId(), null, actualPage), 54,
            text("menu.all-pets.title", "page", actualPage + 1, "pages", pages));
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE, " ");
        int start = actualPage * PETS_PER_PAGE;
        Map<UUID, String> ownerNames = new HashMap<>();
        for (int index = start; index < Math.min(start + PETS_PER_PAGE, pets.size()); index++) {
            PetData pet = pets.get(index);
            String ownerName = ownerNames.computeIfAbsent(pet.owner(), ignored -> plugin.pets().ownerDisplay(pet));
            inventory.setItem(index - start, item(icon(pet.type().name()), text("menu.list.pet-name", "name", pet.name()),
                text("menu.all-pets.pet-info", "owner", ownerName, "species", species(pet),
                    "level", Progression.level(pet, plugin.settings()), "bond", pet.bond(), "uuid", pet.id()),
                "pet:" + pet.id()));
        }
        if (pets.isEmpty()) inventory.setItem(22, item(Material.BARRIER, text("menu.all-pets.empty")));
        navigation(inventory, actualPage, pages, "all-pets");
        inventory.setItem(49, item(Material.ARROW, text("menu.all-pets.back"), "", "back"));
        player.openInventory(inventory);
    }

    /** Opens the direct editor for any pet when the viewer is an administrator. */
    public void openAdminPet(Player player, UUID petId) {
        if (!canAdmin(player)) return;
        PetData pet = petId == null ? null : plugin.pets().find(petId);
        if (pet == null || !canManagePet(player, pet)) {
            plugin.messages().send(player, "error.not-found");
            openAllPets(player, 0);
            return;
        }
        Inventory inventory = inventory(new MenuHolder(MenuType.ADMIN_PET, player.getUniqueId(), petId, 0), 54,
            text("menu.editor.title", "name", pet.name()));
        fill(inventory, Material.BLUE_STAINED_GLASS_PANE, " ");
        inventory.setItem(4, item(Material.BOOK, text("menu.editor.pet-info", "owner", plugin.pets().ownerDisplay(pet),
            "uuid", pet.id()), text("menu.editor.help")));
        for (int index = 0; index < EDIT_FIELDS.length; index++) {
            String field = EDIT_FIELDS[index];
            String value = plugin.pets().adminValue(pet, field);
            String fieldName = text("menu.editor.field." + field);
            String range = plugin.pets().adminLimit(field);
            int slot = EDIT_SLOTS[index];
            inventory.setItem(slot, item(editorIcon(field), text("menu.editor.field-value", "field", fieldName, "value", value),
                text(field.startsWith("base-") ? "menu.editor.float-field-help" : "menu.editor.field-help", "range", range), "field:" + field));
            inventory.setItem(slot + 1, item(Material.NAME_TAG, text("menu.editor.set-value", "field", fieldName),
                text("menu.editor.set-value-help"), "input:" + field));
        }
        if (plugin.pets().isActive(petId)) inventory.setItem(46, action(Material.ENDER_PEARL, "menu.editor.recall", "admin-recall"));
        inventory.setItem(47, action(pet.mode() == PetData.Mode.FOLLOW ? Material.LEAD : Material.IRON_BARS,
            pet.mode() == PetData.Mode.FOLLOW ? "menu.pet.stay" : "menu.pet.follow", "mode"));
        inventory.setItem(48, action(Material.PAPER, "menu.pet.rename", "rename"));
        inventory.setItem(50, action(Material.TNT, "menu.pet.delete", "delete"));
        inventory.setItem(45, item(Material.ARROW, text("menu.editor.back"), "", "back"));
        player.openInventory(inventory);
    }

    /** Opens a paginated administrator settings menu. */
    public void openAdmin(Player player, int page) {
        if (!canAdmin(player)) return;
        int pages = Math.max(1, (ADMIN_SETTING_PATHS.length + ADMIN_SETTINGS_PER_PAGE - 1) / ADMIN_SETTINGS_PER_PAGE);
        int actualPage = Math.max(0, Math.min(page, pages - 1));
        Inventory inventory = inventory(new MenuHolder(MenuType.ADMIN, player.getUniqueId(), null, actualPage), 54,
            text("menu.admin.title", "page", actualPage + 1, "pages", pages));
        fill(inventory, Material.BLUE_STAINED_GLASS_PANE, " ");
        int start = actualPage * ADMIN_SETTINGS_PER_PAGE;
        for (int index = start; index < Math.min(start + ADMIN_SETTINGS_PER_PAGE, ADMIN_SETTING_PATHS.length); index++) {
            String path = ADMIN_SETTING_PATHS[index];
            int slot = 10 + ((index - start) / 7) * 9 + ((index - start) % 7);
            Object current = plugin.getConfig().get(path);
            Material material = current instanceof Boolean enabled ? (enabled ? Material.LIME_DYE : Material.GRAY_DYE) : settingIcon(path);
            inventory.setItem(slot, item(material, settingName(path), text("menu.admin.setting-info", "value", displayValue(current)), "setting:" + path));
        }
        inventory.setItem(47, action(Material.HOPPER, "menu.admin.give-food", "give-food"));
        inventory.setItem(48, action(Material.CLOCK, "menu.admin.reload", "reload"));
        inventory.setItem(50, action(Material.WRITABLE_BOOK, "menu.admin.open-pets", "all-pets"));
        navigation(inventory, actualPage, pages, "admin");
        inventory.setItem(49, item(Material.ARROW, text("menu.admin.back"), "", "back"));
        player.openInventory(inventory);
    }

    /** Prompts an owner to rename their pet in chat. */
    public void requestRename(Player player, UUID petId) {
        PetData pet = petId == null ? null : plugin.pets().find(petId);
        if (pet == null || !canManagePet(player, pet)) return;
        beginInput(player, new InputRequest(InputKind.RENAME, petId, null, UUID.randomUUID(), promptGeneration, null),
            "rename.prompt", "name", pet.name());
    }

    private void requestAdminValue(Player player, UUID petId, String field) {
        PetData pet = petId == null ? null : plugin.pets().find(petId);
        if (!canAdmin(player) || pet == null || !plugin.pets().canManage(player, pet)
                || !List.of(EDIT_FIELDS).contains(field)) return;
        beginInput(player, new InputRequest(InputKind.ADMIN_VALUE, petId, field, UUID.randomUUID(), promptGeneration, null),
            "menu.editor.edit-prompt", "field", text("menu.editor.field." + field),
            "value", plugin.pets().adminValue(pet, field), "range", plugin.pets().adminLimit(field));
    }

    private void beginInput(Player player, InputRequest template, String promptKey, Object... placeholders) {
        cancelInput(player.getUniqueId());
        player.closeInventory();
        plugin.messages().send(player, promptKey, placeholders);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            InputRequest request = inputs.get(player.getUniqueId());
            if (request != null && request.samePrompt(template) && inputs.remove(player.getUniqueId(), request)
                    && player.isOnline()) {
                plugin.messages().send(player, request.kind() == InputKind.RENAME ? "rename.timeout" : "menu.editor.edit-timeout");
            }
        }, 20L * 60L);
        inputs.put(player.getUniqueId(), template.withTimeout(timeout));
    }

    /** Cancels pending chat prompts and closes all HappyPets menus. */
    public void closeAll() {
        promptGeneration++;
        for (UUID playerId : new ArrayList<>(inputs.keySet())) cancelInput(playerId);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (holder.playerId() != null && !holder.playerId().equals(player.getUniqueId())) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(plugin.menuActionKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (action == null) return;
        switch (holder.type()) {
            case LIST -> handleList(player, holder, action);
            case PET -> handlePet(player, holder, action);
            case DELETE -> handleDelete(player, holder, action);
            case ADMIN -> handleAdmin(player, holder, action, event.getClick());
            case ALL_PETS -> handleAllPets(player, holder, action);
            case ADMIN_PET -> handleAdminPet(player, holder, action, event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRenameChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        InputRequest request = inputs.remove(playerId);
        if (request == null) return;
        String raw = event.getMessage();
        if (raw.strip().equalsIgnoreCase("cancel")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                request.timeout().cancel();
                plugin.messages().send(player, request.kind() == InputKind.RENAME ? "rename.cancelled" : "menu.editor.edit-cancelled");
            });
            return;
        }
        if (raw.startsWith("/")) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                request.timeout().cancel();
                plugin.messages().send(player, request.kind() == InputKind.RENAME ? "rename.cancelled" : "menu.editor.edit-cancelled");
            });
            return;
        }
        event.setCancelled(true);
        String value = raw.strip();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            request.timeout().cancel();
            finishInput(player, request, value);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { cancelInput(event.getPlayer().getUniqueId()); }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof MenuHolder holder && holder.type() == MenuType.DELETE) {
            // Confirmation is represented only by the open menu and is discarded on close.
        }
    }

    private void handleList(Player player, MenuHolder holder, String action) {
        if (!canUse(player)) return;
        if (action.startsWith("page:")) {
            int page = parseInteger(action.substring(5));
            if (page >= 0) deferMenuOpen(player, holder, () -> openList(player, page));
            return;
        }
        if (action.startsWith("pet:")) {
            UUID petId = parseUuid(action.substring(4));
            if (petId != null) deferMenuOpen(player, holder, () -> openPet(player, petId));
            return;
        }
        if (action.equals("close")) player.closeInventory();
    }

    private void handleAllPets(Player player, MenuHolder holder, String action) {
        if (!canAdmin(player)) { player.closeInventory(); return; }
        if (action.startsWith("page:")) {
            int page = parseInteger(action.substring(5));
            if (page >= 0) deferMenuOpen(player, holder, () -> openAllPets(player, page));
            return;
        }
        if (action.equals("back")) { deferMenuOpen(player, holder, () -> openAdmin(player, 0)); return; }
        if (!action.startsWith("pet:")) return;
        UUID petId = parseUuid(action.substring(4));
        if (petId != null) deferMenuOpen(player, holder, () -> openAdminPet(player, petId));
    }

    private void handlePet(Player player, MenuHolder holder, String action) {
        PetData pet = holder.petId() == null ? null : plugin.pets().find(holder.petId());
        if (pet == null) { plugin.messages().send(player, "error.not-found"); player.closeInventory(); return; }
        if (!canManagePet(player, pet)) { player.closeInventory(); return; }
        boolean owner = pet.owner().equals(player.getUniqueId());
        switch (action) {
            case "back" -> { deferMenuOpen(player, holder, () -> openReturnMenu(player, pet)); return; }
            case "close" -> player.closeInventory();
            case "editor" -> { if (canAdmin(player)) deferMenuOpen(player, holder, () -> openAdminPet(player, pet.id())); return; }
            case "admin-recall" -> { if (canAdmin(player)) runPetAction(() -> plugin.pets().adminRecall(player, pet.id())); }
            case "summon" -> { if (!owner || !canUse(player)) return; runPetAction(() -> plugin.pets().summon(player, pet.id())); }
            case "recall" -> { if (!owner || !canUse(player)) return; runPetAction(() -> plugin.pets().recall(player)); }
            case "ride" -> {
                if (!owner || !canUse(player)) return;
                if (plugin.pets().ride(player, pet.id())) player.closeInventory();
                return;
            }
            case "mode" -> {
                PetData.Mode mode = pet.mode() == PetData.Mode.FOLLOW ? PetData.Mode.STAY : PetData.Mode.FOLLOW;
                runPetAction(() -> plugin.pets().setMode(player, pet.id(), mode));
            }
            case "feed" -> { if (!owner || !canUse(player)) return; runPetAction(() -> plugin.pets().feed(player, pet.id())); }
            case "play" -> { if (!owner || !canUse(player)) return; deferMenuOpen(player, holder, () -> plugin.games().open(player, pet.id())); return; }
            case "rename" -> { requestRename(player, pet.id()); return; }
            case "delete" -> { deferMenuOpen(player, holder, () -> openDelete(player, pet)); return; }
            case "upgrade:speed", "upgrade:health", "upgrade:jump" -> {
                if (!owner || !canUse(player)) return;
                String stat = action.substring("upgrade:".length());
                runPetAction(() -> plugin.pets().upgrade(player, pet.id(), stat));
            }
            default -> { return; }
        }
        if (plugin.pets().find(pet.id()) != null) deferMenuOpen(player, holder, () -> openPet(player, pet.id()));
        else deferMenuOpen(player, holder, () -> openReturnMenu(player, pet));
    }

    private void openDelete(Player player, PetData pet) {
        Inventory inventory = inventory(new MenuHolder(MenuType.DELETE, player.getUniqueId(), pet.id(), 0), 27, text("menu.pet.delete-confirm-title"));
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE, " ");
        inventory.setItem(11, item(Material.RED_CONCRETE, text("menu.pet.delete-confirm", "name", pet.name()),
            text("menu.pet.delete-warning"), "delete-confirm"));
        inventory.setItem(15, item(Material.LIME_CONCRETE, text("menu.pet.delete-cancel"), "", "delete-cancel"));
        player.openInventory(inventory);
    }

    private void handleDelete(Player player, MenuHolder holder, String action) {
        PetData pet = holder.petId() == null ? null : plugin.pets().find(holder.petId());
        if (pet == null) { plugin.messages().send(player, "error.not-found"); player.closeInventory(); return; }
        if (!canManagePet(player, pet)) { player.closeInventory(); return; }
        if (action.equals("delete-cancel")) { deferMenuOpen(player, holder, () -> openReturnMenu(player, pet)); return; }
        if (action.equals("delete-confirm")) {
            if (plugin.pets().delete(player, pet.id())) {
                deferMenuOpen(player, holder, () -> {
                    if (player.hasPermission(ADMIN_PERMISSION)) openAllPets(player, 0);
                    else openList(player, 0);
                });
            } else {
                deferMenuOpen(player, holder, () -> openReturnMenu(player, pet));
            }
        }
    }

    private void handleAdmin(Player player, MenuHolder holder, String action, ClickType click) {
        if (!canAdmin(player)) { player.closeInventory(); return; }
        if (action.startsWith("page:")) {
            int page = parseInteger(action.substring(5));
            if (page >= 0) deferMenuOpen(player, holder, () -> openAdmin(player, page));
            return;
        }
        if (action.equals("back")) { player.closeInventory(); return; }
        if (action.equals("all-pets")) {
            deferMenuOpen(player, holder, () -> openAllPets(player, 0));
            return;
        }
        if (action.equals("give-food")) {
            plugin.pets().giveFood(player, 16);
            deferOpen(player, () -> openAdmin(player, holder.page()));
            return;
        }
        if (action.equals("reload")) {
            boolean reloaded = plugin.reloadPlugin();
            plugin.messages().send(player, reloaded ? "menu.admin.reload-success" : "menu.admin.reload-failed");
            deferOpen(player, () -> openAdmin(player, holder.page()));
            return;
        }
        if (!action.startsWith("setting:")) return;
        String path = action.substring("setting:".length());
        if (!List.of(ADMIN_SETTING_PATHS).contains(path)) return;
        Object current = plugin.getConfig().get(path);
        Object next = AdminSettingStepper.next(path, current, click, plugin.settings());
        boolean success = true;
        if (!sameValue(current, next)) {
            if (path.equals("growth.max-level") && next instanceof Number max && max.intValue() < plugin.settings().adultLevel()) {
                success = plugin.updateSetting("growth.adult-level", max.intValue()) && plugin.updateSetting(path, next);
            } else success = plugin.updateSetting(path, next);
        }
        if (!success) plugin.messages().send(player, "menu.admin.setting-failed");
        deferOpen(player, () -> openAdmin(player, holder.page()));
    }

    private void handleAdminPet(Player player, MenuHolder holder, String action, ClickType click) {
        if (!canAdmin(player)) { player.closeInventory(); return; }
        PetData pet = holder.petId() == null ? null : plugin.pets().find(holder.petId());
        if (pet == null || !plugin.pets().canManage(player, pet)) {
            plugin.messages().send(player, "error.not-found");
            deferMenuOpen(player, holder, () -> openAllPets(player, 0));
            return;
        }
        if (action.equals("back")) {
            deferMenuOpen(player, holder, () -> openReturnMenu(player, pet));
            return;
        }
        if (action.equals("admin-recall")) {
            plugin.pets().adminRecall(player, pet.id());
            deferMenuOpen(player, holder, () -> openAdminPet(player, pet.id()));
            return;
        }
        if (action.equals("mode")) {
            PetData.Mode mode = pet.mode() == PetData.Mode.FOLLOW ? PetData.Mode.STAY : PetData.Mode.FOLLOW;
            plugin.pets().setMode(player, pet.id(), mode);
            deferMenuOpen(player, holder, () -> openAdminPet(player, pet.id()));
            return;
        }
        if (action.equals("rename")) { requestRename(player, pet.id()); return; }
        if (action.equals("delete")) { deferMenuOpen(player, holder, () -> openDelete(player, pet)); return; }
        if (action.startsWith("input:")) {
            requestAdminValue(player, pet.id(), action.substring("input:".length()));
            return;
        }
        if (!action.startsWith("field:")) return;
        String field = action.substring("field:".length());
        if (!List.of(EDIT_FIELDS).contains(field)) return;
        if (click == ClickType.MIDDLE) { requestAdminValue(player, pet.id(), field); return; }
        long direction = switch (click) {
            case LEFT, SHIFT_LEFT -> 1L;
            case RIGHT, SHIFT_RIGHT -> -1L;
            default -> 0L;
        };
        if (direction == 0) return;
        long multiplier = click.isShiftClick() ? (field.equals("xp") ? 1000L : 10L) : 1L;
        long delta = direction * multiplier;
        if (field.equals("base-health") || field.equals("base-speed")) delta *= 10;
        plugin.pets().adminAdjust(player, pet.id(), field, delta);
        deferMenuOpen(player, holder, () -> openAdminPet(player, pet.id()));
    }

    private void deferMenuOpen(Player player, MenuHolder current, Runnable open) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory().getHolder() != current) return;
            open.run();
        });
    }

    private void deferOpen(Player player, Runnable open) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) open.run();
        });
    }

    private boolean sameValue(Object first, Object second) {
        return first instanceof Number left && second instanceof Number right
            ? Double.compare(left.doubleValue(), right.doubleValue()) == 0 : java.util.Objects.equals(first, second);
    }

    private int parseInteger(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }

    static int pageCount(int total) { return (int) Math.max(1L, ((long) Math.max(0, total) + PETS_PER_PAGE - 1) / PETS_PER_PAGE); }

    private UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private void finishInput(Player player, InputRequest request, String value) {
        if (request.generation() != promptGeneration || !player.isOnline()) return;
        PetData pet = plugin.pets().find(request.petId());
        if (pet == null) {
            plugin.messages().send(player, "error.not-found");
            return;
        }
        if (request.kind() == InputKind.RENAME) {
            if (!canManagePet(player, pet)) return;
            if (value.isBlank() || value.codePointCount(0, value.length()) > 32 || value.chars().anyMatch(Character::isISOControl)) {
                plugin.messages().send(player, "rename.invalid");
                reopenAfterInput(player, pet);
                return;
            }
            if (plugin.pets().rename(player, pet.id(), value)) reopenAfterInput(player, pet);
            return;
        }
        if (!canAdmin(player)) return;
        // adminSet rechecks permission and validates the persisted field on the server thread.
        plugin.pets().adminSet(player, pet.id(), request.field(), value);
        if (player.hasPermission(ADMIN_PERMISSION) && plugin.pets().find(pet.id()) != null) {
            deferOpen(player, () -> openAdminPet(player, pet.id()));
        }
    }

    private void reopenAfterInput(Player player, PetData pet) {
        deferOpen(player, () -> {
            if (player.hasPermission(ADMIN_PERMISSION) && !pet.owner().equals(player.getUniqueId())) openAdminPet(player, pet.id());
            else openPet(player, pet.id());
        });
    }

    private void runPetAction(BooleanAction action) {
        action.run();
    }

    private void cancelInput(UUID playerId) {
        InputRequest request = inputs.remove(playerId);
        if (request != null) request.timeout().cancel();
    }

    private void openReturnMenu(Player player, PetData pet) {
        if (player.hasPermission(ADMIN_PERMISSION)
                && (!pet.owner().equals(player.getUniqueId()) || !canUseSilently(player))) openAllPets(player, 0);
        else openList(player, 0);
    }

    private boolean canManagePet(Player player, PetData pet) {
        if (pet == null || !plugin.pets().canManage(player, pet)) {
            plugin.messages().send(player, "pet.not-owner");
            return false;
        }
        if (player.hasPermission(ADMIN_PERMISSION)) return canAdmin(player);
        return canUse(player);
    }

    private boolean canUseSilently(Player player) {
        return player.hasPermission("happypets.use") && plugin.settings().enabled()
            && !plugin.settings().disabledWorlds().contains(player.getWorld().getName());
    }

    private Material editorIcon(String field) {
        return switch (field) {
            case "level" -> Material.EXPERIENCE_BOTTLE;
            case "xp" -> Material.PAPER;
            case "bond" -> Material.HEART_OF_THE_SEA;
            case "points" -> Material.AMETHYST_SHARD;
            case "speed", "base-speed" -> Material.SUGAR;
            case "health", "base-health" -> Material.GOLDEN_APPLE;
            case "jump" -> Material.SLIME_BALL;
            default -> Material.BOOK;
        };
    }

    private boolean canUse(Player player) {
        return plugin.pets().usable(player);
    }

    private boolean canAdmin(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            plugin.messages().send(player, "menu.admin.unavailable");
            return false;
        }
        return true;
    }

    private void navigation(Inventory inventory, int page, int pages, String kind) {
        if (page > 0) inventory.setItem(45, item(Material.ARROW, text("menu." + kind + ".previous"), "", "page:" + (page - 1)));
        if (page + 1 < pages) inventory.setItem(53, item(Material.ARROW, text("menu." + kind + ".next"), "", "page:" + (page + 1)));
    }

    private ItemStack action(Material material, String key, String action) {
        return item(material, text(key), "", action);
    }

    private Inventory inventory(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, Messages.color(title));
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack item(Material material, String name, String... lore) {
        return item(material, name, String.join("\n", lore), null);
    }

    private ItemStack item(Material material, String name, String lore, String action) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Messages.color(name));
            if (lore != null && !lore.isBlank()) meta.setLore(List.of(Messages.color(lore).split("\\n", -1)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (action != null) meta.getPersistentDataContainer().set(plugin.menuActionKey(), org.bukkit.persistence.PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fill(Inventory inventory, Material material, String name) {
        ItemStack glass = item(material, name);
        for (int slot = 0; slot < inventory.getSize(); slot++) if (inventory.getItem(slot) == null) inventory.setItem(slot, glass.clone());
    }

    private String text(String key, Object... placeholders) { return plugin.messages().text(key, placeholders); }

    private String species(PetData pet) { return text("entity." + pet.type().name().toLowerCase(java.util.Locale.ROOT)); }

    private Material icon(String entityName) {
        Material material = Material.matchMaterial(entityName);
        if (material != null && material.isItem()) return material;
        Material spawnEgg = Material.matchMaterial(entityName + "_SPAWN_EGG");
        if (spawnEgg != null && spawnEgg.isItem()) return spawnEgg;
        if (entityName.endsWith("_FLYING") || entityName.equals("ALLAY")) return Material.FEATHER;
        return Material.BONE;
    }

    private String settingName(String path) {
        String key = "menu.admin.setting." + path.replace('.', '-');
        return text(key);
    }

    private Material settingIcon(String path) {
        if (path.contains("xp")) return Material.EXPERIENCE_BOTTLE;
        if (path.contains("bond")) return Material.HEART_OF_THE_SEA;
        if (path.contains("ride") || path.contains("speed")) return Material.SADDLE;
        if (path.contains("cooldown") || path.contains("interval") || path.contains("ticks")) return Material.CLOCK;
        if (path.contains("pet")) return Material.LEAD;
        return Material.BOOK;
    }

    private String displayValue(Object value) { return value instanceof Boolean enabled ? text(enabled ? "common.enabled" : "common.disabled") : String.valueOf(value); }

    enum InputKind { RENAME, ADMIN_VALUE }

    record InputRequest(InputKind kind, UUID petId, String field, UUID token, long generation, BukkitTask timeout) {
        private InputRequest withTimeout(BukkitTask value) { return new InputRequest(kind, petId, field, token, generation, value); }
        private boolean samePrompt(InputRequest other) {
            return token.equals(other.token);
        }
    }

    @FunctionalInterface private interface BooleanAction { boolean run(); }

    enum MenuType { LIST, PET, DELETE, ADMIN, ALL_PETS, ADMIN_PET }

    static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final UUID playerId;
        private final UUID petId;
        private final int page;
        private Inventory inventory;

        MenuHolder(MenuType type, UUID playerId, UUID petId, int page) {
            this.type = type;
            this.playerId = playerId;
            this.petId = petId;
            this.page = page;
        }

        private MenuType type() { return type; }
        private UUID playerId() { return playerId; }
        private UUID petId() { return petId; }
        private int page() { return page; }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
