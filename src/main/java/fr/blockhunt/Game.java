package fr.blockhunt;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single game of the server: a lobby of players, then a hiding phase (seekers blinded and
 * frozen), then a seeking phase. A hider found by a seeker becomes a seeker. Seekers win when
 * no hider is left, hiders win when the time runs out.
 *
 * <p>Seekers carry a sonar (a sound at every hider) and read the distance to the nearest hider
 * on their XP bar. Hiders read their progress towards solid on theirs, and can taunt: a loud
 * sound at their position that takes time off the clock.
 */
// ponytail: one arena per server. Make Game per-arena, keyed by name, if a server ever needs two.
final class Game implements Listener {

    enum State { LOBBY, HIDING, SEEKING }

    /** Distance at which a seeker's XP bar is empty. */
    private static final double PROXIMITY_RANGE = 50;

    /** Pause between two taunts, so a double click does not burn both. */
    private static final int TAUNT_SPAM_TICKS = 200;

    /** Players needed to start a game: one seeker, one hider. */
    private static final int MIN_PLAYERS = 2;

    /** Names listed on the lobby sidebar, which shows 15 lines at most: past that, a count. */
    private static final int LISTED_NAMES = 6;

    /** A player of the running game. Their own inventory waits in their PDC meanwhile. */
    private static final class Session {
        int finds;
        int taunts;

        Session(int taunts) {
            this.taunts = taunts;
        }
    }

    private final Plugin plugin;
    private final Stash stash;

    /** Everyone who joined, in the lobby or in the running game. */
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> seekers = new HashSet<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** One per joined player, from /bh join to leaving: the lobby, then their role in a game. */
    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    /** Hiders of the running game, plus anyone disguised with /bh disguise for testing. */
    private final Map<UUID, Disguise> disguises = new HashMap<>();

    private final BossBar bar = BossBar.bossBar(Component.empty(), 1, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

    private State state = State.LOBBY;
    private int ticksLeft;
    private int phaseTicks;
    /** World of the running game. */
    private World arena;
    /** Play area of the running game, null between games or with arena-radius 0. */
    private Dome dome;
    /** Ticks before the lobby starts a game on its own, -1 while it waits for players. */
    private int countdown = -1;
    /** reducedDebugInfo of the arena before the game, given back when it ends. */
    private Boolean debugInfoBefore;

    Game(Plugin plugin) {
        this.plugin = plugin;
        stash = new Stash(plugin);
        // Always running: test disguises need ticking outside a game too.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    // --- Commands -------------------------------------------------------------

    void join(Player player) {
        if (state != State.LOBBY) {
            deny(player, "A game is running. Wait for the next one.");
            return;
        }
        if (!players.add(player.getUniqueId())) {
            deny(player, "You already joined.");
            return;
        }
        sidebars.put(player.getUniqueId(), new Sidebar(player));
        player.showTitle(Title.title(Component.text("BlockHunt", NamedTextColor.GOLD),
                Component.text("You joined the lobby: " + players.size() + " players")));
        broadcast(Component.text(player.getName() + " joined (" + players.size() + " players).", NamedTextColor.AQUA));
        Component invite = Component.text("A BlockHunt game is about to start: " + player.getName() + " joined ("
                + players.size() + " players). ", NamedTextColor.AQUA)
                .append(link("[Join]", "/bh join", "Join the lobby"));
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!players.contains(other.getUniqueId())) {
                other.sendMessage(invite);
            }
        }
        updateHud();
    }

    void leave(Player player) {
        if (!players.remove(player.getUniqueId())) {
            deny(player, "You are not in the game.");
            return;
        }
        player.sendMessage(Component.text("You left the game.", NamedTextColor.AQUA));
        if (state != State.LOBBY) {
            reset(player);
            seekers.remove(player.getUniqueId());
        }
        removeSidebar(player);
        checkEnd();
        updateHud();
    }

    /** After /bh setspawn: the lobby status may change from "No spawn set". */
    void refreshLobby() {
        if (state == State.LOBBY) {
            updateHud();
        }
    }

