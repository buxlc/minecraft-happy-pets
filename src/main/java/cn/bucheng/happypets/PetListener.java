package cn.bucheng.happypets;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.MerchantInventory;

/** Gameplay events only inspect the affected entity; no repeating world searches. */
final class PetListener implements Listener {
    private final HappyPetsPlugin plugin;
    private final Set<UUID> interactions = new HashSet<>();
    PetListener(HappyPetsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEntityEvent event) { handleInteraction(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactAt(PlayerInteractAtEntityEvent event) { handleInteraction(event); }

    private void handleInteraction(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();
        boolean pet = plugin.pets().isPet(entity);
        boolean food = plugin.pets().isFood(player.getInventory().getItemInMainHand());
        if (!pet && !food) return;
        // Pet interactions suppress vanilla trading, shearing, milking, bucketing, and mounting.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || !interactions.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> interactions.remove(player.getUniqueId()));
        if (pet) {
            PetData data = plugin.pets().petFor(entity);
            if (data == null) { plugin.pets().removeStale(entity); return; }
            if (player.isSneaking() && plugin.pets().canAdmin(player)) {
                plugin.menus().openPet(player, data.id());
                return;
            }
            if (!plugin.pets().usable(player)) return;
            if (!data.owner().equals(player.getUniqueId())) { plugin.messages().send(player, "error.not-owner"); return; }
            if (player.isSneaking()) plugin.menus().openPet(player, data.id());
            else if (food) plugin.pets().feed(player, data.id());
            else plugin.pets().ride(player, data.id());
        } else if (plugin.pets().usable(player)) {
            if (entity instanceof Mob mob) plugin.pets().capture(player, mob);
            else plugin.messages().send(player, "error.not-capturable");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (plugin.pets().isPet(event.getEntity()) && plugin.settings().protectPets()) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack && isPetSource(attack.getDamager())) event.setCancelled(true);
    }

    private boolean isPetSource(Entity entity) {
        return plugin.pets().isPet(entity)
                || entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter && plugin.pets().isPet(shooter);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) {
        if (plugin.pets().isPet(event.getEntity()) || plugin.pets().isPet(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(ExplosionPrimeEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void block(EntityChangeBlockEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void trample(EntityInteractEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(EntityCombustEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void split(SlimeSplitEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breed(EntityBreedEvent event) { if (plugin.pets().isPet(event.getMother()) || plugin.pets().isPet(event.getFather())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) { if (isPetSource(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spell(EntitySpellCastEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(EntityPortalEvent event) { if (plugin.pets().isPet(event.getEntity())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativeInventory(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof Entity entity && plugin.pets().isPet(entity)) event.setCancelled(true);
        if (event.getInventory() instanceof MerchantInventory inventory && inventory.getMerchant() instanceof Entity entity
                && plugin.pets().isPet(entity)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mount(EntityMountEvent event) {
        if (plugin.pets().isPet(event.getMount()) && !plugin.pets().mayMount(event.getEntity(), event.getMount())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dismount(EntityDismountEvent event) { plugin.pets().dismount(event.getEntity(), event.getDismounted()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void death(EntityDeathEvent event) {
        if (!plugin.pets().isPet(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.pets().onDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { plugin.pets().retract(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerDeath(PlayerDeathEvent event) { plugin.pets().retract(event.getEntity().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        boolean far = event.getFrom().getWorld() != event.getTo().getWorld()
                || event.getFrom().distanceSquared(event.getTo()) > 64;
        if (far) plugin.pets().retract(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            PetData data = plugin.pets().petFor(entity);
            if (data != null) plugin.pets().retract(data.owner());
            else plugin.pets().removeStale(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void entitiesLoad(EntitiesLoadEvent event) { for (Entity entity : event.getEntities()) plugin.pets().removeStale(entity); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void worldUnload(WorldUnloadEvent event) {
        for (PetData data : plugin.pets().all()) {
            Mob mob = plugin.pets().entity(data.id());
            if (mob != null && mob.getWorld() == event.getWorld()) plugin.pets().retract(data.owner());
        }
    }
}
