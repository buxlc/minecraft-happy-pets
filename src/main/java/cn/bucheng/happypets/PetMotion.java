package cn.bucheng.happypets;

import org.bukkit.util.Vector;

/** Shared bounded movement calculations; speed is measured in blocks per tick. */
final class PetMotion {
    private PetMotion() { }

    static Vector steering(float yaw, boolean forward, boolean backward, boolean left, boolean right, double speed) {
        double f = (forward ? 1 : 0) - (backward ? 1 : 0);
        double s = (right ? 1 : 0) - (left ? 1 : 0);
        double radians = Math.toRadians(yaw);
        Vector direction = new Vector(-Math.sin(radians) * f - Math.cos(radians) * s, 0,
                Math.cos(radians) * f - Math.sin(radians) * s);
        if (direction.lengthSquared() > 0) direction.normalize().multiply(speed);
        return direction;
    }

    static double speed(PetData data, PetSettings settings, boolean flying, boolean aquatic) {
        double base = flying ? settings.flySpeed() : aquatic ? settings.swimSpeed() : settings.groundSpeed();
        return Math.min(settings.maxRideSpeed(), base * (1 + data.speed() * settings.speedPerUpgrade()));
    }
}