    void start(Audience sender) {
        if (state != State.LOBBY) {
            sender.sendMessage(Component.text("A game is already running.", NamedTextColor.RED));
            return;
        }
        if (players.size() < MIN_PLAYERS) {
            sender.sendMessage(Component.text("At least " + MIN_PLAYERS + " players must /bh join.", NamedTextColor.RED));
            return;
        }
        Location spawn = plugin.getConfig().getLocation("spawn");
        if (spawn == null || !spawn.isWorldLoaded()) {
            sender.sendMessage(Component.text("No spawn: stand in the arena and run /bh setspawn.", NamedTextColor.RED));
            return;
        }
        List<Material> blocks = plugin.getConfig().getStringList("blocks").stream()
                .map(Material::matchMaterial)
                .filter(material -> material != null && material.isBlock())
                .toList();
        if (blocks.isEmpty()) {
            sender.sendMessage(Component.text("No valid block in config.yml.", NamedTextColor.RED));
            return;
        }

        // Teleport first, while still in the lobby: a world change drops test disguises, it must
        // not count as leaving the game. A refused teleport (a mount, another plugin) leaves that
        // player out, rather than playing from wherever they stand.
        List<Player> online = new ArrayList<>();
        Map<UUID, Location> back = new HashMap<>();
        for (Player player : online(players)) {
            Location from = player.getLocation();
            if (player.teleport(spawn)) {
                online.add(player);
                back.put(player.getUniqueId(), from);
            } else {
                players.remove(player.getUniqueId());
                removeSidebar(player);
                deny(player, "Could not reach the arena: you are out of this game.");
            }
        }
        if (online.size() < MIN_PLAYERS) {
            sender.sendMessage(Component.text("Not enough players reached the arena.", NamedTextColor.RED));
            updateHud();
            return;
        }

        // F3+B draws every Interaction hitbox, so every hider: reduced debug info turns it off.
        // ponytail: per world, there is no per-player API. A crash mid-game leaves the rule on
        // in the arena: harmless, /gamerule reducedDebugInfo false puts it back.
        arena = spawn.getWorld();
        debugInfoBefore = arena.getGameRuleValue(GameRules.REDUCED_DEBUG_INFO);
        arena.setGameRule(GameRules.REDUCED_DEBUG_INFO, true);

        // The play area: the game's players stay in, everyone else stays out. Anyone else standing
        // in it now would watch the hiders hide: set down outside. A radius that is no number of
        // blocks (.inf) means no dome, like 0.
        double radius = plugin.getConfig().getDouble("arena-radius");
        dome = Double.isFinite(radius) && radius > 0 ? new Dome(spawn, radius) : null;
        if (dome != null) {
            for (Player outsider : arena.getPlayers()) {
                if (!players.contains(outsider.getUniqueId()) && dome.contains(outsider.getLocation())
                        && !sendOut(outsider)) {
                    sender.sendMessage(Component.text("Could not move " + outsider.getName() + " out of the dome.",
                            NamedTextColor.RED));
                }
            }
        }

        int taunts = plugin.getConfig().getInt("taunts");
        for (Player player : online) {
            undisguise(player);
            stash.save(player, back.get(player.getUniqueId()));
            sessions.put(player.getUniqueId(), new Session(taunts));
        }
        // A countdown cut short by /bh start must not resume when this game ends.
        countdown = -1;
        assignRoles(online, blocks);
    }

