package cn.bucheng.happypets;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Coalesces immutable snapshots and atomically writes them through one ordered writer. */
public final class PetStore {
    private final Path file;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HappyPets-save");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private List<Snapshot> pending;
    private boolean scheduled;
    private boolean loaded;
    private boolean closed;
    private volatile IOException lastFailure;

    public PetStore(JavaPlugin plugin) {
        this(plugin.getDataFolder().toPath(), plugin.getLogger());
    }

    PetStore(Path folder, Logger logger) {
        file = folder.resolve("pets.yml");
        this.logger = logger;
    }

    /** Refuses invalid data rather than silently replacing a player's collection. */
    public List<PetData> load() {
        synchronized (lock) {
            if (loaded || closed) throw new IllegalStateException("PetStore has already been opened or closed");
        }
        try {
            List<PetData> pets = new ArrayList<>();
            if (Files.exists(file)) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                if (whole(yaml.get("schema-version"), "schema-version", 1, 1) != 1) throw new IllegalArgumentException("Unsupported schema-version");
                Object records = yaml.get("pets");
                if (!(records instanceof List<?> list)) throw new IllegalArgumentException("pets must be a list");
                Set<UUID> seen = new HashSet<>();
                for (Object raw : list) {
                    if (!(raw instanceof Map<?, ?> record)) throw new IllegalArgumentException("Pet record must be a mapping");
                    PetData pet = decode(record);
                    if (!seen.add(pet.id())) throw new IllegalArgumentException("Duplicate pet ID " + pet.id());
                    pets.add(pet);
                }
            } else if (Files.exists(file.resolveSibling("pets.yml.bak"))) {
                throw new IllegalArgumentException("pets.yml is missing while pets.yml.bak exists; restore the backup first");
            }
            synchronized (lock) { loaded = true; }
            return pets;
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load " + file + "; existing data has not been overwritten", e);
        }
    }

    /** Must be called by the server thread; only immutable values reach the writer. */
    public void saveAsync(Collection<PetData> pets) {
        List<Snapshot> snapshot = snapshot(pets);
        synchronized (lock) {
            requireOpen();
            pending = snapshot;
            schedule();
        }
    }

    /** Stops accepting saves, drains the final snapshot, and reports any final write failure. */
    public void close(Collection<PetData> pets) {
        List<Snapshot> snapshot = snapshot(pets);
        synchronized (lock) {
            if (closed) return;
            if (!loaded) {
                closed = true;
                writer.shutdown();
                return;
            }
            pending = snapshot;
            closed = true;
            schedule();
            writer.shutdown();
        }
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                throw new IllegalStateException("Timed out writing final pet data; inspect pets.yml and server log");
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing final pet data", e);
        }
        if (lastFailure != null) throw new IllegalStateException("Final pet save failed", lastFailure);
    }

    private void requireOpen() {
        if (!loaded) throw new IllegalStateException("Refusing to write pet data before a successful load");
        if (closed) throw new IllegalStateException("PetStore is closed");
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            writer.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            List<Snapshot> next;
            synchronized (lock) {
                next = pending;
                pending = null;
                if (next == null) { scheduled = false; return; }
            }
            try {
                write(next);
                lastFailure = null;
            } catch (IOException e) {
                lastFailure = e;
                logger.log(Level.SEVERE, "Cannot save pets.yml; previous data is retained. Check disk space and file access.", e);
            } catch (RuntimeException e) {
                lastFailure = new IOException("Cannot encode pet snapshot", e);
                logger.log(Level.SEVERE, "Cannot encode pet snapshot; previous data is retained.", e);
            }
        }
    }

    private void write(List<Snapshot> pets) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        List<Map<String, Object>> records = new ArrayList<>(pets.size());
        for (Snapshot pet : pets) records.add(pet.encode());
        yaml.set("pets", records);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "pets-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            if (Files.exists(file)) Files.copy(file, file.resolveSibling("pets.yml.bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Snapshot> snapshot(Collection<PetData> pets) {
        List<Snapshot> result = new ArrayList<>(pets.size());
        for (PetData pet : pets) result.add(new Snapshot(pet.id().toString(), pet.owner().toString(), pet.type().name(),
                pet.name(), pet.xp(), pet.bond(), pet.points(), pet.speed(), pet.health(), pet.jump(), pet.mode().name(),
                pet.lastFeed(), pet.lastPlay(), pet.baseHealth(), pet.baseSpeed(), Map.copyOf(pet.appearance())));
        return List.copyOf(result);
    }

    private static PetData decode(Map<?, ?> record) {
        EntityType type = EntityType.valueOf(text(record.get("type"), "type"));
        if (!type.isAlive() || !type.isSpawnable() || type == EntityType.ARMOR_STAND) throw new IllegalArgumentException("Invalid pet type");
        PetData pet = new PetData(UUID.fromString(text(record.get("id"), "id")), UUID.fromString(text(record.get("owner"), "owner")),
                type, text(record.get("name"), "name"));
        pet.setXp(whole(record.get("xp"), "xp", 0, Long.MAX_VALUE));
        pet.setBond((int) whole(record.get("bond"), "bond", 0, Integer.MAX_VALUE));
        pet.setPoints((int) whole(record.get("points"), "points", 0, Integer.MAX_VALUE));
        pet.setSpeed((int) whole(record.get("speed"), "speed", 0, 1000));
        pet.setHealth((int) whole(record.get("health"), "health", 0, 1000));
        pet.setJump((int) whole(record.get("jump"), "jump", 0, 1000));
        pet.setMode(PetData.Mode.valueOf(text(record.get("mode"), "mode")));
        pet.setLastFeed(whole(record.get("last-feed"), "last-feed", 0, Long.MAX_VALUE));
        pet.setLastPlay(whole(record.get("last-play"), "last-play", 0, Long.MAX_VALUE));
        pet.setBaseHealth(decimal(record.get("base-health"), "base-health"));
        pet.setBaseSpeed(decimal(record.get("base-speed"), "base-speed"));
        Object appearance = record.get("appearance");
        if (!(appearance instanceof Map<?, ?> properties)) throw new IllegalArgumentException("appearance must be a map");
        if (properties.size() > 64) throw new IllegalArgumentException("appearance has too many fields");
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            String key = text(property.getKey(), "appearance key");
            if (!(property.getValue() instanceof String value)) throw new IllegalArgumentException("appearance value must be text");
            int maxLength = key.equals("items") ? 16 * 1024 * 1024 : 256;
            if (key.length() > 64 || value.length() > maxLength) throw new IllegalArgumentException("appearance value is too long");
            pet.appearance().put(key, value);
        }
        return pet;
    }

    private static String text(Object raw, String key) {
        if (!(raw instanceof String value) || value.isBlank()) throw new IllegalArgumentException(key + " must be a non-empty string");
        return value;
    }
    private static long whole(Object raw, String key, long min, long max) {
        if (!(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)) throw new IllegalArgumentException(key + " must be a whole number");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw new IllegalArgumentException(key + " is outside its valid range");
        return value;
    }
    private static double decimal(Object raw, String key) {
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException(key + " must be a finite number");
        return number.doubleValue();
    }

    private record Snapshot(String id, String owner, String type, String name, long xp, int bond, int points,
                            int speed, int health, int jump, String mode, long lastFeed, long lastPlay,
                            double baseHealth, double baseSpeed, Map<String, String> appearance) {
        Map<String, Object> encode() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", id); record.put("owner", owner); record.put("type", type); record.put("name", name);
            record.put("xp", xp); record.put("bond", bond); record.put("points", points);
            record.put("speed", speed); record.put("health", health); record.put("jump", jump); record.put("mode", mode);
            record.put("last-feed", lastFeed); record.put("last-play", lastPlay);
            record.put("base-health", baseHealth); record.put("base-speed", baseSpeed); record.put("appearance", appearance);
            return record;
        }
    }
}
