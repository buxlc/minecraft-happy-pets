package cn.bucheng.happypets;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

final class SettingsFixtures {
    private SettingsFixtures() { }

    static PetSettings settings() { return settings(new YamlConfiguration()); }

    static PetSettings settings(YamlConfiguration config) {
        // Item availability comes from a running server's registry in 1.21.4.
        Material cookie = mock(Material.class);
        when(cookie.isItem()).thenReturn(true);
        try (MockedStatic<Material> material = mockStatic(Material.class, CALLS_REAL_METHODS)) {
            material.when(() -> Material.valueOf("COOKIE")).thenReturn(cookie);
            return PetSettings.from(config);
        }
    }
}
