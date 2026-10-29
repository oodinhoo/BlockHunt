package fr.blockhunt;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A game must give every player their own inventory back, and take them back where they stood,
 * however it ends. Players start in a lobby world: going back is a world change.
 */
class GameTest {

    /** Ticks from the lobby filling up to the game start, for a 1 second countdown: set, count, start. */
    private static final int ONE_SECOND_COUNTDOWN = 1 + 20 + 1;

    private ServerMock server;
    private Plugin plugin;
    private WorldMock lobby;
    private WorldMock arena;
    private Game game;
    private PlayerMock alice;
    private PlayerMock bob;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new SidebarServerMock());
        plugin = MockBukkit.createMockPlugin();
        lobby = server.addSimpleWorld("lobby");
        arena = server.addSimpleWorld("arena");
        plugin.getConfig().set("spawn", new Location(arena, 0.5, 70, 0.5));
        plugin.getConfig().set("blocks", List.of("STONE"));
        plugin.getConfig().set("countdown-seconds", 1);
        game = new Game(plugin);
        server.getPluginManager().registerEvents(game, plugin);

        alice = player(Material.DIAMOND, 10);
        bob = player(Material.EMERALD, 20);
        game.join(alice);
        game.join(bob);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** In the lobby world at x, with 3 of their own item in slot 5. */
    private PlayerMock player(Material own, double x) {
        PlayerMock player = new TitlePlayerMock(server, "player" + server.getOnlinePlayers().size());
        server.addPlayer(player);
        player.setLocation(new Location(lobby, x, 70, 0));
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(5, ItemStack.of(own, 3));
        return player;
    }

    /** /bh start, before any tick: no countdown involved. */
    private void start() {
        game.start(server.getConsoleSender());
        assertStarted();
    }

    private void assertStarted() {
        assertEquals(GameMode.ADVENTURE, alice.getGameMode(), "the game started");
        assertEquals(arena, alice.getWorld(), "the game took them to the arena");
    }

    /** With two players, either one leaving ends the game: both get everything back. */
    @Test
    void leavingEndsTheGameAndGivesEveryoneTheirInventoryAndPlaceBack() {
        start();
        game.leave(alice);

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    /** A seeker sent to another world (a /spawn from another plugin) leaves the game, which ends. */
    @Test
    void seekerChangingWorldLeavesTheGame() {
        start();
        PlayerMock seeker = alice.getInventory().contains(Kit.SONAR) ? alice : bob;
        seeker.teleport(server.addSimpleWorld("elsewhere").getSpawnLocation());

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    // --- Finding hiders and ending the game ----------------------------------------------------

    /** /bh start with hide-seconds 1: the seekers are released, and one tick of seeking is gone. */
    private static final int RELEASE = 21;

    private PlayerMock seeker() {
        return alice.getInventory().contains(Kit.SONAR) ? alice : bob;
    }

    /** A hit on the target as the client sends it: on a hider's hitbox, on a seeker themselves. */
    private PrePlayerAttackEntityEvent hit(PlayerMock attacker, PlayerMock target) {
        Entity attacked = target.getInventory().contains(Kit.SONAR) ? target
                : arena.getEntitiesByClass(Interaction.class).stream()
                        .min(Comparator.comparingDouble(box -> box.getLocation().distanceSquared(target.getLocation())))
                        .orElseThrow();
        PrePlayerAttackEntityEvent event = new PrePlayerAttackEntityEvent(attacker, attacked, false);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Every title the player was shown since the last check. */
    private static List<String> titles(PlayerMock player) {
        List<String> shown = new ArrayList<>();
        TitlePlayerMock titled = (TitlePlayerMock) player;
        for (String title = titled.nextAdventureTitle(); title != null; title = titled.nextAdventureTitle()) {
            shown.add(title);
        }
        return shown;
    }

    /** Three players, two of them hiders standing apart: a find leaves one hider in the game. */
    private PlayerMock joinThird() {
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        game.join(carol);
        return carol;
    }

    @Test
    void aFoundHiderBecomesASeeker() {
        plugin.getConfig().set("hide-seconds", 1);
        PlayerMock carol = joinThird();
        start();
        List<PlayerMock> hiders = new ArrayList<>(List.of(alice, bob, carol));
        PlayerMock seeker = hiders.stream().filter(p -> p.getInventory().contains(Kit.SONAR)).findFirst().orElseThrow();
        hiders.remove(seeker);
        hiders.get(0).setLocation(inArena(3.5));
        hiders.get(1).setLocation(inArena(-3.5));
        server.getScheduler().performTicks(RELEASE);

        hit(seeker, hiders.get(0));

        assertTrue(hiders.get(0).getInventory().contains(Kit.SONAR), "the found hider got the seeker kit");
        assertFalse(hiders.get(1).getInventory().contains(Kit.SONAR), "the other hider is still hiding");
        assertEquals(GameMode.ADVENTURE, hiders.get(1).getGameMode(), "the game goes on");
    }

    @Test
    void findingTheLastHiderIsASeekersWin() {
        plugin.getConfig().set("hide-seconds", 1);
        start();
        server.getScheduler().performTicks(RELEASE);
        titles(alice);

        hit(seeker(), hider());

        assertTrue(titles(alice).contains("Seekers win!"));
        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    @Test
    void theClockRunningOutIsAHidersWin() {
        plugin.getConfig().set("hide-seconds", 1);
        plugin.getConfig().set("seek-seconds", 1);
        start();
        titles(alice);

        server.getScheduler().performTicks(RELEASE + 19); // the last of the 20 seeking ticks

        assertTrue(titles(alice).contains("Hiders win!"));
        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    /** The last hider found on the last tick of the clock: one end, the seekers' win. */
    @Test
    void aFindOnTheLastTickEndsTheGameOnce() {
        plugin.getConfig().set("hide-seconds", 1);
        plugin.getConfig().set("seek-seconds", 1);
        start();
        server.getScheduler().performTicks(RELEASE + 18); // one tick left
        titles(alice);

        hit(seeker(), hider());
        server.getScheduler().performTicks(1);

        List<String> shown = titles(alice);
        assertTrue(shown.contains("Seekers win!"));
        assertFalse(shown.contains("Hiders win!"), "the clock must not end the game a second time");
    }

    @Test
    void onlyASeekerHittingAHiderFindsThem() {
        plugin.getConfig().set("hide-seconds", 1);
        plugin.getConfig().set("seekers", 2);
        PlayerMock carol = joinThird();
        start();
        server.getScheduler().performTicks(RELEASE);
        List<PlayerMock> seekers = new ArrayList<>(List.of(alice, bob, carol));
        seekers.removeIf(p -> !p.getInventory().contains(Kit.SONAR));
        PlayerMock hider = List.of(alice, bob, carol).stream().filter(p -> !seekers.contains(p)).findFirst().orElseThrow();

        assertTrue(hit(seekers.get(0), seekers.get(1)).isCancelled(), "a seeker hitting a seeker does nothing");
        assertTrue(hit(hider, seekers.get(0)).isCancelled(), "a hider hitting a seeker does nothing");
        assertFalse(hider.getInventory().contains(Kit.SONAR), "still a hider");
        assertEquals(GameMode.ADVENTURE, hider.getGameMode(), "the game goes on");
    }

    /** The plugin disabled mid-game (a server stop, a reload) gives everyone everything back. */
    @Test
    void stoppingThePluginMidGameRestoresEveryone() {
        start();

        game.stop();

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    /** Two players joined, the default minimum: the game starts on its own after the countdown. */
    @Test
    void countdownStartsTheGameOnceEnoughPlayersJoined() {
        server.getScheduler().performTicks(ONE_SECOND_COUNTDOWN - 1);
        assertEquals(GameMode.SURVIVAL, alice.getGameMode(), "not before the countdown ends");

        server.getScheduler().performTicks(1);
        assertStarted();
    }

    /**
     * A player leaving under the minimum stops the countdown. With 3 required, the 2 left are
     * enough for /bh start: only the stopped countdown keeps the game from starting.
     */
    @Test
    void leavingDuringTheCountdownStopsIt() {
        plugin.getConfig().set("min-players", 3);
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        game.join(carol);
        server.getScheduler().performTicks(10);

        game.leave(carol);
        server.getScheduler().performTicks(ONE_SECOND_COUNTDOWN * 2);

        assertRestored(alice, Material.DIAMOND, 10);
    }

    /** min-players above the lobby size: the lobby keeps waiting. */
    @Test
    void noCountdownUnderMinPlayers() {
        plugin.getConfig().set("min-players", 3);
        server.getScheduler().performTicks(ONE_SECOND_COUNTDOWN * 2);

        assertRestored(alice, Material.DIAMOND, 10);
    }

    /** A countdown whose start fails tells the lobby why, not only the console. */
    @Test
    void failedAutoStartTellsThePlayersWhy() {
        plugin.getConfig().set("blocks", List.of("NOT_A_BLOCK"));
        server.getScheduler().performTicks(ONE_SECOND_COUNTDOWN);

        assertTrue(messages(alice).contains("No valid block in config.yml."));
    }

    /** Who did not join gets a [Join] that runs /bh join; who is in the lobby gets no invite. */
    @Test
    void joiningInvitesTheOthersWithAClickToJoin() {
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        game.join(player(Material.IRON_INGOT, 40));

        assertTrue(clicks(carol).contains(ClickEvent.runCommand("/bh join")));
        assertFalse(clicks(alice).contains(ClickEvent.runCommand("/bh join")));
    }

    /** The players stay in the lobby after a game: a [Leave] that runs /bh leave gets them out. */
    @Test
    void endOfGameOffersAClickToLeave() {
        start();
        game.leave(alice);

        assertTrue(clicks(bob).contains(ClickEvent.runCommand("/bh leave")));
    }

    /** The closer to a seeker, the more time a taunt takes off: all of it right next to one, none at the range. */
    @Test
    void tauntTakesMoreTimeTheCloserTheSeeker() {
        assertEquals(30, Game.tauntSeconds(0, 30, 32));
        assertEquals(15, Game.tauntSeconds(16, 30, 32));
        assertEquals(0, Game.tauntSeconds(32, 30, 32));
        assertEquals(0, Game.tauntSeconds(100, 30, 32));
        assertEquals(0, Game.tauntSeconds(Double.MAX_VALUE, 30, 32), "no seeker to measure against");
        assertEquals(0, Game.tauntSeconds(5, 30, 0), "range 0: taunts off");
        assertEquals(0, Game.tauntSeconds(5, 30, -10), "a negative range must not give more than max");
    }

    /**
     * A taunt from too far is refused and kept: with a single taunt, the one from close by still
     * works, and says how many seconds it took off. Seekers stay at the arena spawn (0.5, 70, 0.5).
     */
    @Test
    void tauntFromTooFarIsRefusedAndKept() {
        plugin.getConfig().set("hide-seconds", 1);
        plugin.getConfig().set("taunts", 1);
        start();
        server.getScheduler().performTicks(21); // seekers released
        PlayerMock hider = alice.getInventory().contains(Kit.SONAR) ? bob : alice;
        hider.getInventory().setHeldItemSlot(1); // the rocket
        messages(hider);

        hider.setLocation(new Location(arena, 100.5, 70, 0.5));
        taunt(hider);
        assertTrue(actionBars(hider).contains("Too far: get closer to a seeker."));
        assertTrue(messages(hider).stream().noneMatch(message -> message.contains("taunted")));

        hider.setLocation(new Location(arena, 4.5, 70, 0.5)); // 4 blocks: 30 * (1 - 4 / 32) = 26
        taunt(hider);
        assertTrue(messages(hider).stream().anyMatch(message -> message.contains("taunted the seekers: -26s")));
    }

    /**
     * Near the end of the clock, a taunt takes off what is left but the last second, and says so:
     * 10 s of seeking, 9.95 s left after one tick, so 8 whole seconds above the last one.
     */
    @Test
    void tauntNearTheEndTakesOnlyWhatIsLeftAndSaysSo() {
        plugin.getConfig().set("hide-seconds", 1);
        plugin.getConfig().set("seek-seconds", 10);
        start();
        server.getScheduler().performTicks(21); // seekers released, 199 ticks left
        PlayerMock hider = alice.getInventory().contains(Kit.SONAR) ? bob : alice;
        hider.getInventory().setHeldItemSlot(1);
        hider.setLocation(new Location(arena, 4.5, 70, 0.5)); // worth 26 s
        messages(hider);

        taunt(hider);
        assertTrue(messages(hider).stream().anyMatch(message -> message.contains("taunted the seekers: -8s")));
    }

    private void taunt(PlayerMock hider) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(hider, Action.RIGHT_CLICK_AIR,
                hider.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    // --- The arena dome: a 10 block sphere around the spawn (0.5, 70, 0.5) -------------------

    private static final double DOME = 10;

    private Location inArena(double x) {
        return new Location(arena, x, 70, 0.5);
    }

    private static boolean insideDome(Location location) {
        return location.distance(new Location(location.getWorld(), 0.5, 70, 0.5)) <= DOME;
    }

    /**
     * Moves the player as a Paper server would: to the event's destination, as listeners left it,
     * or nowhere if they cancelled it. MockBukkit's own move ignores a destination changed by setTo.
     */
    private Location move(PlayerMock player, Location to) {
        PlayerMoveEvent event = new PlayerMoveEvent(player, player.getLocation(), to);
        server.getPluginManager().callEvent(event);
        Location end = event.isCancelled() ? event.getFrom() : event.getTo();
        player.setLocation(end);
        return end;
    }

    private PlayerMock hider() {
        return alice.getInventory().contains(Kit.SONAR) ? bob : alice;
    }

    /** A player of the game walks into the wall of the dome: held back inside. */
    @Test
    void playersCannotWalkOutOfTheDome() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock hider = hider();
        hider.setLocation(inArena(9.5));

        assertTrue(insideDome(move(hider, inArena(11.5))));
    }

    /** An ender pearl does not get a player of the game out either (chorus fruit: same check). */
    @Test
    void playersCannotPearlOutOfTheDome() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock hider = hider();

        assertFalse(hider.teleport(inArena(30), PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        assertTrue(insideDome(hider.getLocation()));
    }

    /** Someone who is not in the game cannot walk in while it runs. */
    @Test
    void outsidersCannotEnterTheDomeDuringAGame() {
        plugin.getConfig().set("arena-radius", DOME);
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        carol.setLocation(inArena(11.5));
        start();

        assertFalse(insideDome(move(carol, inArena(9.5))));
    }

    /** Someone who is not in the game and stands in the arena at the start is set down outside. */
    @Test
    void outsidersInsideTheDomeAreSentOutAtTheStart() {
        plugin.getConfig().set("arena-radius", DOME);
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        carol.setLocation(inArena(3));
        start();

        assertEquals(arena, carol.getWorld());
        assertFalse(insideDome(carol.getLocation()));
    }

    /** Between games the dome is gone: the arena is free to walk in, and to build. */
    @Test
    void theDomeIsGoneOnceTheGameEnds() {
        plugin.getConfig().set("arena-radius", DOME);
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        carol.setLocation(inArena(11.5));
        start();
        game.leave(alice);

        assertTrue(insideDome(move(carol, inArena(9.5))));
    }

    /** Only the crossing is stopped: a player of the game outside walks back in, an outsider walks out. */
    @Test
    void theDomeOnlyStopsTheForbiddenWay() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock hider = hider();
        hider.setLocation(inArena(11.5));
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        carol.setLocation(inArena(9.5));

        assertTrue(insideDome(move(hider, inArena(9.5))));
        assertFalse(insideDome(move(carol, inArena(11.5))));
    }

    /** Sent out of the dome by another plugin's teleport: back in within a second. */
    @Test
    void playersPutOutsideTheDomeAreBroughtBack() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock hider = hider();
        hider.setLocation(inArena(20));

        server.getScheduler().performTicks(20);
        assertTrue(insideDome(hider.getLocation()));
    }

    /** Someone who logs back in inside the dome during a game is set down outside. */
    @Test
    void outsidersLoggingInInsideTheDomeAreSentOut() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock carol = player(Material.GOLD_INGOT, 30);
        carol.setLocation(inArena(3));

        server.getPluginManager().callEvent(new PlayerJoinEvent(carol, Component.empty()));
        assertFalse(insideDome(carol.getLocation()));
    }

    /** The wall is drawn twice a second for a player near it, and the game carries on. */
    @Test
    void drawingTheWallKeepsTheGameRunning() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        PlayerMock hider = hider();
        hider.setLocation(inArena(9.5));

        server.getScheduler().performTicks(40);
        assertEquals(GameMode.ADVENTURE, hider.getGameMode());
        assertTrue(insideDome(hider.getLocation()));
    }

    /** With the dome on, the end of the game still takes everyone back where they stood. */
    @Test
    void theEndOfTheGameTakesEveryoneBackThroughTheDome() {
        plugin.getConfig().set("arena-radius", DOME);
        start();
        game.leave(alice);

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    /** arena-radius 0: no dome at all. */
    @Test
    void radiusZeroTurnsTheDomeOff() {
        plugin.getConfig().set("arena-radius", 0);
        start();
        PlayerMock hider = hider();
        hider.setLocation(inArena(9.5));

        assertFalse(insideDome(move(hider, inArena(11.5))));
    }

    /** The click actions in every message the player got since the last check. */
    private static List<ClickEvent<?>> clicks(PlayerMock player) {
        List<ClickEvent<?>> clicks = new ArrayList<>();
        for (Component message = player.nextComponentMessage(); message != null; message = player.nextComponentMessage()) {
            collectClicks(message, clicks);
        }
        return clicks;
    }

    private static void collectClicks(Component component, List<ClickEvent<?>> clicks) {
        if (component.clickEvent() != null) {
            clicks.add(component.clickEvent());
        }
        component.children().forEach(child -> collectClicks(child, clicks));
    }

    private static List<String> actionBars(PlayerMock player) {
        List<String> shown = new ArrayList<>();
        for (Component bar = player.nextActionBar(); bar != null; bar = player.nextActionBar()) {
            shown.add(PlainTextComponentSerializer.plainText().serialize(bar));
        }
        return shown;
    }

    private static List<String> messages(PlayerMock player) {
        List<String> said = new ArrayList<>();
        for (Component message = player.nextComponentMessage(); message != null; message = player.nextComponentMessage()) {
            said.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return said;
    }

    private void assertRestored(PlayerMock player, Material own, double x) {
        assertEquals(ItemStack.of(own, 3), player.getInventory().getItem(5), player.getName());
        assertEquals(GameMode.SURVIVAL, player.getGameMode(), player.getName());
        assertEquals(new Location(lobby, x, 70, 0), player.getLocation(), player.getName());
    }
}
