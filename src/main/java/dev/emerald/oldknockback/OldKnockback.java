package dev.emerald.oldknockback;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class OldKnockback extends JavaPlugin {

    private static OldKnockback instance;
    private KnockbackListener knockbackListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        knockbackListener = new KnockbackListener(this);
        getServer().getPluginManager().registerEvents(knockbackListener, this);

        getLogger().info("OldKnockback enabled! Classic knockback restored for 1.21.11.");
    }

    @Override
    public void onDisable() {
        getLogger().info("OldKnockback disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("oldkb")) {
            if (!sender.hasPermission("oldknockback.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                sender.sendMessage("§aOldKnockback config reloaded!");
            } else {
                sender.sendMessage("§6OldKnockback §7v" + getDescription().getVersion());
                sender.sendMessage("§7Usage: §f/oldkb reload");
            }
            return true;
        }
        return false;
    }

    public static OldKnockback getInstance() {
        return instance;
    }
}
