package cn.bucheng.happypets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PetSettingsTest {
    @Test void shippedConfigurationLoadsWithIntendedDefaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            yaml.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        PetSettings settings = SettingsFixtures.settings(yaml);
        assertEquals(5, settings.maxPets());
        assertEquals(10, settings.adultLevel());
        assertTrue(settings.flyingTypes().contains(EntityType.BEE));
        assertTrue(settings.aquaticTypes().contains(EntityType.COD));
        assertThrows(UnsupportedOperationException.class, () -> settings.flyingTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> settings.xpThresholds().clear());
    }

    @Test void badIntervalsAndNonFiniteSpeedsAreRejected() {
        for (Object value : List.of(0, -1, 1.5, "ten")) assertBad("performance.follow-ticks", value);
        assertBad("riding.ground-speed", Double.NaN);
        assertBad("riding.fly-speed", Double.POSITIVE_INFINITY);
        assertBad("growth.xp-factor", 0.9);
        assertBad("performance.autosave-seconds", 4);
    }

    @Test void relatedLimitsAndLanguagePathsAreValidatedTogether() {
        assertBad("growth.max-level", 5);
        assertBad("follow.distance", 30);
        assertBad("follow.teleport-distance", 3);
        assertBad("language", "../en_US");
        assertBad("language", "F:/tmp/messages");
        assertBad("limits.max-pets", "5");
        assertBad("games.enabled", "yes");
    }

    @Test void unknownOrNonLivingTypesAndInvalidMaterialsAreRejected() {
        assertBad("riding.flying-types", List.of("NEW_UNKNOWN_MOB"));
        assertBad("riding.flying-types", List.of("ARMOR_STAND"));
        assertBad("capture.excluded-types", List.of("ARROW"));
        assertBad("feed.material", "HONEY_COOKIE");
    }

    private void assertBad(String path, Object value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path, value);
        assertThrows(IllegalArgumentException.class, () -> SettingsFixtures.settings(config), path + "=" + value);
    }
}
