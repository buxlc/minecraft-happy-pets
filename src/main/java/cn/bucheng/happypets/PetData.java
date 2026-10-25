package cn.bucheng.happypets;

import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pet state owned by the server thread. Persistence copies it before scheduling writes. */
public final class PetData {
    public enum Mode { FOLLOW, STAY }

    private final UUID id;
    private final UUID owner;
    private final EntityType type;
    private String name;
    private long xp;
    private int bond;
    private int points;
    private int speed;
    private int health;
    private int jump;
    private Mode mode = Mode.FOLLOW;
    private long lastFeed;
    private long lastPlay;
    private double baseHealth = 20;
    private double baseSpeed = 0.2;
    private final Map<String, String> appearance = new HashMap<>();

    public PetData(UUID id, UUID owner, EntityType type, String name) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.type = Objects.requireNonNull(type);
        setName(name);
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public EntityType type() { return type; }
    public String name() { return name; }
    public long xp() { return xp; }
    public int bond() { return bond; }
    public int points() { return points; }
    public int speed() { return speed; }
    public int health() { return health; }
    public int jump() { return jump; }
    public Mode mode() { return mode; }
    public long lastFeed() { return lastFeed; }
    public long lastPlay() { return lastPlay; }
    public double baseHealth() { return baseHealth; }
    public double baseSpeed() { return baseSpeed; }
    public Map<String, String> appearance() { return appearance; }

    public void setName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.codePointCount(0, name.length()) > 32) {
            throw new IllegalArgumentException("Pet names must contain 1–32 characters");
        }
        this.name = name;
    }
    public void setXp(long value) { xp = Math.max(0, value); }
    public void setBond(int value) { bond = Math.max(0, value); }
    public void setPoints(int value) { points = Math.max(0, value); }
    public void setSpeed(int value) { speed = Math.max(0, value); }
    public void setHealth(int value) { health = Math.max(0, value); }
    public void setJump(int value) { jump = Math.max(0, value); }
    public void setMode(Mode value) { mode = Objects.requireNonNull(value); }
    public void setLastFeed(long value) { lastFeed = Math.max(0, value); }
    public void setLastPlay(long value) { lastPlay = Math.max(0, value); }
    public void setBaseHealth(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1024) throw new IllegalArgumentException("Invalid base health");
        baseHealth = value;
    }
    public void setBaseSpeed(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1024) throw new IllegalArgumentException("Invalid base speed");
        baseSpeed = value;
    }
}
