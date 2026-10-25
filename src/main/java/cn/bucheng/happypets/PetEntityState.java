package cn.bucheng.happypets;

import java.util.List;
import java.util.Map;
import org.bukkit.DyeColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Preserves portable visual variants and inventories without depending on internal server classes. */
final class PetEntityState {
    private PetEntityState() { }

    static void capture(Mob entity, PetData data) {
        Map<String, String> a = data.appearance();
        AttributeInstance health = entity.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
        data.setBaseHealth(Math.max(1, Math.min(1024, health == null ? 20 : health.getBaseValue())));
        data.setBaseSpeed(Math.max(0, Math.min(1024, speed == null ? .2 : speed.getBaseValue())));
        AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        a.put("scale", Double.toString(scale == null ? 1 : scale.getBaseValue()));
        if (entity instanceof Sheep e) { a.put("color", e.getColor().name()); a.put("sheared", Boolean.toString(e.isSheared())); }
        if (entity instanceof Cat e) { a.put("cat", e.getCatType().getKey().toString()); a.put("collar", e.getCollarColor().name()); }
        if (entity instanceof Wolf e) { a.put("wolf", e.getVariant().getKey().toString()); a.put("collar", e.getCollarColor().name()); }
        if (entity instanceof Frog e) a.put("frog", e.getVariant().getKey().toString());
        if (entity instanceof Rabbit e) a.put("rabbit", e.getRabbitType().name());
        if (entity instanceof Fox e) a.put("fox", e.getFoxType().name());
        if (entity instanceof Axolotl e) a.put("axolotl", e.getVariant().name());
        if (entity instanceof Parrot e) a.put("parrot", e.getVariant().name());
        if (entity instanceof MushroomCow e) a.put("mooshroom", e.getVariant().name());
        if (entity instanceof TropicalFish e) { a.put("body", e.getBodyColor().name()); a.put("pattern-color", e.getPatternColor().name()); a.put("pattern", e.getPattern().name()); }
        if (entity instanceof Horse e) { a.put("horse-color", e.getColor().name()); a.put("horse-style", e.getStyle().name()); }
        if (entity instanceof Llama e) { a.put("llama", e.getColor().name()); a.put("strength", Integer.toString(e.getStrength())); }
        if (entity instanceof Panda e) { a.put("gene", e.getMainGene().name()); a.put("hidden-gene", e.getHiddenGene().name()); }
        if (entity instanceof Slime e) a.put("size", Integer.toString(e.getSize()));
        if (entity instanceof Phantom e) a.put("phantom-size", Integer.toString(e.getSize()));
        if (entity instanceof Villager e) { a.put("profession", e.getProfession().getKey().toString()); a.put("villager-type", e.getVillagerType().getKey().toString()); a.put("villager-level", Integer.toString(e.getVillagerLevel())); }
        if (entity instanceof ChestedHorse e) a.put("chest", Boolean.toString(e.isCarryingChest()));
        if (entity instanceof Creeper e) a.put("charged", Boolean.toString(e.isPowered()));
        if (entity instanceof Steerable e) a.put("saddle", Boolean.toString(e.hasSaddle()));
        if (entity instanceof Snowman e) a.put("derp", Boolean.toString(e.isDerp()));
        if (entity instanceof Shulker e && e.getColor() != null) a.put("shulker-color", e.getColor().name());
        if (entity instanceof Enderman e && e.getCarriedBlock() != null) a.put("carried-block", e.getCarriedBlock().getAsString());
        if (entity instanceof ZombieVillager e) { a.put("zombie-profession", e.getVillagerProfession().getKey().toString()); a.put("zombie-type", e.getVillagerType().getKey().toString()); }
        rememberInventory(entity, data);
    }

