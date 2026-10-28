package fr.blockhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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
        PlayerMock player = server.addPlayer();
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
        assertTrue(messages(hider).stream().noneMatch(message -> message.contains("taunted")));

        hider.setLocation(new Location(arena, 4.5, 70, 0.5)); // 4 blocks: 30 * (1 - 4 / 32) = 26
        taunt(hider);
        assertTrue(messages(hider).stream().anyMatch(message -> message.contains("taunted the seekers: -26s")));
    }

    private void taunt(PlayerMock hider) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(hider, Action.RIGHT_CLICK_AIR,
                hider.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
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
