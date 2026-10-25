package cn.bucheng.happypets;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Language files are loaded completely before replacing the active dictionary. */
public final class Messages {
    private final JavaPlugin plugin;
    private final YamlConfiguration defaults;
    private YamlConfiguration current;

    public Messages(JavaPlugin plugin, String language) {
        this.plugin = Objects.requireNonNull(plugin);
        defaults = resource("lang/zh_CN.yml");
        for (String builtIn : List.of("zh_CN", "en_US")) {
            Path path = plugin.getDataFolder().toPath().resolve("lang").resolve(builtIn + ".yml");
            if (!Files.exists(path)) plugin.saveResource("lang/" + builtIn + ".yml", false);
        }
        reload(language);
    }

    /** Rejects path traversal, missing files, invalid YAML, and non-text dictionary values. */
    public void reload(String language) {
        if (language == null || !language.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Invalid language file name");
        }
        Path path = plugin.getDataFolder().toPath().resolve("lang").resolve(language + ".yml");
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(path.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalArgumentException("Cannot load language " + language + ": " + e.getMessage(), e);
        }
        for (String key : candidate.getKeys(true)) {
            if (candidate.isConfigurationSection(key)) continue;
            Object value = candidate.get(key);
            if (value instanceof String) continue;
            if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) continue;
            throw new IllegalArgumentException("Language key '" + key + "' must be text or a list of text");
        }
        current = candidate;
    }

    public String text(String key, Object... replacementPairs) {
        Object value = current.get(key);
        if (!(value instanceof String)) value = defaults.get(key);
        return format(value instanceof String s ? s : key, replacementPairs);
    }

    public void send(CommandSender target, String key, Object... replacementPairs) {
        target.sendMessage(text("prefix") + text(key, replacementPairs));
    }

    public List<String> list(String key, Object... replacementPairs) {
        Object value = current.get(key);
        if (!(value instanceof List<?>) && !(value instanceof String)) value = defaults.get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> lines) {
            for (Object line : lines) result.add(format(String.valueOf(line), replacementPairs));
        } else result.add(format(value instanceof String s ? s : key, replacementPairs));
        return result;
    }

    public static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static String format(String value, Object... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("Replacement names and values must be paired");
        String result = value;
        for (int i = 0; i < pairs.length; i += 2) result = result.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        return color(result);
    }

    private YamlConfiguration resource(String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) throw new IllegalStateException("Missing bundled language " + name);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot read bundled language " + name, e);
        }
    }
}
