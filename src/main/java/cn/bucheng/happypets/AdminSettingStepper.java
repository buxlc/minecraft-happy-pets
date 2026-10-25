package cn.bucheng.happypets;

import org.bukkit.event.inventory.ClickType;

/** Applies bounded left/right-click steps to numeric administrator settings. */
final class AdminSettingStepper {
    private AdminSettingStepper() { }

    static Object next(String path, Object current, ClickType click, PetSettings settings) {
        if (current instanceof Boolean enabled) return !enabled;
        if (!(current instanceof Number number)) return current;
        boolean decrement = click.isRightClick();
        double delta = number instanceof Double || number instanceof Float
            ? (click.isShiftClick() ? 0.1 : 0.01)
            : (click.isShiftClick() ? 10 : 1);
        double[] bounds = bounds(path, settings);
        double value = Math.max(bounds[0], Math.min(bounds[1], number.doubleValue() + (decrement ? -delta : delta)));
        if (number instanceof Double || number instanceof Float) return value;
        return (int) Math.round(value);
    }

    private static double[] bounds(String path, PetSettings settings) {
        return switch (path) {
            case "limits.max-pets" -> new double[]{1, 45};
            case "growth.max-level" -> new double[]{2, 1000};
            case "growth.adult-level" -> new double[]{1, settings.maxLevel()};
            case "growth.xp-base" -> new double[]{1, 1_000_000_000};
            case "growth.xp-factor" -> new double[]{1, 3};
            case "growth.points-per-level" -> new double[]{0, 100};
            case "growth.max-bond" -> new double[]{1, 1_000_000};
            case "rewards.feed.xp", "rewards.play.xp", "rewards.travel.xp" -> new double[]{0, 1_000_000_000};
            case "rewards.feed.bond", "rewards.play.bond" -> new double[]{0, 10_000};
            case "rewards.feed.cooldown-seconds", "rewards.play.cooldown-seconds" -> new double[]{1, 86_400};
            case "rewards.travel.distance" -> new double[]{1, 10_000};
            case "rewards.travel.interval-seconds" -> new double[]{1, 3600};
            case "performance.autosave-seconds" -> new double[]{5, 3600};
            case "performance.follow-ticks" -> new double[]{2, 100};
            case "follow.distance" -> new double[]{1, Math.min(32, settings.teleportDistance() - 1)};
            case "follow.teleport-distance" -> new double[]{settings.followDistance() + 1, 128};
            case "upgrades.speed.max", "upgrades.health.max" -> new double[]{0, 1000};
            case "upgrades.jump.max" -> new double[]{0, 1000};
            case "upgrades.speed.per-level" -> new double[]{0, 2};
            case "upgrades.health.per-level" -> new double[]{0, 100};
            case "upgrades.jump.per-level" -> new double[]{0, 1};
            case "riding.ground-speed", "riding.fly-speed", "riding.swim-speed" -> new double[]{0.01, 1.5};
            case "riding.max-speed" -> new double[]{0.01, 2};
            case "riding.flying-ceiling" -> new double[]{-64, 2048};
            default -> new double[]{numberMinimum(path), numberMaximum(path)};
        };
    }

    private static double numberMinimum(String path) {
        if (path.endsWith(".max")) return 0;
        return 0;
    }

    private static double numberMaximum(String path) {
        if (path.endsWith(".max")) return 1000;
        return 1_000_000_000;
    }
}
