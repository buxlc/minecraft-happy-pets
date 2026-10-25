package cn.bucheng.happypets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    @Test void cumulativeThresholdsAwardEachLevelOnce() {
        PetSettings settings = SettingsFixtures.settings();
        PetData pet = pet();
        assertEquals(1, Progression.level(pet, settings));
        long target = Progression.xpAtLevel(10, settings);
        assertEquals(9, Progression.addXp(pet, target - 1, settings) + Progression.addXp(pet, 1, settings));
        assertEquals(10, Progression.level(pet, settings));
        assertTrue(Progression.adult(pet, settings));
        assertEquals(18, pet.points());
        assertEquals(0, Progression.addXp(pet, 1, settings));
        assertEquals(18, pet.points());
    }

    @Test void hugeRewardsReachTheCapWithoutOverflowOrRepeatedPoints() {
        PetSettings settings = SettingsFixtures.settings();
        PetData pet = pet();
        assertEquals(49, Progression.addXp(pet, Long.MAX_VALUE, settings));
        assertEquals(50, Progression.level(pet, settings));
        assertEquals(98, pet.points());
        assertEquals(0, Progression.addXp(pet, Long.MAX_VALUE, settings));
        assertEquals(0, Progression.requiredForNextLevel(50, settings));
        assertEquals(98, pet.points());
    }

    @Test void saturatedThresholdsRemainBoundedAtTheLargestSupportedConfiguration() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("growth.max-level", 1000);
        config.set("growth.xp-factor", 3.0);
        config.set("growth.xp-base", 1_000_000_000L);
        PetSettings settings = SettingsFixtures.settings(config);
        PetData pet = pet();
        assertEquals(999, Progression.addXp(pet, Long.MAX_VALUE, settings));
        assertEquals(1000, Progression.level(pet, settings));
        assertEquals(Long.MAX_VALUE, pet.xp());
    }

    @Test void loweringTheConfiguredCapNeverRemovesPreviouslyEarnedExperienceOrPoints() {
        PetSettings original = SettingsFixtures.settings();
        PetData pet = pet();
        Progression.addXp(pet, Long.MAX_VALUE, original);
        long earned = pet.xp();
        int points = pet.points();
        YamlConfiguration config = new YamlConfiguration();
        config.set("growth.max-level", 10);
        PetSettings lower = SettingsFixtures.settings(config);
        assertEquals(0, Progression.addXp(pet, 50, lower));
        assertEquals(earned, pet.xp());
        assertEquals(points, pet.points());
    }

    @Test void upgradesConsumePointsOnlyOnSuccessfulChanges() {
        PetSettings settings = SettingsFixtures.settings();
        PetData pet = pet();
        assertFalse(Progression.upgrade(pet, "speed", settings));
        pet.setPoints(40);
        assertFalse(Progression.upgrade(pet, "unknown", settings));
        assertEquals(40, pet.points());
        for (int i = 0; i < settings.maxSpeedUpgrades(); i++) assertTrue(Progression.upgrade(pet, "speed", settings));
        assertFalse(Progression.upgrade(pet, "speed", settings));
        assertEquals(10, pet.points());
        assertTrue(Progression.upgrade(pet, "health", settings));
        assertTrue(Progression.upgrade(pet, "jump", settings));
        assertEquals(8, pet.points());
    }

    @Test void zeroOrNegativeRewardsDoNotChangeProgress() {
        PetSettings settings = SettingsFixtures.settings();
        PetData pet = pet();
        assertEquals(0, Progression.addXp(pet, -1, settings));
        assertEquals(0, Progression.addXp(pet, 0, settings));
        assertEquals(0, pet.xp());
    }

    private PetData pet() { return new PetData(UUID.randomUUID(), UUID.randomUUID(), EntityType.COW, "小牛"); }
}
