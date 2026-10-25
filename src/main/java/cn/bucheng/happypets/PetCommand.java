package cn.bucheng.happypets;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

final class PetCommand implements CommandExecutor, TabCompleter {
    private final HappyPetsPlugin plugin;
    PetCommand(HappyPetsPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (Set.of("admin", "adminpets", "inspect", "edit", "give", "reload", "save").contains(sub)) {
            if (!sender.hasPermission("happypets.admin")) return tell(sender, "error.no-permission");
            switch (sub) {
                case "adminpets" -> {
                    if (!(sender instanceof Player player)) return tell(sender, "error.player-only");
                    plugin.menus().openAllPets(player, 0);
                    return true;
                }
                case "inspect", "edit" -> {
                    if (!(sender instanceof Player player)) return tell(sender, "error.player-only");
                    if (args.length < 2 || (sub.equals("edit") && args.length != 4)) {
                        for (String line : plugin.messages().list("command.manage-help")) sender.sendMessage(line);
                        return true;
                    }
                    UUID id;
                    try { id = UUID.fromString(args[1]); }
                    catch (IllegalArgumentException invalidId) { return tell(sender, "error.not-found"); }
                    if (plugin.pets().find(id) == null) return tell(sender, "error.not-found");
                    if (sub.equals("inspect")) plugin.menus().openPet(player, id);
                    else plugin.pets().adminSet(player, id, args[2].toLowerCase(Locale.ROOT), args[3]);
                    return true;
                }
                case "reload" -> { return tell(sender, plugin.reloadPlugin() ? "command.reloaded" : "command.reload-failed"); }
                case "save" -> { plugin.pets().saveNow(); return tell(sender, "command.saved"); }
                case "give" -> {
                    Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                    if (target == null) return tell(sender, "command.player-not-found");
                    int amount = 16;
                    if (args.length >= 3) {
                        try { amount = Integer.parseInt(args[2]); }
                        catch (NumberFormatException invalid) { return tell(sender, "command.invalid-amount"); }
                    }
                    if (amount < 1 || amount > 2304) return tell(sender, "command.invalid-amount");
                    plugin.pets().giveFood(target, amount);
                    plugin.messages().send(sender, "command.given", "player", target.getName(), "amount", amount);
                    return true;
                }
                default -> {
                    if (!(sender instanceof Player player)) return tell(sender, "error.player-only");
                    plugin.menus().openAdmin(player, 0);
                    return true;
                }
            }
        }
        if (!(sender instanceof Player player)) return tell(sender, "error.player-only");
        if (!player.hasPermission("happypets.use")) return tell(sender, "error.no-permission");
        if (sub.equals("recall")) { plugin.pets().recall(player); return true; }
        if (!plugin.pets().usable(player)) return true;
        switch (sub) {
            case "list", "menu" -> plugin.menus().openList(player, 0);
            case "help" -> {
                for (String line : plugin.messages().list("command.help")) sender.sendMessage(line);
                if (sender.hasPermission("happypets.admin")) for (String line : plugin.messages().list("command.admin-help")) sender.sendMessage(line);
            }
            case "summon", "ride", "rename" -> {
                PetData data = select(player, args.length >= 2 ? args[1] : null);
                if (data == null) return tell(sender, "error.not-found");
                if (sub.equals("summon")) plugin.pets().summon(player, data.id());
                else if (sub.equals("ride")) plugin.pets().ride(player, data.id());
                else if (args.length >= 3) plugin.pets().rename(player, data.id(), String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                else plugin.menus().requestRename(player, data.id());
            }
            default -> { for (String line : plugin.messages().list("command.help")) sender.sendMessage(line); }
        }
        return true;
    }

    private PetData select(Player player, String value) {
        if (value == null) {
            PetData active = plugin.pets().active(player.getUniqueId());
            return active != null ? active : plugin.pets().owned(player.getUniqueId()).stream().findFirst().orElse(null);
        }
        try {
            int index = Integer.parseInt(value);
            List<PetData> list = plugin.pets().owned(player.getUniqueId());
            return index > 0 && index <= list.size() ? list.get(index - 1) : null;
        } catch (NumberFormatException invalid) {
            try {
                PetData data = plugin.pets().find(UUID.fromString(value));
                return data != null && data.owner().equals(player.getUniqueId()) ? data : null;
            } catch (IllegalArgumentException invalidUuid) { return null; }
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("happypets.use")) {
                values.add("recall");
                if (plugin.settings().enabled() && sender instanceof Player player && !plugin.settings().disabledWorlds().contains(player.getWorld().getName())) {
                    values.addAll(List.of("list", "summon", "rename", "help"));
                    if (plugin.settings().ridingEnabled()) values.add("ride");
                }
            }
            if (sender.hasPermission("happypets.admin")) values.addAll(List.of("admin", "adminpets", "inspect", "edit", "give", "reload", "save"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("happypets.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) if (!(sender instanceof Player viewer) || viewer.canSee(player)) values.add(player.getName());
        } else if (args.length == 2 && sender.hasPermission("happypets.admin")
                && Set.of("inspect", "edit").contains(args[0].toLowerCase(Locale.ROOT))) {
            String idPrefix = args[1].toLowerCase(Locale.ROOT);
            plugin.pets().all().stream().map(data -> data.id().toString()).filter(id -> id.startsWith(idPrefix)).limit(100).forEach(values::add);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("edit") && sender.hasPermission("happypets.admin")) {
            values.addAll(PetService.ADMIN_FIELDS);
        } else if (args.length == 2 && sender instanceof Player player && sender.hasPermission("happypets.use")
                && Set.of("summon", "ride", "rename").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (int i = 1; i <= plugin.pets().owned(player.getUniqueId()).size(); i++) values.add(Integer.toString(i));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    private boolean tell(CommandSender sender, String key) { plugin.messages().send(sender, key); return true; }
}
