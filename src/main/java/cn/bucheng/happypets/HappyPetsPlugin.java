package cn.bucheng.happypets;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Spigot entry point. All gameplay state belongs to the server thread. */
public final class HappyPetsPlugin extends JavaPlugin {
    private PetSettings settings;
    private Messages messages;
    private PetStore store;
    private PetService pets;
    private PetMenus menus;
    private MiniGames games;
    private boolean ready;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings = PetSettings.from(readConfig());
            messages = new Messages(this, settings.language());
            store = new PetStore(this);
            List<PetData> loaded = store.load();
            pets = new PetService(this, store, loaded);
            games = new MiniGames(this);
            menus = new PetMenus(this);
            getServer().getPluginManager().registerEvents(new PetListener(this), this);
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(games, this);
            PetCommand executor = new PetCommand(this);
            Objects.requireNonNull(getCommand("pets")).setExecutor(executor);
            Objects.requireNonNull(getCommand("pets")).setTabCompleter(executor);
            pets.start();
            ready = true;
            getLogger().info("HappyPets enabled: " + loaded.size() + " pets, Spigot 1.21.4 / Java 21.");
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "HappyPets could not start. Fix the configuration/data before restarting; existing data is preserved.", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (menus != null) cleanup("menus", menus::closeAll);
        if (games != null) cleanup("games", games::closeAll);
        if (pets != null) cleanup("entities", pets::shutdown);
        if (store != null) cleanup("storage", () -> store.close(pets == null ? List.of() : pets.all()));
        ready = false;
    }

    private void cleanup(String component, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to close " + component + "; inspect the server log and data files", error);
        }
    }

    public PetSettings settings() { return settings; }
    public Messages messages() { return messages; }
    public PetService pets() { return pets; }
    public PetMenus menus() { return menus; }
    public MiniGames games() { return games; }
    public NamespacedKey menuActionKey() { return new NamespacedKey(this, "menu_action"); }
    public boolean ready() { return ready; }

    /** Validate both files before switching live gameplay to a new configuration. */
    public boolean reloadPlugin() {
        try {
            YamlConfiguration candidate = readConfig();
            PetSettings next = PetSettings.from(candidate);
            messages.reload(next.language());
            commit(next);
            return true;
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.WARNING, "Reload rejected: " + error.getMessage(), error);
            return false;
        }
    }

    /** Admin GUI changes are validated and written atomically before applying. */
    public boolean updateSetting(String path, Object value) {
        try {
            YamlConfiguration candidate = readConfig();
            candidate.set(path, value);
            PetSettings next = PetSettings.from(candidate);
            File temp = new File(getDataFolder(), "config.yml.tmp");
            candidate.save(temp);
            try {
                Files.move(temp.toPath(), new File(getDataFolder(), "config.yml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), new File(getDataFolder(), "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            commit(next);
            return true;
        } catch (Exception error) {
            getLogger().warning("Setting rejected (" + path + "): " + error.getMessage());
            return false;
        }
    }

    private YamlConfiguration readConfig() throws IOException, InvalidConfigurationException {
        YamlConfiguration result = new YamlConfiguration();
        result.load(new File(getDataFolder(), "config.yml"));
        return result;
    }

    private void commit(PetSettings next) {
        menus.closeAll();
        games.closeAll();
        settings = next;
        reloadConfig();
        pets.reconfigure();
    }
}