    private void assignRoles(List<Player> online, List<Material> blocks) {
        Collections.shuffle(online);
        int seekerCount = Math.clamp(plugin.getConfig().getInt("seekers"), 1, online.size() - 1);
        int hideTicks = plugin.getConfig().getInt("hide-seconds") * 20;
        for (int i = 0; i < online.size(); i++) {
            Player player = online.get(i);
            player.showBossBar(bar);
            if (i < seekerCount) {
                seekers.add(player.getUniqueId());
                Kit.seeker(player);
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, hideTicks, 0, false, false));
                player.showTitle(Title.title(Component.text("Seeker", NamedTextColor.RED),
                        Component.text("Wait while they hide...")));
            } else {
                Material block = blocks.get(ThreadLocalRandom.current().nextInt(blocks.size()));
                disguise(player, block.createBlockData());
                Kit.hider(player, block.createBlockData());
                player.showTitle(Title.title(Component.text("Hider", NamedTextColor.GREEN),
                        Component.text("You are " + Kit.name(block) + ". Hide!")));
            }
        }
        phase(State.HIDING, hideTicks);
        updateHud();
    }

    /** Test command: toggles a disguise outside of a game. */
    void toggleDisguise(Player player, BlockData block) {
        if (state != State.LOBBY && players.contains(player.getUniqueId())) {
            deny(player, "Not during a game.");
            return;
        }
        if (disguises.containsKey(player.getUniqueId())) {
            undisguise(player);
            player.sendActionBar(Component.text("Disguise removed.", NamedTextColor.AQUA));
        } else {
            disguise(player, block);
            player.sendActionBar(Component.text("Disguised. Run the command again to stop.", NamedTextColor.AQUA));
        }
    }

    /** On plugin disable: nothing may outlive the plugin. */
    void stop() {
        if (state != State.LOBBY) {
            end(Component.text("Game stopped.", NamedTextColor.RED));
        }
        for (Disguise disguise : List.copyOf(disguises.values())) {
            undisguise(disguise.player());
        }
        for (Player player : online(players)) {
            removeSidebar(player);
        }
    }

    // --- Game loop ------------------------------------------------------------

    private void tick() {
        int solidifyTicks = plugin.getConfig().getInt("solidify-seconds") * 20;
        disguises.values().forEach(disguise -> disguise.tick(solidifyTicks));

        if (state == State.LOBBY) {
            tickCountdown();
            return;
        }
        // Every tick, or the bar would jump: the hider's XP bar fills up towards solid.
        for (Disguise disguise : disguises.values()) {
            if (isHider(disguise.player())) {
                disguise.player().sendExperienceChange(disguise.progress(solidifyTicks), 0);
            }
        }
        ticksLeft--;
        // Twice a second: the wall stays on screen for whoever stands near it, without a flood.
        if (dome != null && ticksLeft % 10 == 0) {
            for (Player viewer : arena.getPlayers()) {
                dome.show(viewer);
            }
        }
        bar.progress(Math.clamp((float) ticksLeft / phaseTicks, 0f, 1f));
        if (ticksLeft % 20 == 0) {
            bringBackIntoDome();
            updateBar();
            updateHud();
        }
        if (ticksLeft > 0) {
            return;
        }
        if (state == State.HIDING) {
            phase(State.SEEKING, plugin.getConfig().getInt("seek-seconds") * 20);
            for (Player seeker : online(seekers)) {
                seeker.removePotionEffect(PotionEffectType.BLINDNESS);
            }
            broadcast(Component.text("Seekers released!", NamedTextColor.RED));
        } else {
            end(Component.text("Hiders win!", NamedTextColor.GREEN));
        }
    }

    private void phase(State next, int ticks) {
        state = next;
        ticksLeft = ticks;
        phaseTicks = Math.max(1, ticks);
        bar.color(next == State.HIDING ? BossBar.Color.GREEN : BossBar.Color.RED);
        updateBar();
    }

    private void updateBar() {
        String label = state == State.HIDING ? "Hiding" : "Seeking";
        bar.name(Component.text(label + " " + clock(ticksLeft / 20) + " - " + hidersLeft() + " hiders left"));
    }

    /** 75 -> "1:15". */
    private static String clock(int seconds) {
        seconds = Math.max(0, seconds);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** Sidebars and the seekers' hot and cold: once a second in a game, on every change in the lobby. */
    private void updateHud() {
        for (Player player : online(players)) {
            Sidebar sidebar = sidebars.get(player.getUniqueId());
            Session session = sessions.get(player.getUniqueId());
            if (sidebar == null) {
                continue;
            }
            if (session == null) {
                sidebar.show(lobbyLines());
            } else if (seekers.contains(player.getUniqueId())) {
                sidebar.show(seekerLines(player, session));
                if (state == State.SEEKING) {
                    proximity(player);
                }
            } else {
                Disguise disguise = disguises.get(player.getUniqueId());
                if (disguise != null) {
                    sidebar.show(hiderLines(disguise, session));
                }
            }
        }
    }

    private List<Sidebar.Line> lobbyLines() {
        List<Sidebar.Line> lines = new ArrayList<>();
        lines.add(new Sidebar.Line("Status", lobbyStatus()));
        lines.add(new Sidebar.Line("Players", Component.text(players.size())));
        lines.add(Sidebar.EMPTY);
        List<Player> joined = online(players);
        for (int i = 0; i < joined.size(); i++) {
            if (i == LISTED_NAMES - 1 && joined.size() > LISTED_NAMES) {
                lines.add(new Sidebar.Line("and " + (joined.size() - i) + " more", Component.empty()));
                break;
            }
            lines.add(new Sidebar.Line(joined.get(i).getName(), Component.empty()));
        }
        lines.add(Sidebar.EMPTY);
        lines.add(new Sidebar.Line("Hiding", Component.text(clock(plugin.getConfig().getInt("hide-seconds")))));
        lines.add(new Sidebar.Line("Seeking", Component.text(clock(plugin.getConfig().getInt("seek-seconds")))));
        lines.add(new Sidebar.Line("Leave", Component.text("/bh leave", NamedTextColor.GRAY)));
        return lines;
    }

    private Component lobbyStatus() {
        if (plugin.getConfig().getLocation("spawn") == null) {
            return Component.text("No spawn set", NamedTextColor.RED);
        }
        int missing = minPlayers() - players.size();
        if (missing > 0) {
            return Component.text("Need " + missing + " more", NamedTextColor.YELLOW);
        }
        return countdown >= 0
                ? Component.text("Starts in " + clock((countdown + 19) / 20), NamedTextColor.GREEN)
                : Component.text("Ready", NamedTextColor.GREEN);
    }

    /** Players the lobby waits for before counting down: never under one seeker and one hider. */
    private int minPlayers() {
        return Math.max(MIN_PLAYERS, plugin.getConfig().getInt("min-players"));
    }

    /**
     * In the lobby, every tick: counts down while enough players joined and a spawn is set, then
     * starts the game as /bh start does. Falling under the minimum stops it.
     */
    private void tickCountdown() {
        boolean ready = players.size() >= minPlayers() && plugin.getConfig().getLocation("spawn") != null;
        if (!ready) {
            if (countdown >= 0) {
                countdown = -1;
                broadcast(Component.text("Not enough players: the countdown stopped.", NamedTextColor.YELLOW));
                updateHud();
            }
            return;
        }
        if (countdown < 0) {
            int seconds = Math.max(0, plugin.getConfig().getInt("countdown-seconds"));
            countdown = seconds * 20;
            broadcast(Component.text("The game starts in " + seconds + "s.", NamedTextColor.GREEN));
            updateHud();
        } else if (countdown > 0) {
            countdown--;
            if (countdown % 20 == 0) {
                updateHud();
            }
        } else {
            // Before starting: a start that fails (no valid block, arena unloaded) waits a whole
            // new countdown instead of being retried every tick. Its reason goes to the lobby
            // too, or players would only see the countdown come back.
            countdown = -1;
            start(Audience.audience(plugin.getServer().getConsoleSender(), Audience.audience(online(players))));
        }
    }

    private List<Sidebar.Line> seekerLines(Player seeker, Session session) {
        return List.of(
                new Sidebar.Line("Role", Component.text("Seeker", NamedTextColor.RED)),
                Sidebar.EMPTY,
                new Sidebar.Line("Hiders left", Component.text(hidersLeft())),
                new Sidebar.Line("Seekers", Component.text(seekers.size())),
                Sidebar.EMPTY,
                new Sidebar.Line("Your finds", Component.text(session.finds)),
                new Sidebar.Line("Sonar", countdown(seeker.getCooldown(Kit.SONAR))));
    }

    private List<Sidebar.Line> hiderLines(Disguise disguise, Session session) {
        return List.of(
                new Sidebar.Line("Role", Component.text("Hider", NamedTextColor.GREEN)),
                Sidebar.EMPTY,
                new Sidebar.Line("Disguise", Component.text(Kit.name(disguise.block().getMaterial()))),
                new Sidebar.Line("State", disguise.isSolid()
                        ? Component.text("Solid", NamedTextColor.GREEN)
                        : Component.text("Moving", NamedTextColor.YELLOW)),
                new Sidebar.Line("Change", countdown(disguise.cooldownLeft())),
                Sidebar.EMPTY,
                new Sidebar.Line("Hiders left", Component.text(hidersLeft())),
                new Sidebar.Line("Taunts", Component.text(session.taunts)),
                new Sidebar.Line("Taunt now", tauntPreview(disguise.player(), session)));
    }

    /** What a taunt would take off the clock right now, for the hider to weigh the risk. */
    private Component tauntPreview(Player hider, Session session) {
        if (session.taunts <= 0) {
            return Component.text("none left", NamedTextColor.GRAY);
        }
        if (tauntsOff()) {
            return Component.text("off", NamedTextColor.GRAY);
        }
        // Not before the seekers are released: it would also tell the hiders how far the frozen
        // seekers are, which the seekers' own hot and cold only gets once released.
        if (state != State.SEEKING) {
            return Component.text("after release", NamedTextColor.GRAY);
        }
        int seconds = tauntSeconds(hider);
        return seconds > 0
                ? Component.text("-" + seconds + "s", NamedTextColor.LIGHT_PURPLE)
                : Component.text("too far", NamedTextColor.GRAY);
    }

    /** XP level = distance to the nearest hider, bar = how close; the action bar says hot or cold. */
    private void proximity(Player seeker) {
        double nearest = nearest(seeker, disguises.values().stream().map(Disguise::player).filter(this::isHider).toList());
        if (nearest == Double.MAX_VALUE) {
            seeker.sendExperienceChange(0, 0);
            return;
        }
        seeker.sendExperienceChange((float) Math.clamp(1 - nearest / PROXIMITY_RANGE, 0, 1), (int) nearest);
        seeker.sendActionBar(nearest < 8 ? Component.text("Hot!", NamedTextColor.RED)
                : nearest < 20 ? Component.text("Warm...", NamedTextColor.GOLD)
                : Component.text("Cold", NamedTextColor.AQUA));
    }

    /** Distance to the nearest of {@code others} in the same world, as the crow flies; Double.MAX_VALUE if none. */
    private static double nearest(Player from, List<Player> others) {
        return others.stream().filter(other -> other.getWorld().equals(from.getWorld()))
                .mapToDouble(other -> other.getLocation().distance(from.getLocation()))
                .min().orElse(Double.MAX_VALUE);
    }

    private void found(Player hider, Player seeker) {
        undisguise(hider);
        seekers.add(hider.getUniqueId());
        Kit.seeker(hider);
        Session session = sessions.get(seeker.getUniqueId());
        if (session != null) {
            session.finds++;
        }
        hider.showTitle(Title.title(Component.text("Found!", NamedTextColor.RED),
                Component.text("You are a seeker now.")));
        broadcast(Component.text(seeker.getName() + " found " + hider.getName() + "!", NamedTextColor.GOLD));
        for (Player player : online(players)) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        updateBar();
        updateHud();
        checkEnd();
    }

    /** Every hider rings at their own position, heard by this seeker only. */
    private void sonar(Player seeker, ItemStack compass) {
        if (state != State.SEEKING) {
            deny(seeker, "The sonar works once seekers are released.");
            return;
        }
        if (seeker.hasCooldown(compass)) {
            deny(seeker, "Sonar recharging.");
            return;
        }
        for (Disguise disguise : disguises.values()) {
            if (isHider(disguise.player())) {
                // Volume 4: heard up to 64 blocks away.
                seeker.playSound(disguise.player().getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 4.0f, 1.0f);
            }
        }
        seeker.setCooldown(compass, plugin.getConfig().getInt("sonar-cooldown-seconds") * 20);
    }

    /**
     * A loud sound at the hider's position, paid back by time off the clock: the closer to a
     * seeker, the more. Too far from every seeker, it is refused and kept.
     */
    private void taunt(Player hider, ItemStack rocket) {
        Session session = sessions.get(hider.getUniqueId());
        if (state != State.SEEKING || session == null) {
            deny(hider, "Taunts work once seekers are released.");
            return;
        }
        if (session.taunts <= 0) {
            deny(hider, "No taunt left.");
            return;
        }
        if (hider.hasCooldown(rocket)) {
            return;
        }
        int seconds = tauntSeconds(hider);
        if (seconds <= 0) {
            deny(hider, tauntsOff() ? "Taunts are off on this server." : "Too far: get closer to a seeker.");
            return;
        }
        // The clock keeps its last second: near the end, a taunt takes off only what is left above
        // it, and the message says that, not the full value.
        int taken = Math.min(seconds, (ticksLeft - 20) / 20);
        if (taken <= 0) {
            deny(hider, "Too late: the clock is almost out.");
            return;
        }
        session.taunts--;
        hider.setCooldown(rocket, TAUNT_SPAM_TICKS);
        hider.getWorld().playSound(hider.getLocation(), Sound.ENTITY_GOAT_SCREAMING_AMBIENT, 2.0f, 1.0f);
        ticksLeft -= taken * 20;
        broadcast(Component.text(hider.getName() + " taunted the seekers: -" + taken + "s!", NamedTextColor.LIGHT_PURPLE));
        updateBar();
        updateHud();
    }

    /** What a taunt by this hider takes off the clock now, from their distance to the nearest seeker. */
    private int tauntSeconds(Player hider) {
        return tauntSeconds(nearest(hider, online(seekers)), plugin.getConfig().getInt("taunt-max-seconds"),
                plugin.getConfig().getDouble("taunt-range"));
    }

    /**
     * {@code max} right next to a seeker, then down evenly to 0 at {@code range} and beyond. Rounded
     * to whole seconds, so it reaches 0 a little before {@code range}. A range of 0 or less: 0.
     */
    static int tauntSeconds(double distance, int max, double range) {
        return range > 0 ? (int) Math.round(max * Math.max(0, 1 - distance / range)) : 0;
    }

    /** taunt-max-seconds or taunt-range at 0 or less: no taunt takes anything off, so none is allowed. */
    private boolean tauntsOff() {
        return plugin.getConfig().getInt("taunt-max-seconds") <= 0 || plugin.getConfig().getDouble("taunt-range") <= 0;
    }

    private void checkEnd() {
        if (state == State.LOBBY) {
            return;
        }
        if (hidersLeft() == 0) {
            end(Component.text("Seekers win!", NamedTextColor.RED));
        } else if (seekers.isEmpty()) {
            end(Component.text("Hiders win: no seeker left.", NamedTextColor.GREEN));
        }
    }

    /** Back to the lobby with the same players, ready for /bh start. */
    private void end(Component result) {
        // First: the teleport back can change a player's world, which must not count as leaving
        // a running game, or that player would be reset a second time and lose their inventory.
        state = State.LOBBY;
        // Everyone stays in the lobby, for the next game: one click to get out of it.
        Component stay = Component.text("You are in the lobby for the next game. ", NamedTextColor.AQUA)
                .append(link("[Leave]", "/bh leave", "Leave BlockHunt"));
        for (Player player : online(players)) {
            reset(player);
            player.showTitle(Title.title(result, Component.empty()));
            player.sendMessage(stay);
        }
        seekers.clear();
        sessions.clear();
        if (arena != null) {
            arena.setGameRule(GameRules.REDUCED_DEBUG_INFO, debugInfoBefore);
            arena = null;
        }
        dome = null;
        updateHud();
    }

    /** Gives back everything the game took: look, inventory, game mode, position, XP bar. */
    private void reset(Player player) {
        undisguise(player);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.hideBossBar(bar);
        sessions.remove(player.getUniqueId());
        // Or the sonar, taunt or block cooldowns would follow the player out of the game.
        Kit.clear(player);
        stash.restore(player);
        // The XP bar was only ever faked on the client: resend the real one.
        player.sendExperienceChange(player.getExp(), player.getLevel());
    }

    /** Out of the lobby: back to the scoreboard the player had before joining. */
    private void removeSidebar(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar != null) {
            sidebar.remove();
        }
    }

    private int hidersLeft() {
        return players.size() - seekers.size();
    }

    private boolean playing(Player player) {
        return state != State.LOBBY && players.contains(player.getUniqueId());
    }

    private boolean isHider(Player player) {
        return playing(player) && !seekers.contains(player.getUniqueId());
    }

    private void disguise(Player player, BlockData block) {
        undisguise(player);
        disguises.put(player.getUniqueId(), new Disguise(plugin, player, block));
    }

    private void undisguise(Player player) {
        Disguise disguise = disguises.remove(player.getUniqueId());
        if (disguise != null) {
            disguise.remove();
        }
    }

    // --- Events ---------------------------------------------------------------

    // Not ignoreCancelled: vanilla never "attacks" an Interaction (willAttack is false), so Paper
    // fires this event already cancelled, and setCancelled cannot change that. A hit on one of
    // our hitboxes is handled even so: no protection plugin has a reason to guard them. A player
    // of the game hitting any other entity only gets that hit cancelled.
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Disguise target = disguises.values().stream()
                .filter(disguise -> disguise.owns(event.getAttacked())).findFirst().orElse(null);
        if (target == null) {
            // Frames, armor stands, animals: a seeker swinging at everything must not wreck the
            // map, and a hidden hider hitting a mob would give their position away.
            if (playing(event.getPlayer())) {
                event.setCancelled(true);
            }
            return;
        }

        Player attacker = event.getPlayer();
        Player hider = target.player();
        if (state == State.SEEKING && seekers.contains(attacker.getUniqueId()) && playing(hider)) {
            found(hider, attacker);
        } else if (state == State.LOBBY) {
            attacker.sendActionBar(Component.text("Hit " + hider.getName() + "'s disguise.", NamedTextColor.AQUA));
        }
    }

    /**
     * Seeker with the sonar: rings at every hider. Hider with the rocket: taunt. Disguised player clicking a
     * block of the map: takes its look, once per cooldown.
     */
    // Not ignoreCancelled: a click in the air always arrives cancelled, and arenas are often
    // protected, while none of this changes anything in the world.
    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent event) {
        // PlayerInteractEvent fires twice per click: main hand, then off hand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (playing(player) && seekers.contains(player.getUniqueId()) && inHand.getType() == Kit.SONAR) {
            event.setCancelled(true);
            sonar(player, inHand);
            return;
        }
        Disguise disguise = disguises.get(player.getUniqueId());
        if (disguise == null) {
            return;
        }
        if (isHider(player) && inHand.getType() == Material.FIREWORK_ROCKET) {
            event.setCancelled(true);
            taunt(player, inHand);
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);

        int cooldownLeft = disguise.cooldownLeft();
        if (cooldownLeft > 0) {
            deny(player, "You can change again in " + (cooldownLeft + 19) / 20 + "s.");
            return;
        }
        // A display does not draw block entities (chests, signs, beds...): it would be invisible.
        if (!block.getType().isSolid() || block.getState(false) instanceof TileState) {
            deny(player, "You cannot copy this block.");
            return;
        }
        int cooldownTicks = plugin.getConfig().getInt("change-cooldown-seconds") * 20;
        BlockData look = block.getBlockData();
        // A copied waterlogged block would bring its water into the air cell of the fake block.
        if (look instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(false);
        }
        disguise.change(look, cooldownTicks);
        if (sessions.containsKey(player.getUniqueId())) {
            // Slot 1 shows the new look, with the vanilla cooldown sweep over it.
            ItemStack item = Kit.blockItem(look);
            player.getInventory().setItem(0, item);
            if (!item.isEmpty()) {
                player.setCooldown(item, cooldownTicks);
            }
        }
        player.sendActionBar(Component.text("You are now " + Kit.name(block.getType()) + ".", NamedTextColor.GREEN));
    }

    // ponytail: someone riding a horse or a boat moves through VehicleMoveEvent, which cannot be
    // cancelled: an outsider can ride into the dome. Dismount them there if it ever happens.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (state == State.HIDING && seekers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (crossesDome(player, event.getFrom(), event.getTo())) {
            // Back where they were, but still looking where they turned: cancelling would also
            // snap the camera back, every tick they keep walking into the wall.
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
            domeMessage(player);
        }
    }

    // Ender pearls, chorus fruit, portals. Teleports by a plugin, this one (to the arena and back)
    // or another (a /spawn), and admin commands such as /tp go through: one that leaves a player
    // of the game outside the dome is undone within a second, one to another world takes them
    // out of the game.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        TeleportCause cause = event.getCause();
        if (cause != TeleportCause.PLUGIN && cause != TeleportCause.COMMAND
                && crossesDome(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            domeMessage(event.getPlayer());
        }
    }

    /**
     * Once a second: a player of the game found outside the dome goes back to its centre. Only a
     * teleport can put them there (another plugin's /home, a /tp): walking out is stopped.
     */
    private void bringBackIntoDome() {
        if (dome == null) {
            return;
        }
        for (Player player : online(players)) {
            if (!dome.contains(player.getLocation()) && player.teleport(dome.center())) {
                player.sendActionBar(Component.text("Back in the dome: the game is played inside.", NamedTextColor.YELLOW));
            }
        }
    }

    /** Sets someone who is not in the game down just outside the dome. False if the teleport is refused. */
    private boolean sendOut(Player outsider) {
        outsider.leaveVehicle(); // a passenger cannot be teleported
        if (!outsider.teleport(dome.outside(outsider.getLocation()))) {
            return false;
        }
        outsider.sendActionBar(Component.text("A BlockHunt game is running in this dome.", NamedTextColor.AQUA));
        return true;
    }

    /** A player of the game leaving the dome, or anyone else entering it. */
    private boolean crossesDome(Player player, Location from, Location to) {
        if (dome == null || to == null) {
            return false;
        }
        boolean wasIn = dome.contains(from);
        boolean isIn = dome.contains(to);
        return playing(player) ? wasIn && !isIn : !wasIn && isIn;
    }

    private void domeMessage(Player player) {
        player.sendActionBar(playing(player)
                ? Component.text("The dome keeps you in the arena.", NamedTextColor.RED)
                : Component.text("A BlockHunt game is running in this dome.", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && playing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && playing(player)) {
            event.setCancelled(true);
        }
    }

    // An item taken from a frame or an armor stand would land in the kit inventory, which the
    // end-of-game restore overwrites: the item would be gone for good. Also covers the "at"
    // variant, which shares this handler list, and so armor stand manipulation that follows it.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (playing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // The kit stays where it is: no moving, dropping, swapping or picking up during a game.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && playing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (playing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (playing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && playing(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Only /bh during a game: another plugin's /home or /tpa would move a frozen seeker or cross
     * the dome, its /sell hand or /hat would take the copied block out of the game.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!playing(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1); // blockhunt:bh is /bh
        if (!label.equals("bh") && !label.equals("blockhunt")) {
            event.setCancelled(true);
            deny(event.getPlayer(), "Only /bh works during a game.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (Disguise disguise : disguises.values()) {
            disguise.hideFrom(event.getPlayer());
        }
        // Left over by a crash mid-game: the game is gone, the inventory comes back.
        stash.restore(event.getPlayer());
        // Logged out in the arena, back during a game: they would watch the hiders from inside.
        if (dome != null && dome.contains(event.getPlayer().getLocation())) {
            sendOut(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer());
        // Out of the lobby too: the lobby then only ever holds online players.
        if (players.remove(event.getPlayer().getUniqueId())) {
            removeSidebar(event.getPlayer());
            updateHud();
        }
    }

    /**
     * The disguise entities stay in the old world, and the game stays in the arena: a player who
     * changes world is out, seekers included. start() teleports in the lobby, so it is not one.
     */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        drop(event.getPlayer());
    }

    private void drop(Player player) {
        if (playing(player)) {
            leave(player);
        } else {
            undisguise(player);
        }
    }

    // --- Messages -------------------------------------------------------------

    private List<Player> online(Set<UUID> ids) {
        return ids.stream().map(plugin.getServer()::getPlayer).filter(Objects::nonNull).toList();
    }

    private void broadcast(Component message) {
        for (Player player : online(players)) {
            player.sendMessage(message);
        }
    }

    /** A chat word that runs {@code command} when clicked. */
    private static Component link(String label, String command, String hover) {
        return Component.text(label, NamedTextColor.GOLD, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    private static Component countdown(int ticks) {
        return ticks <= 0
                ? Component.text("ready", NamedTextColor.GREEN)
                : Component.text((ticks + 19) / 20 + "s", NamedTextColor.GRAY);
    }

    private static void deny(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}
