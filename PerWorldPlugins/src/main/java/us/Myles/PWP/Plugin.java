package us.Myles.PWP;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.hanging.HangingEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerEvent;
import org.bukkit.event.vehicle.VehicleEvent;
import org.bukkit.event.weather.WeatherEvent;
import org.bukkit.event.world.WorldEvent;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.mcstats.Metrics;
import us.Myles.PWP.TransparentListeners.PerWorldPluginLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class Plugin extends JavaPlugin {
    public static Plugin instance;
    String blockedMessage;
    @SuppressWarnings("deprecation")
    private List<Class<?>> exemptEvents = Arrays.asList(new Class<?>[]{AsyncPlayerPreLoginEvent.class,
            PlayerJoinEvent.class, PlayerKickEvent.class, PlayerLoginEvent.class, PlayerPreLoginEvent.class,
            PlayerQuitEvent.class});
    private boolean isExemptEnabled = true;
    private Map<String, Set<String>> pluginNameToWorlds = new HashMap<>();

    // Just for making string coloring less tedious.
    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void onLoad() {
        Plugin.instance = this;
        $("Registering event interceptor...");
        PerWorldPluginLoader pwpLoader = new PerWorldPluginLoader(Bukkit.getServer());
        injectExistingPlugins(pwpLoader);
        cleanJavaPluginLoaders(pwpLoader);
    }

    private void injectExistingPlugins(PerWorldPluginLoader pwpLoader) {
        for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p instanceof JavaPlugin) {
                JavaPlugin jp = (JavaPlugin) p;
                try {
                    Field f = JavaPlugin.class.getDeclaredField("loader");
                    f.setAccessible(true);
                    f.set(jp, pwpLoader);
                } catch (Exception e) {
                    Bukkit.getServer().getLogger().log(
                            Level.SEVERE,
                            "PerWorldPlugins failed injecting " + jp.getDescription().getFullName()
                                    + " with the new PluginLoader, contact the developers on BukkitDev!", e);
                }
            }
        }
    }

    private void cleanJavaPluginLoaders(PerWorldPluginLoader pwpLoader) {
        PluginManager spm = Bukkit.getPluginManager();
        try {
            Field field = spm.getClass().getDeclaredField("fileAssociations");
            field.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            @SuppressWarnings("unchecked")
            Map<Pattern, PluginLoader> map = (Map<Pattern, PluginLoader>) field.get(spm);
            for (Entry<Pattern, PluginLoader> entry : map.entrySet()) {
                if (entry.getValue() instanceof JavaPluginLoader) {
                    entry.setValue(pwpLoader);
                }
            }
            field.set(spm, map);
        } catch (Exception e) {
            Bukkit.getServer().getLogger().log(Level.SEVERE,
                    "PerWorldPlugins failed replacing the existing PluginLoader, contact the developers on BukkitDev!", e);
        }
    }

    public void onEnable() {
        getCommand("pwp").setExecutor(new PWPCommandExecutor());
        this.reload();
        setupMetrics();
        $("Enabled, attempting to inject CommandHandler...");
        try {
            Field f = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            if (f.getType().getPackage().getName().contains("Myles")) {
                Bukkit.getServer()
                        .getLogger()
                        .log(Level.SEVERE,
                                "Looks like the FakeSimpleCommandMap has already been injected, If this is a reload please ignore.");
                return;
            }

            f.setAccessible(true);

            //set not final
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(f, f.getModifiers() & ~Modifier.FINAL);

            SimpleCommandMap oldCommandMap = (SimpleCommandMap) f.get(Bukkit.getServer());
            f.set(Bukkit.getServer(), new FakeSimpleCommandMap(oldCommandMap));
        } catch (Exception e) {
            Bukkit.getServer().getLogger().log(Level.SEVERE,
                    "PerWorldPlugins failed replacing the existing PluginLoader, contact the developers on BukkitDev!", e);
        }
    }

    private void $(String s) {
        System.out.println("[PerWorldPlugins] " + s);
    }

    private void setupMetrics() {
        try {
            Metrics metrics = new Metrics(this);
            metrics.start();
        } catch (IOException e) {
            // Failed to submit the stats :-(
        }
    }

    private void loadConfig() {
        this.saveDefaultConfig();
        FileConfiguration c = getConfig();
        if (!c.isBoolean("exempt-login-events") || !c.contains("exempt-login-events")
                || !c.isSet("exempt-login-events")) {
            c.set("exempt-login-events", true);
        }
        isExemptEnabled = c.getBoolean("exempt-login-events", true);
        if (!c.isBoolean("check-for-updates") || !c.contains("check-for-updates") || !c.isSet("check-for-updates")) {
            c.set("check-for-updates", true);
        }
        if (!c.isString("blocked-msg") || !c.contains("blocked-msg") || !c.isSet("blocked-msg")) {
            c.set("blocked-msg", "&c[Error] This command cannot be performed in this world.");
        }
        blockedMessage = c.getString("blocked-msg", "&c[Error] This command cannot be performed in this world.");
        ConfigurationSection ul = c.getConfigurationSection("limit");
        if (ul == null) {
            ul = c.createSection("limit");
        }
        for (org.bukkit.plugin.Plugin plug : Bukkit.getPluginManager().getPlugins()) {
            if (plug.equals(this))
                continue;
            if (!ul.isList(plug.getDescription().getName())) {
                ul.set(plug.getDescription().getName(), new ArrayList<String>());
            }
        }
        saveConfig();
    }

    void reload() {
        this.reloadConfig();
        this.loadConfig();
        pluginNameToWorlds.clear();
        ConfigurationSection limit = getConfig().getConfigurationSection("limit");
        for (String pluginName : limit.getKeys(false)) {
            if (limit.isList(pluginName)) {
                List<String> worldNames = limit.getStringList(pluginName);
                if (worldNames.size() == 0)
                    continue;
                pluginNameToWorlds.put(pluginName, new HashSet<>());
                worldNames.stream().map(String::toLowerCase).forEach(pluginNameToWorlds.get(pluginName)::add);
            }
        }
    }

    boolean checkWorld(org.bukkit.plugin.Plugin plugin, World w) {
        if (plugin == null) return true;
        if (w == null) return true;
        String pluginName = plugin.getDescription().getName();
        Set<String> restrictedWorlds = pluginNameToWorlds.get(pluginName);
        if (restrictedWorlds == null)
            return true;
        return restrictedWorlds.contains(w.getName().toLowerCase());
    }

    public boolean checkWorld(org.bukkit.plugin.Plugin plugin, Event e) {
        if ((e instanceof PlayerEvent)) {
            PlayerEvent e1 = (PlayerEvent) e;
            if ((exemptEvents.contains(e.getClass())) && (instance.isExemptEnabled())) {
                return true;
            }
            return checkWorld(plugin, e1.getPlayer().getWorld());
        }
        if ((e instanceof BlockEvent)) {
            BlockEvent e1 = (BlockEvent) e;
            return checkWorld(plugin, e1.getBlock().getWorld());
        }
        if ((e instanceof InventoryEvent)) {
            InventoryEvent e1 = (InventoryEvent) e;
            return checkWorld(plugin, e1.getView().getPlayer().getWorld());
        }
        if ((e instanceof EntityEvent)) {
            EntityEvent e1 = (EntityEvent) e;
            return checkWorld(plugin, e1.getEntity().getWorld());
        }
        if ((e instanceof HangingEvent)) {
            HangingEvent e1 = (HangingEvent) e;
            return checkWorld(plugin, e1.getEntity().getWorld());
        }
        if ((e instanceof VehicleEvent)) {
            VehicleEvent e1 = (VehicleEvent) e;
            return checkWorld(plugin, e1.getVehicle().getWorld());
        }
        if ((e instanceof WeatherEvent)) {
            WeatherEvent e1 = (WeatherEvent) e;
            return checkWorld(plugin, e1.getWorld());
        }
        if ((e instanceof WorldEvent)) {
            WorldEvent e1 = (WorldEvent) e;
            return checkWorld(plugin, e1.getWorld());
        }
        if ((e instanceof ServerEvent)) {
            return true;
        }
        return true;
    }

    private boolean isExemptEnabled() {
        return this.isExemptEnabled;
    }
}
