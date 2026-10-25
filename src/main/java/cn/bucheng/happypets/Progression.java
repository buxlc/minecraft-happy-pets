package cn.bucheng.happypets;

import java.util.Locale;

/** Bounded progression calculations over configuration-time XP thresholds. */
public final class Progression {
    private Progression() { }

    public static int level(PetData pet, PetSettings settings) {
        int low = 1;
        int high = settings.maxLevel();
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (pet.xp() >= xpAtLevel(middle, settings)) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    public static long requiredForNextLevel(int level, PetSettings settings) {
        if (level < 1 || level >= settings.maxLevel()) return 0;
        double raw = Math.ceil(settings.xpBase() * Math.pow(settings.xpFactor(), level - 1));
        return raw >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) raw;
    }

    public static long xpAtLevel(int level, PetSettings settings) {
        if (level < 1 || level > settings.maxLevel()) throw new IllegalArgumentException("Level outside configured range");
        return settings.xpThresholds().get(level - 1);
    }

    public static boolean adult(PetData pet, PetSettings settings) {
        return level(pet, settings) >= settings.adultLevel();
    }

    /** Returns the number of levels gained and grants each level's upgrade points once. */
    public static int addXp(PetData pet, long amount, PetSettings settings) {
        if (amount <= 0) return 0;
        int before = level(pet, settings);
        long sum = pet.xp() > Long.MAX_VALUE - amount ? Long.MAX_VALUE : pet.xp() + amount;
        pet.setXp(Math.max(pet.xp(), Math.min(sum, xpAtLevel(settings.maxLevel(), settings))));
        int gained = level(pet, settings) - before;
        long points = (long) pet.points() + (long) gained * settings.pointsPerLevel();
        pet.setPoints((int) Math.min(Integer.MAX_VALUE, points));
        return gained;
    }

    /** Spends one point only when the selected statistic can increase. */
    public static boolean upgrade(PetData pet, String stat, PetSettings settings) {
        if (pet.points() <= 0 || stat == null) return false;
        switch (stat.toLowerCase(Locale.ROOT)) {
            case "speed" -> { if (pet.speed() >= settings.maxSpeedUpgrades()) return false; pet.setSpeed(pet.speed() + 1); }
            case "health" -> { if (pet.health() >= settings.maxHealthUpgrades()) return false; pet.setHealth(pet.health() + 1); }
            case "jump" -> { if (pet.jump() >= settings.maxJumpUpgrades()) return false; pet.setJump(pet.jump() + 1); }
            default -> { return false; }
        }
        pet.setPoints(pet.points() - 1);
        return true;
    }
}
