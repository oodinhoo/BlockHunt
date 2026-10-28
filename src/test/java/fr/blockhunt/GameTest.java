package fr.blockhunt;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A game must give every player their own inventory back, and take them back where they stood,
 * however it ends. Players start in a lobby world: going back is a world change.
 */
class GameTest {

    private ServerMock server;
    private WorldMock lobby;
    private Game game;
    private PlayerMock alice;
    private PlayerMock bob;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new SidebarServerMock());
        Plugin plugin = MockBukkit.createMockPlugin();
        lobby = server.addSimpleWorld("lobby");
        WorldMock arena = server.addSimpleWorld("arena");
        plugin.getConfig().set("spawn", new Location(arena, 0.5, 70, 0.5));
        plugin.getConfig().set("blocks", List.of("STONE"));
        game = new Game(plugin);
        server.getPluginManager().registerEvents(game, plugin);

        alice = player(Material.DIAMOND, 10);
        bob = player(Material.EMERALD, 20);
        game.join(alice);
        game.join(bob);
        game.start(server.getConsoleSender());
        assertEquals(GameMode.ADVENTURE, alice.getGameMode(), "the game started");
        assertEquals(arena, alice.getWorld(), "the game took them to the arena");
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

    /** With two players, either one leaving ends the game: both get everything back. */
    @Test
    void leavingEndsTheGameAndGivesEveryoneTheirInventoryAndPlaceBack() {
        game.leave(alice);

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    /** A seeker sent to another world (a /spawn from another plugin) leaves the game, which ends. */
    @Test
    void seekerChangingWorldLeavesTheGame() {
        PlayerMock seeker = alice.getInventory().contains(Kit.SONAR) ? alice : bob;
        seeker.teleport(server.addSimpleWorld("elsewhere").getSpawnLocation());

        assertRestored(alice, Material.DIAMOND, 10);
        assertRestored(bob, Material.EMERALD, 20);
    }

    private void assertRestored(PlayerMock player, Material own, double x) {
        assertEquals(ItemStack.of(own, 3), player.getInventory().getItem(5), player.getName());
        assertEquals(GameMode.SURVIVAL, player.getGameMode(), player.getName());
        assertEquals(new Location(lobby, x, 70, 0), player.getLocation(), player.getName());
    }
}
