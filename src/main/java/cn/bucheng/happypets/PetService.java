package cn.bucheng.happypets;

import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Main-thread pet lifecycle and a single bounded scheduler for active companions. */
public final class PetService {
    public static final List<String> ADMIN_FIELDS = List.of("level", "xp", "bond", "points", "speed", "health", "jump", "base-health", "base-speed");
    private static final Set<EntityType> BOSSES = Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER,
            EntityType.WARDEN, EntityType.ELDER_GUARDIAN);
    private final HappyPetsPlugin plugin;
    private final PetStore store;
    private final NamespacedKey petKey;
    private final NamespacedKey foodKey;
    private final NamespacedKey recipeKey;
    private final Map<UUID, PetData> records = new LinkedHashMap<>();
    private final Map<UUID, List<PetData>> owners = new HashMap<>();
    private final Map<UUID, UUID> activeByOwner = new HashMap<>();
    private final Map<UUID, Mob> entities = new HashMap<>();
    private final Map<UUID, UUID> entityIds = new HashMap<>();
    private final Map<UUID, UUID> riders = new HashMap<>();
    private final Set<UUID> mounting = new HashSet<>();
    private final Map<UUID, Location> travelLocations = new HashMap<>();
    private final Map<UUID, Double> travelDistance = new HashMap<>();
    private final Map<UUID, Integer> playTickets = new HashMap<>();
    private BukkitTask task;
    private BukkitTask adminSaveTask;
    private long tick;
    private boolean dirty;
    private boolean stopping;

    public PetService(HappyPetsPlugin plugin, PetStore store, Collection<PetData> loaded) {
        this.plugin = plugin;
        this.store = store;
        petKey = new NamespacedKey(plugin, "pet_id");
        foodKey = new NamespacedKey(plugin, "pet_food");
        recipeKey = new NamespacedKey(plugin, "pet_food_recipe");
        for (PetData data : loaded) add(data);
    }

    public void start() {
        // A one-time loaded-entity scan removes pets left by a previous plugin reload.
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) removeStale(entity);
        registerRecipe();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public List<PetData> owned(UUID owner) { return List.copyOf(owners.getOrDefault(owner, List.of())); }
    public Collection<PetData> all() { return List.copyOf(records.values()); }
    public PetData find(UUID id) { return records.get(id); }
    public PetData active(UUID owner) { return records.get(activeByOwner.get(owner)); }
    public boolean isActive(UUID id) { return entities.containsKey(id); }
    public Mob entity(UUID id) { return entities.get(id); }
    public boolean isPet(Entity entity) { return entity != null && entity.getPersistentDataContainer().has(petKey, PersistentDataType.STRING); }
    public PetData petFor(Entity entity) { return entity == null ? null : records.get(entityIds.get(entity.getUniqueId())); }
    public void touch() { dirty = true; }
    public String status() { return records.size() + " / " + entities.size() + " / " + riders.size(); }

    public boolean canAdmin(Player player) { return player.hasPermission("happypets.admin"); }
    public boolean canManage(Player player, PetData data) {
        return data != null && (data.owner().equals(player.getUniqueId()) || canAdmin(player));
    }

    /** UUID lookup uses cached profiles and never requests a name from the network. */
    public String ownerDisplay(PetData data) {
        String name = Bukkit.getOfflinePlayer(data.owner()).getName();
        return name == null ? data.owner().toString() : name;
    }

    private void add(PetData data) {
        if (records.putIfAbsent(data.id(), data) != null) throw new IllegalArgumentException("Duplicate pet id");
        owners.computeIfAbsent(data.owner(), ignored -> new ArrayList<>()).add(data);
    }

    boolean usable(Player player) {
        if (!player.hasPermission("happypets.use")) return fail(player, "error.no-permission");
        if (!plugin.settings().enabled()) return fail(player, "error.disabled");
        if (plugin.settings().disabledWorlds().contains(player.getWorld().getName())) return fail(player, "error.world-disabled");
        return true;
    }

    private PetData ownedBy(Player player, UUID id) {
        PetData data = find(id);
        if (data == null) { fail(player, "error.not-found"); return null; }
        if (!data.owner().equals(player.getUniqueId())) { fail(player, "error.not-owner"); return null; }
        return data;
    }

    private PetData manageableBy(Player player, UUID id) {
        PetData data = find(id);
        if (data == null) { fail(player, "error.not-found"); return null; }
        if (!canManage(player, data)) { fail(player, "error.not-owner"); return null; }
        return data;
    }

    private boolean managementAllowed(Player player) { return canAdmin(player) || usable(player); }

    /** Direct administration changes values without charging or granting upgrade points. */
    public boolean adminSet(Player player, UUID id, String field, String value) {
        if (!canAdmin(player)) return fail(player, "error.no-permission");
        PetData data = find(id);
        if (data == null) return fail(player, "error.not-found");
        if (field == null || !ADMIN_FIELDS.contains(field)) return fail(player, "admin.edit-unknown-field");
        try {
            if (field.startsWith("base-")) {
                double number = Double.parseDouble(value.strip());
                if (!Double.isFinite(number) || number > 1024 || number < 0 || (field.equals("base-health") && number == 0))
                    return fail(player, "admin.edit-invalid", "range", adminLimit(field));
                if (field.equals("base-health")) data.setBaseHealth(number); else data.setBaseSpeed(number);
            } else {
                long number = Long.parseLong(value.strip());
                if (number < adminMinimum(field) || number > adminMaximum(field)) return fail(player, "admin.edit-invalid", "range", adminLimit(field));
                switch (field) {
                    case "level" -> {
                        long xp = Progression.xpAtLevel((int) number, plugin.settings());
                        PetData probe = new PetData(data.id(), data.owner(), data.type(), data.name());
                        probe.setXp(xp);
                        if (Progression.level(probe, plugin.settings()) != number) return fail(player, "admin.edit-unreachable-level");
                        data.setXp(xp);
                    }
                    case "xp" -> data.setXp(number);
                    case "bond" -> data.setBond((int) number);
                    case "points" -> data.setPoints((int) number);
                    case "speed" -> data.setSpeed((int) number);
                    case "health" -> data.setHealth((int) number);
                    case "jump" -> data.setJump((int) number);
                    default -> throw new IllegalStateException("Unexpected admin field");
                }
            }
        } catch (IllegalArgumentException | NullPointerException invalidValue) {
            return fail(player, "admin.edit-invalid", "range", adminLimit(field));
        }
        applyAdminChange(player, data);
        plugin.messages().send(player, "admin.edit-success", "name", data.name(), "field",
                plugin.messages().text("menu.editor.field." + field), "value", adminValue(data, field));
        plugin.getLogger().info("Admin " + player.getName() + " edited pet " + id + ": " + field + "=" + adminValue(data, field));
        return true;
    }

    /** Click adjustment saturates at storage limits, including at Long.MAX_VALUE. */
    public boolean adminAdjust(Player player, UUID id, String field, long delta) {
        if (!canAdmin(player)) return fail(player, "error.no-permission");
        PetData data = find(id);
        if (data == null) return fail(player, "error.not-found");
        if (field == null || !ADMIN_FIELDS.contains(field)) return fail(player, "admin.edit-unknown-field");
        if (field.startsWith("base-")) {
            double current = Double.parseDouble(adminValue(data, field));
            double next = Math.max(field.equals("base-health") ? .01 : 0, Math.min(1024, current + delta * .01));
            return adminSet(player, id, field, Double.toString(Math.round(next * 100.0) / 100.0));
        }
        long current = Long.parseLong(adminValue(data, field));
        long next;
        try { next = Math.addExact(current, delta); }
        catch (ArithmeticException overflow) { next = delta > 0 ? Long.MAX_VALUE : Long.MIN_VALUE; }
        next = Math.max(adminMinimum(field), Math.min(adminMaximum(field), next));
        return adminSet(player, id, field, Long.toString(next));
    }

    public String adminValue(PetData data, String field) {
        return switch (field) {
            case "level" -> Integer.toString(Progression.level(data, plugin.settings()));
            case "xp" -> Long.toString(data.xp());
            case "bond" -> Integer.toString(data.bond());
            case "points" -> Integer.toString(data.points());
            case "speed" -> Integer.toString(data.speed());
            case "health" -> Integer.toString(data.health());
            case "jump" -> Integer.toString(data.jump());
            case "base-health" -> Double.toString(data.baseHealth());
            case "base-speed" -> Double.toString(data.baseSpeed());
            default -> throw new IllegalArgumentException("Unknown pet field");
        };
    }

    public String adminLimit(String field) {
        if (field.equals("base-health")) return "0 < x ≤ 1024";
        if (field.equals("base-speed")) return "0–1024";
        return adminMinimum(field) + "–" + adminMaximum(field);
    }

    private long adminMinimum(String field) { return field.equals("level") ? 1 : 0; }
    private long adminMaximum(String field) {
        return switch (field) {
            case "level" -> plugin.settings().maxLevel();
            case "xp" -> Long.MAX_VALUE;
            case "bond", "points" -> Integer.MAX_VALUE;
            case "speed", "health", "jump" -> 1000;
            default -> throw new IllegalArgumentException("Unknown pet field");
        };
    }

    private void applyAdminChange(Player actor, PetData data) {
        Mob entity = entities.get(data.id());
        if (entity != null) {
            try {
                if (!Progression.adult(data, plugin.settings())) { entity.eject(); riders.remove(data.owner()); }
                PetEntityState.configure(entity, data, plugin.settings());
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Pet data changed but its entity could not update: " + data.id(), error);
                retract(data.owner());
                plugin.messages().send(actor, "admin.saved-entity-retracted", "name", data.name());
            }
        }
        queueAdminSave();
    }

    private void queueAdminSave() {
        touch();
        if (adminSaveTask != null || stopping) return;
        adminSaveTask = Bukkit.getScheduler().runTask(plugin, () -> {
            adminSaveTask = null;
            if (!stopping) { store.saveAsync(all()); dirty = false; }
        });
    }

    public boolean adminRecall(Player player, UUID id) {
        if (!canAdmin(player)) return fail(player, "error.no-permission");
        PetData data = find(id);
        if (data == null || !isActive(id)) return fail(player, "error.not-found");
        retract(data.owner());
        queueAdminSave();
        return success(player, "pet.recalled");
    }

    static boolean capturableType(EntityType type) {
        return type.isAlive() && type.isSpawnable() && type != EntityType.PLAYER
                && type != EntityType.ARMOR_STAND && !BOSSES.contains(type);
    }

    public boolean capture(Player player, Mob mob) {
        if (!usable(player)) return false;
        if (BOSSES.contains(mob.getType())) return fail(player, "error.boss");
        if (!capturableType(mob.getType()) || plugin.settings().excludedTypes().contains(mob.getType())
                || mob.hasMetadata("NPC") || isPet(mob) || !mob.isValid()) return fail(player, "error.not-capturable");
        if (!mob.getPassengers().isEmpty() || mob.isInsideVehicle() || mob.isLeashed()) return fail(player, "error.busy");
        if (mob instanceof Tameable tame && tame.getOwner() != null && !tame.getOwner().getUniqueId().equals(player.getUniqueId())) return fail(player, "error.not-owner");
        if (owned(player.getUniqueId()).size() >= plugin.settings().maxPets()) return fail(player, "error.limit", "max", plugin.settings().maxPets());
        if (!isFood(player.getInventory().getItemInMainHand())) return fail(player, "error.no-food");
        PetCaptureEvent event = new PetCaptureEvent(player, mob);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return fail(player, "error.capture-denied");
        if (mob instanceof org.bukkit.inventory.InventoryHolder holder) {
            for (HumanEntity viewer : new ArrayList<>(holder.getInventory().getViewers())) viewer.closeInventory();
        }
        String originalName = ChatColor.stripColor(mob.getCustomName());
        String name = originalName == null || originalName.isBlank() || originalName.codePointCount(0, originalName.length()) > 32
                ? plugin.messages().text("pet.default-name") : originalName;
        PetData data = new PetData(UUID.randomUUID(), player.getUniqueId(), mob.getType(), name);
        PetEntityState.capture(mob, data);
        // Configure before consuming an item or altering the owner's current companion.
        PetEntityState.configure(mob, data, plugin.settings());
        retract(player.getUniqueId());
        add(data);
        bind(data, mob);
        consumeMainHand(player);
        touch();
        saveNow();
        plugin.messages().send(player, "pet.captured", "name", data.name());
        return true;
    }

    private void bind(PetData data, Mob mob) {
        mob.getPersistentDataContainer().set(petKey, PersistentDataType.STRING, data.id().toString());
        entities.put(data.id(), mob);
        entityIds.put(mob.getUniqueId(), data.id());
        activeByOwner.put(data.owner(), data.id());
        Player owner = Bukkit.getPlayer(data.owner());
        if (owner != null) travelLocations.put(data.id(), owner.getLocation());
    }

    public boolean summon(Player player, UUID id) {
        if (!usable(player)) return false;
        PetData data = ownedBy(player, id);
        if (data == null) return false;
        if (!capturableType(data.type())) return fail(player, "error.boss");
        if (player.isInsideVehicle()) return fail(player, "error.mounted");
        if (isActive(id)) return fail(player, "error.already-active");
        Location location = safeNear(player, data.type(), 1, 2);
        if (location == null) return fail(player, "error.no-space");
        Mob spawned = null;
        try {
            Entity raw = location.getWorld().spawnEntity(location, data.type());
            if (!(raw instanceof Mob mob)) { raw.remove(); return fail(player, "error.spawn-failed"); }
            spawned = mob;
            if (!mob.isValid()) throw new IllegalStateException("Entity spawn was cancelled");
            PetEntityState.restore(mob, data);
            PetEntityState.configure(mob, data, plugin.settings());
            Location fitted = safeNear(player, data.type(), mob.getWidth(), mob.getHeight());
            if (fitted == null || !mob.teleport(fitted)) throw new IllegalStateException("No room for the pet's actual dimensions");
            retract(player.getUniqueId());
            bind(data, mob);
            return success(player, "pet.summoned", "name", data.name());
        } catch (Exception error) {
            if (spawned != null) spawned.remove();
            plugin.getLogger().log(Level.WARNING, "Could not summon pet " + id + "; record was preserved", error);
            return fail(player, "error.spawn-failed");
        }
    }

    public boolean recall(Player player) {
        if (!player.hasPermission("happypets.use")) return fail(player, "error.no-permission");
        if (active(player.getUniqueId()) == null) return fail(player, "error.not-found");
        retract(player.getUniqueId());
        saveNow();
        return success(player, "pet.recalled");
    }

    public void retract(UUID owner) {
        UUID id = activeByOwner.get(owner);
        if (id == null) return;
        Mob entity = entities.get(id);
        try {
            if (entity != null) {
                PetData data = records.get(id);
                if (entity.isValid() && data != null) PetEntityState.rememberInventory(entity, data);
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not snapshot pet inventory; keeping the previous inventory snapshot: " + id, error);
        } finally {
            activeByOwner.remove(owner);
            entities.remove(id);
            riders.remove(owner);
            playTickets.remove(id);
            travelLocations.remove(id);
            travelDistance.remove(id);
            if (entity != null) {
                entityIds.remove(entity.getUniqueId());
                try { entity.eject(); }
                finally { entity.remove(); }
            }
            touch();
        }
    }

    public void removeStale(Entity entity) {
        if (isPet(entity) && !entityIds.containsKey(entity.getUniqueId())) entity.remove();
    }

    public void onDeath(Entity entity) {
        PetData data = petFor(entity);
        if (data == null) return;
        // Preserve inventory from the record, never expose equipment as loot.
        retract(data.owner());
        Player owner = Bukkit.getPlayer(data.owner());
        if (owner != null) plugin.messages().send(owner, "pet.recovered", "name", data.name());
    }

    public boolean feed(Player player, UUID id) {
        if (!usable(player)) return false;
        PetData data = ownedBy(player, id);
        if (data == null) return false;
        int remaining = remainingSeconds(data.lastFeed(), plugin.settings().feedCooldownSeconds());
        if (remaining > 0) return fail(player, "error.cooldown", "seconds", remaining);
        int slot = findFood(player);
        if (slot < 0) return fail(player, "error.no-food");
        ItemStack item = player.getInventory().getItem(slot);
        Objects.requireNonNull(item).setAmount(item.getAmount() - 1);
        data.setLastFeed(System.currentTimeMillis());
        award(data, plugin.settings().feedXp(), plugin.settings().feedBond());
        Mob entity = entities.get(id);
        if (entity != null) {
            AttributeInstance maximum = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maximum != null) entity.setHealth(maximum.getValue());
            entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation().add(0, entity.getHeight(), 0), 3, .25, .2, .25, 0);
        }
        return success(player, "pet.fed", "name", data.name(), "xp", plugin.settings().feedXp(), "bond", plugin.settings().feedBond());
    }

    public boolean rename(Player player, UUID id, String name) {
        if (!managementAllowed(player)) return false;
        PetData data = manageableBy(player, id);
        if (data == null) return false;
        name = name.strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 32 || name.indexOf('§') >= 0
                || !ChatColor.translateAlternateColorCodes('&', name).equals(name)
                || name.codePoints().anyMatch(Character::isISOControl)) return fail(player, "error.invalid-name");
        data.setName(name);
        Mob entity = entities.get(id);
        if (entity != null) entity.setCustomName(name);
        if (canAdmin(player)) queueAdminSave(); else touch();
        return success(player, "pet.renamed", "name", name);
    }

    public boolean setMode(Player player, UUID id, PetData.Mode mode) {
        if (!managementAllowed(player)) return false;
        PetData data = manageableBy(player, id);
        if (data == null) return false;
        data.setMode(mode);
        Mob entity = entities.get(id);
        if (entity != null) {
            PetEntityState.prepareControlledMovement(entity);
            entity.setVelocity(new Vector());
        }
        if (canAdmin(player)) queueAdminSave(); else touch();
        return success(player, mode == PetData.Mode.FOLLOW ? "pet.mode-follow" : "pet.mode-stay");
    }

    public boolean upgrade(Player player, UUID id, String stat) {
        if (!managementAllowed(player)) return false;
        PetData data = manageableBy(player, id);
        if (data == null) return false;
        if (!Progression.upgrade(data, stat, plugin.settings())) return fail(player, "error.upgrade");
        Mob entity = entities.get(id);
        if (entity != null) PetEntityState.configure(entity, data, plugin.settings());
        if (canAdmin(player)) queueAdminSave(); else touch();
        return success(player, "pet.upgraded");
    }

    public boolean delete(Player player, UUID id) {
        if (!managementAllowed(player)) return false;
        PetData data = manageableBy(player, id);
        if (data == null) return false;
        if (isActive(id)) retract(data.owner());
        records.remove(id);
        List<PetData> list = owners.get(data.owner());
        list.remove(data);
        if (list.isEmpty()) owners.remove(data.owner());
        playTickets.remove(id);
        touch();
        saveNow();
        return success(player, "pet.deleted");
    }

    public boolean beginPlay(Player player, UUID id) {
        if (!usable(player)) return false;
        PetData data = ownedBy(player, id);
        if (data == null) return false;
        if (!plugin.settings().gamesEnabled()) return fail(player, "error.disabled");
        Mob mob = entities.get(id);
        if (mob == null || mob.getWorld() != player.getWorld() || mob.getLocation().distanceSquared(player.getLocation()) > 256)
            return fail(player, "error.no-active");
        int remaining = remainingSeconds(data.lastPlay(), plugin.settings().playCooldownSeconds());
        if (remaining > 0) return fail(player, "error.cooldown", "seconds", remaining);
        data.setLastPlay(System.currentTimeMillis());
        playTickets.put(id, 1);
        touch();
        return true;
    }

    public void rewardPlay(Player player, UUID id, double multiplier) {
        if (!usable(player)) return;
        PetData data = ownedBy(player, id);
        if (data == null || !plugin.settings().gamesEnabled() || playTickets.remove(id) == null) return;
        double reward = Math.max(0, Math.min(2, Double.isFinite(multiplier) ? multiplier : 0));
        long xp = (long) (plugin.settings().playXp() * reward);
        int bond = (int) (plugin.settings().playBond() * reward);
        award(data, xp, bond);
        plugin.messages().send(player, "pet.play-reward", "xp", xp, "bond", bond);
    }

    private void award(PetData data, long xp, int bond) {
        int levels = Progression.addXp(data, xp, plugin.settings());
        data.setBond((int) Math.max(data.bond(), Math.min(plugin.settings().maxBond(), (long) data.bond() + bond)));
        if (levels > 0) {
            Mob entity = entities.get(data.id());
            if (entity != null) PetEntityState.configure(entity, data, plugin.settings());
            Player owner = Bukkit.getPlayer(data.owner());
            if (owner != null) plugin.messages().send(owner, "pet.level-up", "name", data.name(),
                    "level", Progression.level(data, plugin.settings()), "points", levels * plugin.settings().pointsPerLevel());
        }
        touch();
    }

    public boolean ride(Player player, UUID id) {
        if (!usable(player)) return false;
        PetData data = ownedBy(player, id);
        if (data == null) return false;
        if (!plugin.settings().ridingEnabled()) return fail(player, "error.riding-disabled");
        if (!Progression.adult(data, plugin.settings())) return fail(player, "error.young", "level", plugin.settings().adultLevel());
        if (flying(data) && !plugin.settings().flightEnabled()) return fail(player, "error.flight-disabled");
        if (player.isInsideVehicle()) return fail(player, "error.mounted");
        Mob mob = entities.get(id);
        if (mob == null) { if (!summon(player, id)) return false; mob = entities.get(id); }
        if (mob == null || mob.getWorld() != player.getWorld() || mob.getLocation().distanceSquared(player.getLocation()) > 36)
            return fail(player, "error.no-active");
        if (aquatic(data) && !mob.isInWater()) return fail(player, "error.water-required");
        mounting.add(player.getUniqueId());
        boolean mounted;
        try { mounted = mob.addPassenger(player); }
        finally { mounting.remove(player.getUniqueId()); }
        if (!mounted) return fail(player, "error.busy");
        riders.put(player.getUniqueId(), id);
        return success(player, "pet.riding");
    }

    public boolean mayMount(Entity passenger, Entity mount) {
        PetData data = petFor(mount);
        return data != null && passenger instanceof Player player && data.owner().equals(player.getUniqueId())
                && mounting.contains(player.getUniqueId());
    }

    public void dismount(Entity passenger, Entity mount) {
        if (passenger instanceof Player player && isPet(mount)) {
            riders.remove(player.getUniqueId());
            mount.setVelocity(new Vector());
            // A brief fall-damage grace period covers deliberate midair dismounts without changing flight permissions.
            player.setFallDistance(0);
            if (mount instanceof Mob mob && flyingType(mob.getType())) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 200, 0, false, false));
            }
        }
    }

    public void giveFood(Player player, int amount) {
        while (amount > 0) {
            int count = Math.min(amount, plugin.settings().feedMaterial().getMaxStackSize());
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(food(count));
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            amount -= count;
        }
    }

    public ItemStack food(int amount) {
        ItemStack item = new ItemStack(plugin.settings().feedMaterial(), amount);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.setDisplayName(plugin.messages().text("food.name"));
        meta.setLore(plugin.messages().list("food.lore"));
        meta.getPersistentDataContainer().set(foodKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isFood(ItemStack item) {
        return item != null && item.hasItemMeta()
                && Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer().get(foodKey, PersistentDataType.BYTE));
    }

    private int findFood(Player player) {
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++) if (isFood(player.getInventory().getItem(i))) return i;
        return -1;
    }

    private void consumeMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        item.setAmount(item.getAmount() - 1);
    }

    public void reconfigure() {
        registerRecipe();
        for (PetData data : new ArrayList<>(records.values())) {
            Mob entity = entities.get(data.id());
            if (entity == null) continue;
            if (!plugin.settings().enabled() || plugin.settings().disabledWorlds().contains(entity.getWorld().getName())) retract(data.owner());
            else {
                if (!plugin.settings().ridingEnabled() || (flying(data) && !plugin.settings().flightEnabled())) {
                    entity.eject(); riders.remove(data.owner());
                }
                PetEntityState.configure(entity, data, plugin.settings());
            }
        }
        playTickets.clear();
        travelLocations.clear();
        travelDistance.clear();
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!plugin.settings().enabled() || !plugin.settings().recipeEnabled()) return;
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, food(2));
        recipe.addIngredient(Material.WHEAT).addIngredient(Material.APPLE).addIngredient(Material.SUGAR).addIngredient(Material.BONE);
        Bukkit.addRecipe(recipe);
    }

    private void tick() {
        if (stopping) return;
        tick++;
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(riders.entrySet())) {
            try { steer(entry.getKey(), entry.getValue()); }
            catch (RuntimeException error) { isolateFailure(entry.getValue(), error); }
        }
        PetSettings settings = plugin.settings();
        if (tick % settings.followTicks() == 0) {
            for (UUID id : new ArrayList<>(entities.keySet())) {
                try { follow(id); }
                catch (RuntimeException error) { isolateFailure(id, error); }
            }
        }
        if (tick % 20 == 0) sampleTravel();
        if (tick % (settings.travelIntervalSeconds() * 20L) == 0) travel();
        if (tick % (settings.autosaveSeconds() * 20L) == 0 && dirty) saveNow();
    }

    private void isolateFailure(UUID id, RuntimeException error) {
        plugin.getLogger().log(Level.WARNING, "Retracting pet after entity update failure: " + id, error);
        PetData data = records.get(id);
        if (data != null) retract(data.owner());
    }

    private void steer(UUID playerId, UUID id) {
        Player player = Bukkit.getPlayer(playerId);
        Mob mob = entities.get(id);
        PetData data = records.get(id);
        if (player == null || mob == null || data == null || !mob.isValid() || player.getVehicle() != mob) { riders.remove(playerId); return; }
        if (!player.hasPermission("happypets.use")) { mob.removePassenger(player); riders.remove(playerId); return; }
        Input input = player.getCurrentInput();
        boolean flying = flying(data), swimming = aquatic(data);
        double speed = PetMotion.speed(data, plugin.settings(), flying, swimming);
        Vector velocity = PetMotion.steering(player.getLocation().getYaw(), input.isForward(), input.isBackward(), input.isLeft(), input.isRight(), speed);
        double y = mob.getVelocity().getY();
        if (flying || (swimming && mob.isInWater())) {
            y = input.isJump() ? speed * .8 : input.isForward() ? -Math.sin(Math.toRadians(player.getLocation().getPitch())) * speed : 0;
            if (flying && mob.getLocation().getY() >= Math.min(plugin.settings().flyingCeiling(), mob.getWorld().getMaxHeight() - 2)) y = Math.min(-.1, y);
            if (mob.getLocation().getY() <= mob.getWorld().getMinHeight() + 2) y = Math.max(.1, y);
            mob.setFallDistance(0);
            player.setFallDistance(0);
        } else if (input.isJump() && mob.isOnGround()) {
            y = Math.min(1.2, .42 + data.jump() * plugin.settings().jumpPerUpgrade());
        } else if (mob.isOnGround() && velocity.lengthSquared() > 0) y = step(mob, velocity) ? .42 : y;
        velocity.setY(y);
        if (!loadedDestination(mob, velocity)) velocity = new Vector(0, Math.min(0, y), 0);
        mob.setRotation(player.getLocation().getYaw(), 0);
        mob.setVelocity(velocity);
    }

    private void follow(UUID id) {
        PetData data = records.get(id);
        Mob mob = entities.get(id);
        if (data == null || mob == null) return;
        Player player = Bukkit.getPlayer(data.owner());
        if (!mob.isValid() || player == null || player.isDead() || !player.hasPermission("happypets.use")
                || plugin.settings().disabledWorlds().contains(player.getWorld().getName()) || player.getWorld() != mob.getWorld()) { retract(data.owner()); return; }
        mob.setFireTicks(0);
        mob.setRemainingAir(mob.getMaximumAir());
        if (riders.containsKey(data.owner())) return;
        if (data.mode() == PetData.Mode.STAY) { mob.setVelocity(new Vector(0, flying(data) ? 0 : mob.getVelocity().getY(), 0)); return; }
        double distance = mob.getLocation().distanceSquared(player.getLocation());
        if (distance > plugin.settings().teleportDistance() * plugin.settings().teleportDistance()) {
            Location safe = safeNear(player, data.type(), mob.getWidth(), mob.getHeight());
            if (safe != null) mob.teleport(safe);
            travelLocations.put(id, player.getLocation());
            return;
        }
        if (distance <= plugin.settings().followDistance() * plugin.settings().followDistance()) {
            mob.setVelocity(new Vector(0, flying(data) ? 0 : mob.getVelocity().getY(), 0));
            return;
        }
        Vector direction = player.getLocation().toVector().subtract(mob.getLocation().toVector());
        boolean fly = flying(data), swim = aquatic(data) && mob.isInWater();
        if (!fly && !swim) direction.setY(0);
        if (direction.lengthSquared() < .001) return;
        direction.normalize().multiply(Math.min(.45, PetMotion.speed(data, plugin.settings(), fly, swim)));
        if (!fly && !swim) direction.setY(mob.isOnGround() && step(mob, direction) ? .42 : mob.getVelocity().getY());
        if (loadedDestination(mob, direction)) {
            mob.setRotation((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())), 0);
            mob.setVelocity(direction);
        } else mob.setVelocity(new Vector());
    }

    private boolean loadedDestination(Mob mob, Vector velocity) {
        Location next = mob.getLocation().add(velocity.clone().multiply(2));
        return next.getWorld().isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)
                && next.getWorld().getWorldBorder().isInside(next);
    }

    private boolean step(Mob mob, Vector direction) {
        Location front = mob.getLocation().add(direction.clone().setY(0).normalize().multiply(mob.getWidth() / 2 + .55));
        if (!front.getWorld().isChunkLoaded(front.getBlockX() >> 4, front.getBlockZ() >> 4)) return false;
        return front.getBlock().getType().isSolid() && front.clone().add(0, 1, 0).getBlock().isPassable()
                && front.clone().add(0, Math.max(2, mob.getHeight()), 0).getBlock().isPassable();
    }

    private Location safeNear(Player player, EntityType type, double width, double height) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        boolean water = plugin.settings().aquaticTypes().contains(type);
        boolean air = flyingType(type);
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    Location candidate = origin.clone().add(dx, dy, dz);
                    candidate.setX(candidate.getBlockX() + .5); candidate.setY(candidate.getBlockY()); candidate.setZ(candidate.getBlockZ() + .5);
                    if (candidate.getY() < world.getMinHeight() + 1 || candidate.getY() + height >= world.getMaxHeight()
                            || !world.getWorldBorder().isInside(candidate)) continue;
                    if (!clear(world, candidate, width, height, water)) continue;
                    if (!air && !water && !candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) continue;
                    if (water && candidate.getBlock().getType() != Material.WATER) continue;
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean clear(World world, Location origin, double width, double height, boolean water) {
        double radius = Math.max(.3, width / 2);
        for (int x = (int) Math.floor(origin.getX() - radius); x <= Math.floor(origin.getX() + radius); x++) {
            for (int z = (int) Math.floor(origin.getZ() - radius); z <= Math.floor(origin.getZ() + radius); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                for (int y = origin.getBlockY(); y <= Math.floor(origin.getY() + height); y++) {
                    var block = world.getBlockAt(x, y, z);
                    if (!block.isPassable() || block.getType() == Material.LAVA || (!water && block.isLiquid())) return false;
                }
            }
        }
        return true;
    }

    private void sampleTravel() {
        for (Map.Entry<UUID, Mob> entry : entities.entrySet()) {
            PetData data = records.get(entry.getKey());
            Player player = Bukkit.getPlayer(data.owner());
            if (player == null || player.getWorld() != entry.getValue().getWorld()
                    || (data.mode() == PetData.Mode.STAY && !riders.containsKey(data.owner()))) continue;
            Location current = player.getLocation(), last = travelLocations.put(data.id(), current);
            if (last == null || last.getWorld() != current.getWorld()) continue;
            double distance = current.distance(last);
            if (distance < .25 || distance > plugin.settings().teleportDistance()
                    || current.distanceSquared(entry.getValue().getLocation()) > plugin.settings().teleportDistance() * plugin.settings().teleportDistance()) continue;
            double total = travelDistance.getOrDefault(data.id(), 0.0) + distance;
            travelDistance.put(data.id(), Math.min(total, plugin.settings().travelDistance() * 2));
        }
    }

    private void travel() {
        for (UUID id : new ArrayList<>(entities.keySet())) {
            double total = travelDistance.getOrDefault(id, 0.0);
            if (total >= plugin.settings().travelDistance()) {
                try {
                    award(records.get(id), plugin.settings().travelXp(), 1);
                    travelDistance.put(id, total % plugin.settings().travelDistance());
                } catch (RuntimeException error) { isolateFailure(id, error); }
            }
        }
    }

    public void saveNow() {
        for (Map.Entry<UUID, Mob> entry : entities.entrySet()) if (entry.getValue().isValid()) PetEntityState.rememberInventory(entry.getValue(), records.get(entry.getKey()));
        store.saveAsync(all());
        dirty = false;
    }

    public void shutdown() {
        stopping = true;
        if (task != null) task.cancel();
        if (adminSaveTask != null) adminSaveTask.cancel();
        for (UUID owner : new ArrayList<>(activeByOwner.keySet())) retract(owner);
        Bukkit.removeRecipe(recipeKey);
        playTickets.clear();
    }

    boolean flying(PetData data) { return flyingType(data.type()); }
    private boolean flyingType(EntityType type) { return plugin.settings().flyingTypes().contains(type); }
    private boolean aquatic(PetData data) { return plugin.settings().aquaticTypes().contains(data.type()); }

    static int remainingSeconds(long last, int seconds) {
        long remaining = seconds * 1000L - Math.max(0, System.currentTimeMillis() - last);
        return remaining <= 0 ? 0 : (int) ((remaining + 999) / 1000);
    }

    private boolean success(Player player, String key, Object... pairs) { plugin.messages().send(player, key, pairs); return true; }
    private boolean fail(Player player, String key, Object... pairs) { plugin.messages().send(player, key, pairs); return false; }
}