    static void rememberInventory(Mob entity, PetData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (entity instanceof InventoryHolder holder) yaml.set("inventory", holder.getInventory().getContents());
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            yaml.set("armor", equipment.getArmorContents());
            yaml.set("main", equipment.getItemInMainHand());
            yaml.set("off", equipment.getItemInOffHand());
            yaml.set("body", equipment.getItem(EquipmentSlot.BODY));
        }
        data.appearance().put("items", yaml.saveToString());
    }

    static void restore(Mob entity, PetData data) throws InvalidConfigurationException {
        Map<String, String> a = data.appearance();
        if (entity instanceof Sheep e && a.containsKey("color")) { e.setColor(DyeColor.valueOf(a.get("color"))); e.setSheared(Boolean.parseBoolean(a.get("sheared"))); }
        if (entity instanceof Cat e && a.containsKey("cat")) { e.setCatType(java.util.Objects.requireNonNull(Registry.CAT_VARIANT.get(NamespacedKey.fromString(a.get("cat"))))); e.setCollarColor(DyeColor.valueOf(a.get("collar"))); }
        if (entity instanceof Wolf e && a.containsKey("wolf")) { e.setVariant(java.util.Objects.requireNonNull(Registry.WOLF_VARIANT.get(NamespacedKey.fromString(a.get("wolf"))))); e.setCollarColor(DyeColor.valueOf(a.get("collar"))); }
        if (entity instanceof Frog e && a.containsKey("frog")) e.setVariant(java.util.Objects.requireNonNull(Registry.FROG_VARIANT.get(NamespacedKey.fromString(a.get("frog")))));
        if (entity instanceof Rabbit e && a.containsKey("rabbit")) e.setRabbitType(Rabbit.Type.valueOf(a.get("rabbit")));
        if (entity instanceof Fox e && a.containsKey("fox")) e.setFoxType(Fox.Type.valueOf(a.get("fox")));
        if (entity instanceof Axolotl e && a.containsKey("axolotl")) e.setVariant(Axolotl.Variant.valueOf(a.get("axolotl")));
        if (entity instanceof Parrot e && a.containsKey("parrot")) e.setVariant(Parrot.Variant.valueOf(a.get("parrot")));
        if (entity instanceof MushroomCow e && a.containsKey("mooshroom")) e.setVariant(MushroomCow.Variant.valueOf(a.get("mooshroom")));
        if (entity instanceof TropicalFish e && a.containsKey("body")) { e.setBodyColor(DyeColor.valueOf(a.get("body"))); e.setPatternColor(DyeColor.valueOf(a.get("pattern-color"))); e.setPattern(TropicalFish.Pattern.valueOf(a.get("pattern"))); }
        if (entity instanceof Horse e && a.containsKey("horse-color")) { e.setColor(Horse.Color.valueOf(a.get("horse-color"))); e.setStyle(Horse.Style.valueOf(a.get("horse-style"))); }
        if (entity instanceof Llama e && a.containsKey("llama")) { e.setColor(Llama.Color.valueOf(a.get("llama"))); e.setStrength(Integer.parseInt(a.get("strength"))); }
        if (entity instanceof Panda e && a.containsKey("gene")) { e.setMainGene(Panda.Gene.valueOf(a.get("gene"))); e.setHiddenGene(Panda.Gene.valueOf(a.get("hidden-gene"))); }
        if (entity instanceof Slime e && a.containsKey("size")) e.setSize(Integer.parseInt(a.get("size")));
        if (entity instanceof Phantom e && a.containsKey("phantom-size")) e.setSize(Integer.parseInt(a.get("phantom-size")));
        if (entity instanceof Villager e && a.containsKey("profession")) { e.setProfession(java.util.Objects.requireNonNull(Registry.VILLAGER_PROFESSION.get(NamespacedKey.fromString(a.get("profession"))))); e.setVillagerType(java.util.Objects.requireNonNull(Registry.VILLAGER_TYPE.get(NamespacedKey.fromString(a.get("villager-type"))))); e.setVillagerLevel(Integer.parseInt(a.get("villager-level"))); }
        if (entity instanceof ChestedHorse e) e.setCarryingChest(Boolean.parseBoolean(a.getOrDefault("chest", "false")));
        if (entity instanceof Creeper e) e.setPowered(Boolean.parseBoolean(a.getOrDefault("charged", "false")));
        if (entity instanceof Steerable e) e.setSaddle(Boolean.parseBoolean(a.getOrDefault("saddle", "false")));
        if (entity instanceof Snowman e && a.containsKey("derp")) e.setDerp(Boolean.parseBoolean(a.get("derp")));
        if (entity instanceof Shulker e && a.containsKey("shulker-color")) e.setColor(DyeColor.valueOf(a.get("shulker-color")));
        if (entity instanceof Enderman e && a.containsKey("carried-block")) e.setCarriedBlock(org.bukkit.Bukkit.createBlockData(a.get("carried-block")));
        if (entity instanceof ZombieVillager e && a.containsKey("zombie-profession")) { e.setVillagerProfession(java.util.Objects.requireNonNull(Registry.VILLAGER_PROFESSION.get(NamespacedKey.fromString(a.get("zombie-profession"))))); e.setVillagerType(java.util.Objects.requireNonNull(Registry.VILLAGER_TYPE.get(NamespacedKey.fromString(a.get("zombie-type"))))); }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(a.getOrDefault("items", ""));
        if (entity instanceof InventoryHolder holder && yaml.isList("inventory")) holder.getInventory().setContents(items(yaml.getList("inventory"), holder.getInventory().getSize()));
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            if (yaml.isList("armor")) equipment.setArmorContents(items(yaml.getList("armor"), 4));
            if (yaml.contains("main")) equipment.setItemInMainHand(yaml.getItemStack("main"));
            if (yaml.contains("off")) equipment.setItemInOffHand(yaml.getItemStack("off"));
            if (yaml.contains("body")) equipment.setItem(EquipmentSlot.BODY, yaml.getItemStack("body"));
        }
    }

    private static ItemStack[] items(List<?> values, int size) {
        if (values.size() > size) throw new IllegalArgumentException("Stored pet inventory exceeds entity inventory");
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value != null && !(value instanceof ItemStack)) throw new IllegalArgumentException("Invalid stored pet inventory item");
            result[i] = (ItemStack) value;
        }
        return result;
    }

    static void configure(Mob entity, PetData data, PetSettings settings) {
        prepareControlledMovement(entity);
        entity.setCanPickupItems(false);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(false);
        entity.setInvulnerable(settings.protectPets());
        entity.setSilent(true);
        entity.setCustomName(data.name());
        entity.setCustomNameVisible(true);
        entity.setGravity(!settings.flyingTypes().contains(data.type()));
        entity.setCollidable(true);
        if (entity instanceof Sittable sitter) sitter.setSitting(false);
        if (entity instanceof Tameable tame) { tame.setTamed(true); tame.setOwner(org.bukkit.Bukkit.getOfflinePlayer(data.owner())); }
        boolean adult = Progression.adult(data, settings);
        if (entity instanceof Ageable ageable) { if (adult) ageable.setAdult(); else ageable.setBaby(); ageable.setAgeLock(true); ageable.setBreed(false); }
        if (entity instanceof Zombie zombie) zombie.setBaby(!adult);
        if (entity instanceof PiglinAbstract piglin) piglin.setImmuneToZombification(true);
        if (entity instanceof Creaking creaking) { creaking.deactivate(); creaking.setHome(null); }
        if (entity instanceof Vex vex) vex.setLifeTicks(Integer.MAX_VALUE);
        if (entity instanceof Raider raider) { raider.setCanJoinRaid(false); raider.setPatrolLeader(false); raider.setRaid(null); }
        if (entity instanceof Creeper creeper) creeper.setFuseTicks(0);
        if (entity instanceof PufferFish puffer) puffer.setPuffState(0);
        if (entity instanceof Bat bat) bat.setAwake(true);
        setAttribute(entity, Attribute.MAX_HEALTH, Math.min(1024, data.baseHealth() + data.health() * settings.healthPerUpgrade()));
        setAttribute(entity, Attribute.MOVEMENT_SPEED, Math.min(1, data.baseSpeed() * (1 + data.speed() * settings.speedPerUpgrade())));
        setAttribute(entity, Attribute.ATTACK_DAMAGE, 0);
        setAttribute(entity, Attribute.SCALE, Math.max(.0625, Math.min(16, Double.parseDouble(data.appearance().getOrDefault("scale", "1")) * ((adult || entity instanceof Ageable) ? 1 : .7))));
        AttributeInstance maximum = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null && entity.getHealth() > maximum.getValue()) entity.setHealth(maximum.getValue());
    }

    /** Keeps entity physics available for plugin movement while disabling its own behavior. */
    static void prepareControlledMovement(Mob entity) {
        entity.setAI(true);
        entity.setAware(false);
        entity.setTarget(null);
    }

    private static void setAttribute(Mob entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }
}
