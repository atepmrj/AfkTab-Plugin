package dev.afktab;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AfkTabPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String TEAM_NAME = "zzz_afk";
    private static final long SAVE_EVERY_MS = 60_000L;
    private static final long KEEP_DATA_MS = 14L * 24 * 3600 * 1000;
    private static final long MIN_IDLE_TO_REMEMBER_MS = 60_000L;

    /** Состояние онлайн-игрока. */
    private static final class State {
        long lastMove = System.currentTimeMillis();
        Location anchor;
        boolean afk;
        boolean inTeam;
        String originalListName;
    }

    /** Что помним про игрока, который вышел. */
    private static final class Saved {
        boolean afk;
        long idleMillis;
        long savedAt;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Saved> saved = new HashMap<>();
    private Team afkTeam;
    private File dataFile;
    private boolean dirty;
    private long lastSave = System.currentTimeMillis();

    // config cache
    private long afkMillis;
    private int checkIntervalSeconds;
    private String tabFormat;
    private boolean sortToBottom;
    private double moveDistanceSq;
    private boolean hfEnabled;
    private List<String> hfHeader;
    private List<String> hfFooter;
    private boolean broadcastEnabled;
    private String msgAfkOn;
    private String msgAfkOff;

    private int taskId = -1;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        setupTeam();

        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("afk") != null) getCommand("afk").setExecutor(this);
        if (getCommand("afktab") != null) getCommand("afktab").setExecutor(this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            onPlayerPresent(p);
        }
        startTask();
        updateTab();
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null) continue;
            // запоминаем, чтобы после рестарта сервера статус сохранился
            remember(p.getUniqueId(), s);
            if (s.afk) {
                applyAfk(p, s, false);
            }
            if (hfEnabled) {
                p.setPlayerListHeader(null);
                p.setPlayerListFooter(null);
            }
        }
        saveData();
        states.clear();
        if (afkTeam != null) {
            try {
                afkTeam.unregister();
            } catch (IllegalStateException ignored) {
                // already unregistered
            }
            afkTeam = null;
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        afkMillis = Math.max(1, c.getLong("afk-minutes", 20)) * 60_000L;
        checkIntervalSeconds = Math.max(1, c.getInt("check-interval-seconds", 5));
        tabFormat = c.getString("tab-format", "&7[AFK] {player}");
        sortToBottom = c.getBoolean("sort-to-bottom", true);
        double d = Math.max(0.1, c.getDouble("move-distance", 1.5));
        moveDistanceSq = d * d;
        hfEnabled = c.getBoolean("tab-header-footer.enabled", true);
        hfHeader = c.getStringList("tab-header-footer.header");
        hfFooter = c.getStringList("tab-header-footer.footer");
        broadcastEnabled = c.getBoolean("broadcast.enabled", false);
        msgAfkOn = c.getString("broadcast.afk-on", "&7{player} теперь AFK");
        msgAfkOff = c.getString("broadcast.afk-off", "&7{player} вернулся");
    }

    private void setupTeam() {
        if (Bukkit.getScoreboardManager() == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam(TEAM_NAME);
        if (t == null) {
            t = sb.registerNewTeam(TEAM_NAME);
        }
        // чистим хвосты после возможного краша
        for (String entry : new HashSet<>(t.getEntries())) {
            t.removeEntry(entry);
        }
        afkTeam = t;
    }

    private void startTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        long period = 20L * checkIntervalSeconds;
        taskId = Bukkit.getScheduler().runTaskTimer(this, this::tick, period, period).getTaskId();
    }

    // ------------------------------------------------------------------ persistence

    private void loadData() {
        saved.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        long now = System.currentTimeMillis();
        for (String key : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) continue;
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Saved sv = new Saved();
            sv.afk = sec.getBoolean("afk", false);
            sv.idleMillis = sec.getLong("idle", 0L);
            sv.savedAt = sec.getLong("saved-at", now);
            if (now - sv.savedAt > KEEP_DATA_MS) continue;
            saved.put(id, sv);
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Saved> e : saved.entrySet()) {
            String k = e.getKey().toString();
            cfg.set(k + ".afk", e.getValue().afk);
            cfg.set(k + ".idle", e.getValue().idleMillis);
            cfg.set(k + ".saved-at", e.getValue().savedAt);
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException ex) {
            getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    private void remember(UUID id, State s) {
        long now = System.currentTimeMillis();
        long idle = s.afk ? afkMillis : now - s.lastMove;
        if (s.afk || idle >= MIN_IDLE_TO_REMEMBER_MS) {
            Saved sv = new Saved();
            sv.afk = s.afk;
            sv.idleMillis = idle;
            sv.savedAt = now;
            saved.put(id, sv);
        } else {
            saved.remove(id);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ core logic

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null || s.afk) continue;
            if (p.hasPermission("afktab.exempt")) {
                s.lastMove = now;
                continue;
            }
            if (now - s.lastMove >= afkMillis) {
                setAfk(p, s, true);
            }
        }
        if (dirty && now - lastSave >= SAVE_EVERY_MS) {
            saveData();
        }
    }

    /** Игрок появился на сервере (вход или перезагрузка плагина). */
    private void onPlayerPresent(Player p) {
        State s = new State();
        s.anchor = p.getLocation().clone();
        states.put(p.getUniqueId(), s);

        Saved sv = saved.remove(p.getUniqueId());
        dirty = true;
        if (sv == null || p.hasPermission("afktab.exempt")) return;

        if (sv.afk) {
            // был AFK до выхода: остаётся AFK, пока не пойдёт
            s.lastMove = System.currentTimeMillis() - afkMillis;
            applyAfk(p, s, true);
        } else {
            // продолжаем копить бездействие с того места, где остановились
            s.lastMove = System.currentTimeMillis() - Math.max(0L, sv.idleMillis);
        }
    }

    /** Реальное перемещение игрока. */
    private void moved(Player p, State s) {
        s.lastMove = System.currentTimeMillis();
        if (s.afk) {
            setAfk(p, s, false);
        }
    }

    private void setAfk(Player p, State s, boolean afk) {
        if (s.afk == afk) return;
        applyAfk(p, s, afk);
        if (broadcastEnabled) {
            String raw = afk ? msgAfkOn : msgAfkOff;
            Bukkit.broadcastMessage(color(raw.replace("{player}", p.getName())));
        }
        updateTab();
    }

    private void applyAfk(Player p, State s, boolean afk) {
        s.afk = afk;
        if (afk) {
            s.originalListName = p.getPlayerListName();
            p.setPlayerListName(color(tabFormat.replace("{player}", p.getName())));
            if (sortToBottom && afkTeam != null) {
                Scoreboard sb = afkTeam.getScoreboard();
                if (sb != null && sb.getEntryTeam(p.getName()) == null) {
                    afkTeam.addEntry(p.getName());
                    s.inTeam = true;
                }
            }
        } else {
            String orig = s.originalListName;
            if (orig == null || orig.equals(p.getName())) {
                p.setPlayerListName(null);
            } else {
                p.setPlayerListName(orig);
            }
            s.originalListName = null;
            releaseTeam(p, s);
        }
    }

    private void releaseTeam(Player p, State s) {
        if (s.inTeam && afkTeam != null) {
            try {
                afkTeam.removeEntry(p.getName());
            } catch (IllegalStateException ignored) {
                // team already unregistered
            }
        }
        s.inTeam = false;
    }

    private void updateTab() {
        if (!hfEnabled) return;
        int total = states.size();
        int afk = 0;
        for (State s : states.values()) {
            if (s.afk) afk++;
        }
        int online = total - afk;
        String header = format(hfHeader, online, afk, total);
        String footer = format(hfFooter, online, afk, total);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!states.containsKey(p.getUniqueId())) continue;
            p.setPlayerListHeader(header.isEmpty() ? null : header);
            p.setPlayerListFooter(footer.isEmpty() ? null : footer);
        }
    }

    private String format(List<String> lines, int online, int afk, int total) {
        if (lines == null || lines.isEmpty()) return "";
        String joined = String.join("\n", lines)
                .replace("{online}", String.valueOf(online))
                .replace("{afk}", String.valueOf(afk))
                .replace("{total}", String.valueOf(total));
        return color(joined);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        onPlayerPresent(e.getPlayer());
        updateTab();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        State s = states.remove(p.getUniqueId());
        if (s != null) {
            remember(p.getUniqueId(), s);
            releaseTeam(p, s);
        }
        updateTab();
    }

    /** Единственный признак активности - ходьба. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null) return;
        Player p = e.getPlayer();
        State s = states.get(p.getUniqueId());
        if (s == null) return;

        // в транспорте двигается транспорт, а не игрок
        if (p.isInsideVehicle()) return;

        Location a = s.anchor;
        if (a == null || a.getWorld() != to.getWorld()) {
            s.anchor = to.clone();
            return;
        }
        if (a.distanceSquared(to) >= moveDistanceSq) {
            s.anchor = to.clone();
            moved(p, s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s != null && e.getTo() != null) {
            // телепорт не считается ходьбой, просто сдвигаем точку отсчёта
            s.anchor = e.getTo().clone();
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("afktab")) {
            if (!sender.hasPermission("afktab.admin")) {
                sender.sendMessage(color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                loadSettings();
                startTask();
                updateTab();
                sender.sendMessage(color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
            } else {
                sender.sendMessage(color("&7Использование: /afktab reload"));
            }
            return true;
        }

        if (name.equals("afk")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getConfig().getString("messages.only-player", "Только для игроков."));
                return true;
            }
            Player p = (Player) sender;
            State s = states.get(p.getUniqueId());
            if (s == null) return true;
            if (s.afk) {
                s.lastMove = System.currentTimeMillis();
                setAfk(p, s, false);
            } else {
                setAfk(p, s, true);
            }
            return true;
        }
        return false;
    }
}
