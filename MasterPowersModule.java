package com.hypesmp.masterpowers;

/*
 * MasterPowers, merged into ProjectKorra.
 *
 * This class is a 1:1 port of the original standalone MasterPowers.java plugin.
 * EVERY original feature, power, command and config option from MasterPowers is
 * kept exactly as it was ("bina ek bhi cheej kaate"). The only structural change
 * is that this no longer extends JavaPlugin itself — ProjectKorra can only have
 * one JavaPlugin main class per plugin.yml, so MasterPowers now runs as a module
 * that is booted up by ProjectKorra.onEnable() / shut down by ProjectKorra.onDisable().
 * It uses its own separate config (masterpowers.yml) and its own data file
 * (masterpowers/players.yml) so it never touches ProjectKorra's own config.yml.
 *
 * Added on top of the original (new addons, nothing removed):
 *  - Two new powers: "heal" and "blink"
 *  - /power stats <player>  (admin) - shows playtime + unlocked powers
 *  - A live boss bar cooldown indicator when a power is on cooldown
 */

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MasterPowersModule implements Listener, CommandExecutor, TabCompleter {

    private static final List<String> ALL_POWERS = Arrays.asList(
            "lightning", "fire", "water", "earth", "speed", "jump", "shield",
            "dash", "magnet", "invisibility", "heal", "blink");

    private final JavaPlugin plugin;

    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playtime = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> powers = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> cooldownBars = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration data;
    private File configFile;
    private YamlConfiguration config;

    public MasterPowersModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        loadConfig();

        File moduleFolder = new File(plugin.getDataFolder(), "masterpowers");
        if (!moduleFolder.exists()) moduleFolder.mkdirs();
        dataFile = new File(moduleFolder, "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadData();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("power") != null) {
            plugin.getCommand("power").setExecutor(this);
            plugin.getCommand("power").setTabCompleter(this);
        } else {
            plugin.getLogger().warning("[MasterPowers] 'power' command is not declared in plugin.yml - add it to use /power.");
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickPlaytime, 20L, 20L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickCooldownBars, 5L, 5L);
        plugin.getLogger().info("[MasterPowers] module enabled.");
    }

    public void disable() {
        saveData();
        for (BossBar bar : cooldownBars.values()) bar.removeAll();
        cooldownBars.clear();
    }

    // ---- config (masterpowers.yml, separate from ProjectKorra's config.yml) ----
    private void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "masterpowers.yml");
        if (!configFile.exists()) {
            try (InputStream in = plugin.getResource("masterpowers.yml")) {
                if (in != null) {
                    plugin.getDataFolder().mkdirs();
                    Files.copy(in, configFile.toPath());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[MasterPowers] Could not save default masterpowers.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    private void loadData() {
        if (data.isConfigurationSection("players")) {
            for (String id : data.getConfigurationSection("players").getKeys(false)) {
                UUID u;
                try { u = UUID.fromString(id); } catch (Exception e) { continue; }
                playtime.put(u, data.getLong("players." + id + ".playtime", 0));
                powers.put(u, new HashSet<>(data.getStringList("players." + id + ".powers")));
            }
        }
    }

    private void saveData() {
        for (Player p : Bukkit.getOnlinePlayers()) flush(p);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[MasterPowers] Could not save players.yml: " + e.getMessage());
        }
    }

    private void flush(Player p) {
        UUID u = p.getUniqueId();
        long total = playtime.getOrDefault(u, 0L) + currentSession(u);
        playtime.put(u, total);
        joinTimes.remove(u);
        String path = "players." + u;
        data.set(path + ".playtime", total);
        data.set(path + ".powers", new ArrayList<>(powers.getOrDefault(u, new HashSet<>())));
    }

    private long currentSession(UUID u) {
        long t = joinTimes.getOrDefault(u, 0L);
        return t == 0 ? 0 : (System.currentTimeMillis() - t) / 1000;
    }

    private void tickPlaytime() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            joinTimes.putIfAbsent(p.getUniqueId(), System.currentTimeMillis());
        }
        if (System.currentTimeMillis() % 30000 < 1000) saveData();
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        joinTimes.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        flush(e.getPlayer());
        try {
            data.save(dataFile);
        } catch (IOException ignored) {
        }
        BossBar bar = cooldownBars.remove(e.getPlayer().getUniqueId());
        if (bar != null) bar.removeAll();
    }

    private boolean unlocked(Player p) {
        return p.isOp() || getPlaytime(p) >= config.getLong("unlock-playtime-seconds", 86400);
    }

    private long getPlaytime(Player p) {
        return playtime.getOrDefault(p.getUniqueId(), 0L) + currentSession(p.getUniqueId());
    }

    private boolean hasPower(Player p, String power) {
        return p.isOp() || powers.getOrDefault(p.getUniqueId(), Set.of()).contains(power.toLowerCase(Locale.ROOT));
    }

    private boolean ready(Player p, String power) {
        if (!unlocked(p)) {
            p.sendMessage(color("&cPowers unlock after 24 hours of playtime. &7You have &f" + format(getPlaytime(p)) + "&7."));
            return false;
        }
        if (!hasPower(p, power)) {
            p.sendMessage(color("&cYou don't have the &f" + power + " &cpower."));
            return false;
        }
        String key = p.getUniqueId() + ":" + power.toLowerCase(Locale.ROOT);
        long end = cooldowns.getOrDefault(key, 0L);
        long now = System.currentTimeMillis();
        if (end > now) {
            p.sendMessage(color("&cCooldown: &f" + ((end - now + 999) / 1000) + "s"));
            return false;
        }
        return true;
    }

    private void cd(Player p, String power) {
        long durationMs = config.getLong("cooldowns." + power.toLowerCase(Locale.ROOT), 10) * 1000;
        cooldowns.put(p.getUniqueId() + ":" + power.toLowerCase(Locale.ROOT), System.currentTimeMillis() + durationMs);
        startCooldownBar(p, power, durationMs);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String format(long sec) {
        return String.format("%02dh %02dm %02ds", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private void use(Player p, String raw) {
        String power = raw.toLowerCase(Locale.ROOT);
        if (!ready(p, power)) return;
        switch (power) {
            case "lightning" -> {
                Entity target = targetEntity(p, 12);
                if (target instanceof LivingEntity l) {
                    l.getWorld().strikeLightning(l.getLocation());
                    l.damage(config.getDouble("damage.lightning", 8), p);
                } else {
                    p.getWorld().strikeLightning(p.getTargetBlockExact(12) != null ? p.getTargetBlockExact(12).getLocation() : p.getLocation());
                }
                premiumEffect(p, power);
                cd(p, power);
            }
            case "fire" -> {
                Entity target = targetEntity(p, 10);
                if (target instanceof LivingEntity l) {
                    l.setFireTicks((int) (config.getInt("duration.fire", 5) * 20));
                    l.damage(config.getDouble("damage.fire", 4), p);
                }
                p.getWorld().spawnParticle(Particle.FLAME, p.getEyeLocation().add(p.getLocation().getDirection()), 30, .3, .3, .3, .05);
                premiumEffect(p, power);
                cd(p, power);
            }
            case "water" -> {
                Vector v = p.getLocation().getDirection().normalize();
                for (int i = 1; i <= 6; i++)
                    p.getWorld().spawnParticle(Particle.SPLASH, p.getLocation().add(v.clone().multiply(i)).add(0, 1, 0), 12, .3, .3, .3, .1);
                Entity t = targetEntity(p, 8);
                if (t instanceof LivingEntity l) {
                    l.damage(config.getDouble("damage.water", 5), p);
                    l.setVelocity(v.clone().multiply(1.3).setY(.35));
                }
                premiumEffect(p, power);
                cd(p, power);
            }
            case "earth" -> {
                Location l = p.getTargetBlockExact(8) != null ? p.getTargetBlockExact(8).getLocation().add(.5, 1, .5) : p.getLocation();
                p.getWorld().spawnParticle(Particle.BLOCK, l, 50, .7, .3, .7, .1, Material.DIRT.createBlockData());
                for (Entity e : p.getNearbyEntities(4, 2, 4))
                    if (e instanceof LivingEntity le && e != p) {
                        le.damage(config.getDouble("damage.earth", 6), p);
                        le.setVelocity(new Vector(0, .8, 0));
                    }
                premiumEffect(p, power);
                cd(p, power);
            }
            case "speed" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, config.getInt("duration.speed", 10) * 20, config.getInt("amplifier.speed", 2), false, false, true));
                premiumEffect(p, power);
                cd(p, power);
            }
            case "jump" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, config.getInt("duration.jump", 10) * 20, config.getInt("amplifier.jump", 3), false, false, true));
                premiumEffect(p, power);
                cd(p, power);
            }
            case "shield" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, config.getInt("duration.shield", 8) * 20, config.getInt("amplifier.shield", 2), false, false, true));
                premiumEffect(p, power);
                cd(p, power);
            }
            case "invisibility" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, config.getInt("duration.invisibility", 10) * 20, 0, false, false, true));
                premiumEffect(p, power);
                cd(p, power);
            }
            case "dash" -> {
                Vector v = p.getLocation().getDirection().normalize().multiply(config.getDouble("dash-power", 1.8));
                v.setY(Math.max(.35, v.getY() + .25));
                p.setVelocity(v);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 25, .3, .3, .3, .08);
                premiumEffect(p, power);
                cd(p, power);
            }
            case "magnet" -> {
                int r = config.getInt("magnet-radius", 12);
                for (Entity e : p.getNearbyEntities(r, r, r))
                    if (e instanceof Item it) {
                        Vector v = p.getLocation().toVector().subtract(it.getLocation().toVector()).normalize().multiply(.7);
                        it.setVelocity(v);
                    }
                premiumEffect(p, power);
                cd(p, power);
            }
            // ---- new addon powers ----
            case "heal" -> {
                double amount = config.getDouble("heal-amount", 6);
                double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                p.setHealth(Math.min(max, p.getHealth() + amount));
                p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.6, 0), 12, .4, .4, .4, 0);
                premiumEffect(p, power);
                cd(p, power);
            }
            case "blink" -> {
                double distance = config.getDouble("blink-distance", 8);
                var loc = p.getLocation();
                var dir = loc.getDirection().normalize();
                var target = loc.clone();
                for (double d = distance; d > 0; d -= 0.5) {
                    var check = loc.clone().add(dir.clone().multiply(d));
                    if (check.getBlock().isPassable() && check.clone().add(0, 1, 0).getBlock().isPassable()) {
                        target = check;
                        break;
                    }
                }
                p.teleport(target);
                p.getWorld().spawnParticle(Particle.PORTAL, loc, 40, .3, .5, .3, .3);
                p.getWorld().spawnParticle(Particle.PORTAL, target, 40, .3, .5, .3, .3);
                premiumEffect(p, power);
                cd(p, power);
            }
            default -> p.sendMessage(color("&cUnknown power. Use &f/power list"));
        }
    }

    private Entity targetEntity(Player p, int range) {
        var result = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getLocation().getDirection(), range, e -> e != p);
        return result == null ? null : result.getHitEntity();
    }

    private void premiumEffect(Player p, String power) {
        if (!config.getBoolean("premium-effects", true)) return;
        String serverName = config.getString("premium-server-name", "HYPE MC");
        p.sendActionBar(color("&d✦ &l" + serverName + " &5| &f" + capitalize(power) + " &dPOWER ACTIVATED ✦"));
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 18, .45, .65, .45, .03);
        p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 28, .65, .8, .65, .05);
    }

    // ---- addon: boss bar cooldown indicator ----
    private void startCooldownBar(Player p, String power, long durationMs) {
        if (durationMs <= 0) return;
        BossBar existing = cooldownBars.get(p.getUniqueId());
        if (existing != null) existing.removeAll();
        BossBar bar = Bukkit.createBossBar(color("&d" + capitalize(power) + " &7cooldown"), BarColor.PURPLE, BarStyle.SOLID);
        bar.addPlayer(p);
        bar.setProgress(1.0);
        cooldownBars.put(p.getUniqueId(), bar);
    }

    private void tickCooldownBars() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, BossBar>> it = cooldownBars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BossBar> entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            BossBar bar = entry.getValue();
            if (p == null) {
                bar.removeAll();
                it.remove();
                continue;
            }
            long soonestEnd = 0;
            for (Map.Entry<String, Long> cdEntry : cooldowns.entrySet()) {
                if (cdEntry.getKey().startsWith(p.getUniqueId() + ":") && cdEntry.getValue() > now) {
                    soonestEnd = Math.max(soonestEnd, cdEntry.getValue());
                }
            }
            if (soonestEnd <= now) {
                bar.removeAll();
                it.remove();
            } else {
                double remaining = (soonestEnd - now) / 1000.0;
                double total = Math.max(remaining, 1);
                bar.setProgress(Math.max(0, Math.min(1, remaining / Math.max(total, remaining))));
            }
        }
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(color("&d&m----------------------------------------"));
            s.sendMessage(color("&d&lMasterPowers &7| &5Exclusive to &d&lHYPE MC"));
            s.sendMessage(color("&7/power list, use, give, remove, time, stats, reload"));
            s.sendMessage(color("&d&m----------------------------------------"));
            return true;
        }
        if (a[0].equalsIgnoreCase("list")) {
            s.sendMessage(color("&dPowers: &fLightning, Fire, Water, Earth, Speed, Jump, Shield, Dash, Magnet, Invisibility, Heal, Blink"));
            return true;
        }
        if (a[0].equalsIgnoreCase("time") && s instanceof Player p) {
            s.sendMessage(color("&dPlaytime: &f" + format(getPlaytime(p)) + (unlocked(p) ? " &a[UNLOCKED]" : " &c[LOCKED]")));
            return true;
        }
        if (a[0].equalsIgnoreCase("use") && s instanceof Player p && a.length >= 2) {
            use(p, a[1]);
            return true;
        }
        if (a[0].equalsIgnoreCase("give") && s.hasPermission("masterpowers.admin") && a.length >= 3) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                s.sendMessage(color("&cPlayer not found."));
                return true;
            }
            powers.computeIfAbsent(t.getUniqueId(), x -> new HashSet<>()).add(a[2].toLowerCase(Locale.ROOT));
            s.sendMessage(color("&aPower given."));
            return true;
        }
        if (a[0].equalsIgnoreCase("remove") && s.hasPermission("masterpowers.admin") && a.length >= 3) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                s.sendMessage(color("&cPlayer not found."));
                return true;
            }
            powers.computeIfAbsent(t.getUniqueId(), x -> new HashSet<>()).remove(a[2].toLowerCase(Locale.ROOT));
            s.sendMessage(color("&aPower removed."));
            return true;
        }
        if (a[0].equalsIgnoreCase("reload") && s.hasPermission("masterpowers.admin")) {
            reloadConfig();
            s.sendMessage(color("&aMasterPowers config reloaded."));
            return true;
        }
        // ---- new addon subcommand ----
        if (a[0].equalsIgnoreCase("stats") && s.hasPermission("masterpowers.admin") && a.length >= 2) {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                s.sendMessage(color("&cPlayer not found."));
                return true;
            }
            Set<String> owned = powers.getOrDefault(t.getUniqueId(), Set.of());
            s.sendMessage(color("&d" + t.getName() + "'s stats:"));
            s.sendMessage(color("&7Playtime: &f" + format(getPlaytime(t)) + (unlocked(t) ? " &a[UNLOCKED]" : " &c[LOCKED]")));
            s.sendMessage(color("&7Powers: &f" + (owned.isEmpty() ? "none" : String.join(", ", owned))));
            return true;
        }
        s.sendMessage(color("&cNo permission or invalid command."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("list", "use", "give", "remove", "time", "stats", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("use")) {
            return ALL_POWERS;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("stats"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("remove"))) {
            return ALL_POWERS;
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && hasPower(p, "shield") && p.hasPotionEffect(PotionEffectType.RESISTANCE)) {
            e.setDamage(e.getDamage() * .65);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null) return;
    }
}
