package com.elias.spawnflight;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpawnFlight extends JavaPlugin implements Listener, CommandExecutor {

    // Zustand pro Spieler
    private static final class State {
        boolean armed;          // darf per Doppelsprung losgleiten
        long outsideSince;      // seit wann außerhalb der Zone (0 = innerhalb)
        boolean gliding;        // laufender Flug (gestartet durch dieses Plugin)
        int boostsUsed;         // Boosts in diesem Flug
        long lastBoost;         // Zeitpunkt des letzten Boosts
        boolean hadAllowFlight; // Flugrecht des Spielers vor der Berechtigung
    }

    private final Map<UUID, State> states = new HashMap<>();
    private NamespacedKey armedKey;

    // Zone (aus pos1 und pos2 berechnet)
    private boolean zoneSet;
    private String zoneWorld;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    @Override
    public void onEnable() {
        armedKey = new NamespacedKey(this, "armed");

        getConfig().addDefault("boost-strength", 1.5);
        getConfig().addDefault("boosts-per-flight", 3);
        getConfig().addDefault("boost-cooldown-seconds", 2.0);
        getConfig().addDefault("outside-window-seconds", 4.0);
        getConfig().addDefault("min-height", 3);
        getConfig().options().copyDefaults(true);
        saveConfig();

        rebuildZone();
        for (Player p : Bukkit.getOnlinePlayers()) {
            cleanupLeftover(p);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("flugzone").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 2L);
        getLogger().info("SpawnFlight aktiviert.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            if (st != null) {
                disarm(p, st);
            }
        }
        states.clear();
    }

    // ===== Zone =====

    private void rebuildZone() {
        zoneSet = false;
        if (!getConfig().isSet("pos1.world") || !getConfig().isSet("pos2.world")) {
            return;
        }
        String world1 = getConfig().getString("pos1.world");
        String world2 = getConfig().getString("pos2.world");
        if (world1 == null || !world1.equals(world2)) {
            return;
        }

        int x1 = getConfig().getInt("pos1.x");
        int y1 = getConfig().getInt("pos1.y");
        int z1 = getConfig().getInt("pos1.z");
        int x2 = getConfig().getInt("pos2.x");
        int y2 = getConfig().getInt("pos2.y");
        int z2 = getConfig().getInt("pos2.z");

        minX = Math.min(x1, x2);
        maxX = Math.max(x1, x2);
        minY = Math.min(y1, y2);
        maxY = Math.max(y1, y2);
        minZ = Math.min(z1, z2);
        maxZ = Math.max(z1, z2);

        // Mindesthöhe der Zone (Standard 3 Blöcke)
        int minHeight = Math.max(1, getConfig().getInt("min-height", 3));
        if (maxY - minY + 1 < minHeight) {
            maxY = minY + minHeight - 1;
        }

        zoneWorld = world1;
        zoneSet = true;
    }

    private boolean isInZone(Location loc) {
        if (!zoneSet || loc.getWorld() == null || !loc.getWorld().getName().equals(zoneWorld)) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    // ===== Berechtigung (Flugrecht) =====

    private boolean isSurvivalLike(Player p) {
        GameMode mode = p.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    // Spieler bekommt die Berechtigung: Doppelsprung startet den Flug
    private void arm(Player p, State st) {
        if (st.armed) {
            return;
        }
        st.armed = true;
        st.hadAllowFlight = p.getAllowFlight();
        // Merken, damit nach einem Absturz oder Neustart aufgeräumt werden kann
        p.getPersistentDataContainer().set(armedKey, PersistentDataType.BYTE,
                (byte) (st.hadAllowFlight ? 1 : 0));
        p.setAllowFlight(true);
    }

    // Berechtigung entziehen und das ursprüngliche Flugrecht wiederherstellen
    private void disarm(Player p, State st) {
        if (!st.armed) {
            return;
        }
        st.armed = false;
        st.outsideSince = 0;
        p.getPersistentDataContainer().remove(armedKey);
        restoreFlight(p, st.hadAllowFlight);
    }

    private void restoreFlight(Player p, boolean hadAllowFlight) {
        if (hadAllowFlight || !isSurvivalLike(p)) {
            return; // Creative/Spectator und fremde Flugrechte nicht anfassen
        }
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    // Räumt Reste auf, falls der Server mit gesetzter Berechtigung gestoppt wurde
    private void cleanupLeftover(Player p) {
        Byte had = p.getPersistentDataContainer().get(armedKey, PersistentDataType.BYTE);
        if (had == null) {
            return;
        }
        p.getPersistentDataContainer().remove(armedKey);
        if (had.byteValue() == 0) {
            restoreFlight(p, false);
        }
    }

    // ===== Regelmäßige Prüfung (alle 2 Ticks) =====

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            updatePlayer(p, now);
        }
    }

    private void updatePlayer(Player p, long now) {
        UUID id = p.getUniqueId();
        State st = states.get(id);

        // Nicht zuständig: Zone fehlt, anderer Spielmodus oder Spieler tot
        if (!zoneSet || !isSurvivalLike(p) || p.isDead()) {
            if (st != null) {
                disarm(p, st);
                states.remove(id);
            }
            return;
        }

        boolean inZone = isInZone(p.getLocation());
        if (st == null) {
            if (!inZone) {
                return;
            }
            st = new State();
            states.put(id, st);
        }

        // Laufender Flug: nichts tun, bis der Server das Gleiten beendet (Landung)
        if (st.gliding) {
            if (p.isGliding()) {
                return;
            }
            st.gliding = false;
        }

        boolean inVehicle = p.isInsideVehicle();
        if (inZone && !inVehicle) {
            st.outsideSince = 0;
            arm(p, st);
        } else if (st.armed) {
            if (st.outsideSince == 0) {
                st.outsideSince = now;
            }
            long windowMs = (long) (getConfig().getDouble("outside-window-seconds", 4.0) * 1000);
            boolean onGround = p.isOnGround();
            boolean inLiquid = p.getLocation().getBlock().isLiquid();
            if (onGround || inLiquid || inVehicle || now - st.outsideSince > windowMs) {
                disarm(p, st);
            }
        }

        if (!st.armed && !st.gliding) {
            states.remove(id);
        }
    }

    // ===== Flugstart per Doppelsprung =====

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        State st = states.get(p.getUniqueId());
        if (st == null || !st.armed || !isSurvivalLike(p)) {
            return; // nicht unser Fall
        }
        if (!event.isFlying()) {
            return;
        }

        // Normales Creative-Fliegen verhindern, wir starten stattdessen das Gleiten
        event.setCancelled(true);
        p.setFlying(false);

        if (p.isOnGround() || p.isInsideVehicle()) {
            return; // Start nur in der Luft
        }
        startGlide(p, st);
    }

    private void startGlide(Player p, State st) {
        st.gliding = true;
        st.boostsUsed = 0;
        st.lastBoost = 0;
        disarm(p, st); // Berechtigung ist verbraucht, Flugrecht wieder aus
        p.setGliding(true);
        p.setVelocity(p.getVelocity().add(new Vector(0, 0.4, 0)));
    }

    // ===== Gleiten nicht abschalten lassen, solange der Spieler in der Luft ist =====

    private boolean mayStopGliding(Player p) {
        Block feet = p.getLocation().getBlock();
        return p.isOnGround() || p.isInsideVehicle() || p.isDead() || !feet.getType().isAir();
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) event.getEntity();
        if (event.isGliding()) {
            return; // nur das Beenden interessiert uns
        }
        State st = states.get(p.getUniqueId());
        if (st == null || !st.gliding) {
            return;
        }
        if (mayStopGliding(p)) {
            return; // Landung, Wasser, Fahrzeug usw.: Beenden zulassen
        }
        event.setCancelled(true); // in der Luft weitergleiten
    }

    // ===== Boost mit der F-Taste =====

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        State st = states.get(p.getUniqueId());
        if (st == null || !st.gliding || !p.isGliding()) {
            return; // am Boden und ohne Flug bleibt F normal
        }
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long cooldownMs = (long) (getConfig().getDouble("boost-cooldown-seconds", 2.0) * 1000);
        int maxBoosts = getConfig().getInt("boosts-per-flight", 3);

        if (cooldownMs > 0 && now - st.lastBoost < cooldownMs) {
            return;
        }
        if (maxBoosts > 0 && st.boostsUsed >= maxBoosts) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.RED + "Keine Boosts mehr in diesem Flug"));
            return;
        }

        double strength = getConfig().getDouble("boost-strength", 1.5);
        Vector direction = p.getLocation().getDirection().normalize();
        p.setVelocity(direction.multiply(strength));
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);

        st.boostsUsed++;
        st.lastBoost = now;

        if (maxBoosts > 0) {
            int left = maxBoosts - st.boostsUsed;
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.GREEN + "Boost! Noch " + left + " übrig"));
        }
    }

    // ===== Aufräumen beim Ein- und Ausloggen =====

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        cleanupLeftover(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        State st = states.remove(p.getUniqueId());
        if (st != null) {
            disarm(p, st);
        }
    }

    // ===== Befehle =====

    private void savePos(String name, Player p) {
        Location loc = p.getLocation();
        getConfig().set(name + ".world", loc.getWorld().getName());
        getConfig().set(name + ".x", loc.getBlockX());
        getConfig().set(name + ".y", loc.getBlockY());
        getConfig().set(name + ".z", loc.getBlockZ());
        saveConfig();
        rebuildZone();
    }

    private void zoneMessage(CommandSender sender) {
        if (zoneSet) {
            sender.sendMessage(ChatColor.GREEN + "Zone aktiv: X " + minX + " bis " + maxX
                    + ", Y " + minY + " bis " + maxY + ", Z " + minZ + " bis " + maxZ
                    + " (Welt " + zoneWorld + ")");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Die Zone ist noch nicht komplett (pos1 und pos2 in derselben Welt nötig).");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Benutzung: /flugzone <pos1|pos2|info|remove|boost <Wert>|boosts <Anzahl>|cooldown <Sekunden>>");
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("pos1") || sub.equals("pos2")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Dieser Befehl geht nur im Spiel.");
                return true;
            }
            Player p = (Player) sender;
            savePos(sub, p);
            sender.sendMessage(ChatColor.GREEN + sub + " gesetzt auf deine Position ("
                    + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockY() + ", "
                    + p.getLocation().getBlockZ() + ").");
            zoneMessage(sender);
            return true;
        }

        if (sub.equals("info")) {
            zoneMessage(sender);
            sender.sendMessage(ChatColor.GRAY + "Boost-Stärke: " + getConfig().getDouble("boost-strength", 1.5)
                    + " | Boosts pro Flug: " + getConfig().getInt("boosts-per-flight", 3)
                    + " (0 = unbegrenzt) | Pause: " + getConfig().getDouble("boost-cooldown-seconds", 2.0)
                    + " s");
            return true;
        }

        if (sub.equals("remove")) {
            getConfig().set("pos1", null);
            getConfig().set("pos2", null);
            saveConfig();
            rebuildZone();
            sender.sendMessage(ChatColor.GREEN + "Zone gelöscht.");
            return true;
        }

        if (sub.equals("boost") || sub.equals("boosts") || sub.equals("cooldown")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Bitte einen Wert angeben, z. B. /flugzone " + sub + " 2");
                return true;
            }
            try {
                double value = Double.parseDouble(args[1].replace(',', '.'));
                if (value < 0) {
                    sender.sendMessage(ChatColor.RED + "Der Wert darf nicht negativ sein.");
                    return true;
                }
                if (sub.equals("boost")) {
                    getConfig().set("boost-strength", value);
                } else if (sub.equals("boosts")) {
                    getConfig().set("boosts-per-flight", (int) value);
                } else {
                    getConfig().set("boost-cooldown-seconds", value);
                }
                saveConfig();
                sender.sendMessage(ChatColor.GREEN + "Gespeichert: " + sub + " = " + value);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Das ist keine Zahl.");
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unbekannter Unterbefehl.");
        return true;
    }
}
