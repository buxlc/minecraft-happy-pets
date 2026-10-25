package cn.bucheng.happypets;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validated configuration snapshot; active operations never read a changing YAML object. */
public record PetSettings(
        boolean enabled, String language, int maxPets, int maxLevel, int adultLevel,
        long xpBase, double xpFactor, int pointsPerLevel, int maxBond,
        long feedXp, int feedBond, int feedCooldownSeconds,
        long playXp, int playBond, int playCooldownSeconds,
        long travelXp, double travelDistance, int travelIntervalSeconds,
        int autosaveSeconds, int followTicks, double followDistance, double teleportDistance,
        int maxSpeedUpgrades, int maxHealthUpgrades, int maxJumpUpgrades,
        double speedPerUpgrade, double healthPerUpgrade, double jumpPerUpgrade,
        double groundSpeed, double flySpeed, double swimSpeed, double maxRideSpeed, double flyingCeiling,
        Set<EntityType> flyingTypes, Set<EntityType> aquaticTypes, Set<EntityType> excludedTypes,
        Set<String> disabledWorlds, boolean ridingEnabled, boolean flightEnabled, boolean gamesEnabled,
        boolean protectPets, Material feedMaterial, boolean recipeEnabled, List<Long> xpThresholds) {

    public PetSettings {
        flyingTypes = Set.copyOf(flyingTypes);
        aquaticTypes = Set.copyOf(aquaticTypes);
        excludedTypes = Set.copyOf(excludedTypes);
        disabledWorlds = Set.copyOf(disabledWorlds);
        xpThresholds = List.copyOf(xpThresholds);
    }

    /** Validates every deployment setting before constructing a usable snapshot. */
    public static PetSettings from(FileConfiguration c) {
        int maxLevel = integer(c, "growth.max-level", 50, 2, 1000);
        int adultLevel = integer(c, "growth.adult-level", 10, 1, maxLevel);
        long xpBase = whole(c, "growth.xp-base", 100, 1, 1_000_000_000);
        double xpFactor = decimal(c, "growth.xp-factor", 1.18, 1, 3);
        double followDistance = decimal(c, "follow.distance", 3, 1, 32);
        double teleportDistance = decimal(c, "follow.teleport-distance", 24, followDistance + 1, 128);
        String language = string(c, "language", "zh_CN");
        if (!language.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) throw invalid("language", "must be a language file name");
        Material material;
        try { material = Material.valueOf(string(c, "feed.material", "COOKIE").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw invalid("feed.material", "unknown material"); }
        if (!material.isItem() || material.isAir()) throw invalid("feed.material", "must be an item");
        List<Long> thresholds = new ArrayList<>(maxLevel);
        long total = 0;
        thresholds.add(0L);
        for (int level = 1; level < maxLevel; level++) {
            double raw = Math.ceil(xpBase * Math.pow(xpFactor, level - 1));
            long required = raw >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) raw;
            total = total >= Long.MAX_VALUE - required ? Long.MAX_VALUE : total + required;
            thresholds.add(total);
        }
        return new PetSettings(
                bool(c, "enabled", true), language, integer(c, "limits.max-pets", 5, 1, 45), maxLevel, adultLevel,
                xpBase, xpFactor, integer(c, "growth.points-per-level", 2, 0, 100), integer(c, "growth.max-bond", 1000, 1, 1_000_000),
                whole(c, "rewards.feed.xp", 40, 0, 1_000_000_000), integer(c, "rewards.feed.bond", 5, 0, 10000), integer(c, "rewards.feed.cooldown-seconds", 30, 1, 86400),
                whole(c, "rewards.play.xp", 80, 0, 1_000_000_000), integer(c, "rewards.play.bond", 15, 0, 10000), integer(c, "rewards.play.cooldown-seconds", 60, 1, 86400),
                whole(c, "rewards.travel.xp", 15, 0, 1_000_000_000), decimal(c, "rewards.travel.distance", 40, 1, 10000), integer(c, "rewards.travel.interval-seconds", 20, 1, 3600),
                integer(c, "performance.autosave-seconds", 60, 5, 3600), integer(c, "performance.follow-ticks", 10, 2, 100), followDistance, teleportDistance,
                integer(c, "upgrades.speed.max", 30, 0, 1000), integer(c, "upgrades.health.max", 30, 0, 1000), integer(c, "upgrades.jump.max", 20, 0, 1000),
                decimal(c, "upgrades.speed.per-level", 0.06, 0, 2), decimal(c, "upgrades.health.per-level", 2, 0, 100), decimal(c, "upgrades.jump.per-level", 0.02, 0, 1),
                decimal(c, "riding.ground-speed", 0.28, 0.01, 1.5), decimal(c, "riding.fly-speed", 0.35, 0.01, 1.5), decimal(c, "riding.swim-speed", 0.3, 0.01, 1.5),
                decimal(c, "riding.max-speed", 1.2, 0.01, 2), decimal(c, "riding.flying-ceiling", 300, -64, 2048),
                types(c, "riding.flying-types", List.of("BAT", "PARROT", "BEE", "ALLAY", "PHANTOM", "VEX", "GHAST", "BLAZE")),
                types(c, "riding.aquatic-types", List.of("COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "DOLPHIN", "SQUID", "GLOW_SQUID", "AXOLOTL", "TURTLE", "GUARDIAN")),
                types(c, "capture.excluded-types", List.of()), Set.copyOf(strings(c, "capture.disabled-worlds", List.of())),
                bool(c, "riding.enabled", true), bool(c, "riding.flight-enabled", true), bool(c, "games.enabled", true),
                bool(c, "protection.invulnerable", true), material, bool(c, "feed.recipe-enabled", true), thresholds);
    }

    private static boolean bool(FileConfiguration c, String key, boolean fallback) {
        Object raw = c.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Boolean value)) throw invalid(key, "must be true or false");
        return value;
    }
    private static String string(FileConfiguration c, String key, String fallback) {
        Object raw = c.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof String value) || value.isBlank()) throw invalid(key, "must be a non-empty string");
        return value;
    }
    private static int integer(FileConfiguration c, String key, int fallback, int min, int max) {
        return (int) whole(c, key, fallback, min, max);
    }
    private static long whole(FileConfiguration c, String key, long fallback, long min, long max) {
        Object raw = c.get(key);
        if (raw == null) raw = fallback;
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) throw invalid(key, "must be a whole number");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw invalid(key, "must be between " + min + " and " + max);
        return value;
    }
    private static double decimal(FileConfiguration c, String key, double fallback, double min, double max) {
        Object raw = c.get(key);
        if (raw == null) raw = fallback;
        if (!(raw instanceof Number number)) throw invalid(key, "must be a number");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw invalid(key, "must be between " + min + " and " + max);
        return value;
    }
    private static List<String> strings(FileConfiguration c, String key, List<String> fallback) {
        Object raw = c.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof List<?> values)) throw invalid(key, "must be a list");
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String item) || item.isBlank()) throw invalid(key, "must contain non-empty strings");
            result.add(item);
        }
        return result;
    }
    private static Set<EntityType> types(FileConfiguration c, String key, List<String> fallback) {
        Set<EntityType> result = new HashSet<>();
        for (String item : strings(c, key, fallback)) {
            EntityType type;
            try { type = EntityType.valueOf(item.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw invalid(key, "unknown entity type " + item); }
            if (!type.isAlive() || !type.isSpawnable() || type == EntityType.ARMOR_STAND) throw invalid(key, "must contain mob types: " + item);
            result.add(type);
        }
        return result;
    }
    private static IllegalArgumentException invalid(String key, String reason) {
        return new IllegalArgumentException("Invalid config '" + key + "': " + reason);
    }
}
