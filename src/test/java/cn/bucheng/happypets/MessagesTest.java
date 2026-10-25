package cn.bucheng.happypets;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagesTest {
    @TempDir Path folder;

    @Test void selectedLanguageAndPlaceholdersLoadFromSeparateFiles() throws Exception {
        Messages messages = new Messages(plugin(), "en_US");
        assertTrue(messages.text("pet.summoned", "name", "Moo").contains("Moo"));
        assertTrue(messages.text("menu.pet.rename").contains("Name"));
        assertEquals(3, messages.list("food.lore").size());
        messages.reload("zh_CN");
        assertTrue(messages.text("menu.pet.rename").contains("起名"));
        assertTrue(Files.exists(folder.resolve("lang/en_US.yml")));
    }

    @Test void missingKeysUseBundledChineseAndInvalidReloadPreservesCurrentDictionary() throws Exception {
        Messages messages = new Messages(plugin(), "en_US");
        Files.writeString(folder.resolve("lang/custom.yml"), "prefix: '&aCustom '\nmenu:\n  pet:\n    rename: '&bMy rename'\n");
        messages.reload("custom");
        assertTrue(messages.text("menu.pet.rename").contains("My rename"));
        assertTrue(messages.text("pet.recalled").contains("休息"));
        Files.writeString(folder.resolve("lang/bad.yml"), "prefix: 123\n");
        assertThrows(IllegalArgumentException.class, () -> messages.reload("bad"));
        assertTrue(messages.text("menu.pet.rename").contains("My rename"));
        assertThrows(IllegalArgumentException.class, () -> messages.reload("../en_US"));
        assertThrows(IllegalArgumentException.class, () -> messages.reload("missing"));
        assertTrue(messages.text("menu.pet.rename").contains("My rename"));
    }

    @Test void bothBundledLanguagesContainTheSameLeafKeysAndGuiDescriptions() throws Exception {
        YamlConfiguration chinese = bundled("zh_CN");
        YamlConfiguration english = bundled("en_US");
        var keys = chinese.getKeys(true).stream().filter(k -> !chinese.isConfigurationSection(k)).collect(java.util.stream.Collectors.toSet());
        var englishKeys = english.getKeys(true).stream().filter(k -> !english.isConfigurationSection(k)).collect(java.util.stream.Collectors.toSet());
        assertEquals(keys, englishKeys);
        assertTrue(chinese.getString("menu.pet.info").contains("\n"));
        assertTrue(english.getString("menu.admin.setting-info").contains("{value}"));
        for (String key : keys) {
            Object value = chinese.get(key);
            assertTrue(value instanceof String || value instanceof List<?>, key);
        }
    }

    @Test void unknownKeysRemainVisibleToAdministratorsAndPairsMustBeComplete() throws Exception {
        Messages messages = new Messages(plugin(), "en_US");
        assertEquals("unknown.key", messages.text("unknown.key"));
        assertEquals(List.of("unknown.key"), messages.list("unknown.key"));
        assertThrows(IllegalArgumentException.class, () -> messages.text("pet.summoned", "name"));
    }

    private JavaPlugin plugin() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getResource(anyString())).thenAnswer(invocation -> getClass().getClassLoader().getResourceAsStream(invocation.getArgument(0)));
        doAnswer(invocation -> {
            String resource = invocation.getArgument(0);
            Path target = folder.resolve(resource);
            Files.createDirectories(target.getParent());
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream);
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        }).when(plugin).saveResource(anyString(), eq(false));
        return plugin;
    }

    private YamlConfiguration bundled(String language) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var stream = getClass().getResourceAsStream("/lang/" + language + ".yml")) {
            assertNotNull(stream);
            yaml.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        return yaml;
    }
}
