package fr.blockhunt;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single game of the server: a lobby of players, then a hiding phase (seekers blinded and
 * frozen), then a seeking phase. A hider found by a seeker becomes a seeker. Seekers win when
 * no hider is left, hiders win when the time runs out.
 */
// ponytail: one arena per server. Make Game per-arena, keyed by name, if a server ever needs two.
final class Game implements Listener {

    enum State { LOBBY, HIDING, SEEKING }

    private final BlockHuntPlugin plugin;

    /** Everyone who joined, in the lobby or in the running game. */
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> seekers = new HashSet<>();
    /** Hiders of the running game, plus anyone disguised with /bh disguise for testing. */
    private final Map<UUID, Disguise> disguises = new HashMap<>();

    private final BossBar bar = BossBar.bossBar(Component.empty(), 1, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

    private State state = State.LOBBY;
    private int ticksLeft;
    private int phaseTicks;

    Game(BlockHuntPlugin plugin) {
        this.plugin = plugin;
        // Always running: test disguises need ticking outside a game too.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    // --- Commands -------------------------------------------------------------

    void join(Player player) {
        if (state != State.LOBBY) {
            deny(player, "A game is running. Wait for the next one.");
            return;
        }
        if (players.add(player.getUniqueId())) {
            broadcast(Component.text(player.getName() + " joined (" + players.size() + " players).", NamedTextColor.AQUA));
        }
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
            checkEnd();
        }
    }

    void start(CommandSender sender) {
        if (state != State.LOBBY) {
            sender.sendMessage(Component.text("A game is already running.", NamedTextColor.RED));
            return;
        }
        List<Player> online = new ArrayList<>(online(players));
        if (online.size() < 2) {
            sender.sendMessage(Component.text("At least 2 players must /bh join.", NamedTextColor.RED));
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

        // Teleport first, while still in the lobby: a world change drops test disguises,
        // it must not count as leaving the game.
        for (Player player : online) {
            player.teleport(spawn);
            undisguise(player);
        }

        Collections.shuffle(online);
        int seekerCount = Math.clamp(plugin.getConfig().getInt("seekers"), 1, online.size() - 1);
        int hideTicks = plugin.getConfig().getInt("hide-seconds") * 20;
        for (int i = 0; i < online.size(); i++) {
            Player player = online.get(i);
            player.showBossBar(bar);
            if (i < seekerCount) {
                seekers.add(player.getUniqueId());
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, hideTicks, 0, false, false));
                player.showTitle(Title.title(Component.text("Seeker", NamedTextColor.RED),
                        Component.text("Wait while they hide...")));
            } else {
                Material block = blocks.get(ThreadLocalRandom.current().nextInt(blocks.size()));
                disguise(player, block.createBlockData());
                player.showTitle(Title.title(Component.text("Hider", NamedTextColor.GREEN),
                        Component.text("You are " + block.getKey().getKey() + ". Hide!")));
            }
        }
        // Players who joined but are offline do not play.
        players.retainAll(online.stream().map(Player::getUniqueId).toList());
        phase(State.HIDING, hideTicks);
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
    }

    // --- Game loop ------------------------------------------------------------

    private void tick() {
        int solidifyTicks = plugin.getConfig().getInt("solidify-seconds") * 20;
        disguises.values().forEach(disguise -> disguise.tick(solidifyTicks));

        if (state == State.LOBBY) {
            return;
        }
        ticksLeft--;
        bar.progress(Math.clamp((float) ticksLeft / phaseTicks, 0f, 1f));
        if (ticksLeft % 20 == 0) {
            updateBar();
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
        int seconds = Math.max(0, ticksLeft / 20);
        String time = seconds / 60 + ":" + String.format("%02d", seconds % 60);
        String label = state == State.HIDING ? "Hiding" : "Seeking";
        bar.name(Component.text(label + " " + time + " - " + hidersLeft() + " hiders left"));
    }

    private void found(Player hider, Player seeker) {
        undisguise(hider);
        seekers.add(hider.getUniqueId());
        hider.showTitle(Title.title(Component.text("Found!", NamedTextColor.RED),
                Component.text("You are a seeker now.")));
        broadcast(Component.text(seeker.getName() + " found " + hider.getName() + "!", NamedTextColor.GOLD));
        for (Player player : online(players)) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        updateBar();
        checkEnd();
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
        for (Player player : online(players)) {
            reset(player);
            player.showTitle(Title.title(result, Component.empty()));
        }
        seekers.clear();
        state = State.LOBBY;
    }

    private void reset(Player player) {
        undisguise(player);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.hideBossBar(bar);
    }

    private int hidersLeft() {
        return players.size() - seekers.size();
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

    private boolean playing(Player player) {
        return state != State.LOBBY && players.contains(player.getUniqueId());
    }

    // --- Events ---------------------------------------------------------------

    // HIGH, after the protection plugins: a hit they deny is already cancelled and ignored.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Disguise target = null;
        for (Disguise disguise : disguises.values()) {
            if (disguise.owns(event.getAttacked())) {
                target = disguise;
                break;
            }
        }
        if (target == null) {
            return;
        }
        event.setCancelled(true);

        Player attacker = event.getPlayer();
        Player hider = target.player();
        if (state == State.SEEKING && seekers.contains(attacker.getUniqueId()) && playing(hider)) {
            found(hider, attacker);
        } else if (state == State.LOBBY) {
            attacker.sendActionBar(Component.text("Hit " + hider.getName() + "'s disguise.", NamedTextColor.AQUA));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (state == State.HIDING && event.hasChangedPosition() && seekers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (playing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (playing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (Disguise disguise : disguises.values()) {
            disguise.hideFrom(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer());
    }

    /** The disguise entities stay in the old world: a disguised player who changes world is out. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (disguises.containsKey(event.getPlayer().getUniqueId())) {
            drop(event.getPlayer());
        }
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

    private static void deny(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}
