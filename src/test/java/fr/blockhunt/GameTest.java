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

/** A game must give every player their own inventory back, however it ends. */
class GameTest {

    private ServerMock server;
    private Game game;
    private PlayerMock alice;
    private PlayerMock bob;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new SidebarServerMock());
        Plugin plugin = MockBukkit.createMockPlugin();
        WorldMock arena = server.addSimpleWorld("arena");
        plugin.getConfig().set("spawn", new Location(arena, 0.5, 70, 0.5));
        plugin.getConfig().set("blocks", List.of("STONE"));
        game = new Game(plugin);
        server.getPluginManager().registerEvents(game, plugin);

        alice = player(Material.DIAMOND);
        bob = player(Material.EMERALD);
        game.join(alice);
        game.join(bob);
        game.start(server.getConsoleSender());
        assertEquals(GameMode.ADVENTURE, alice.getGameMode(), "the game started");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player(Material own) {
        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(5, ItemStack.of(own, 3));
        return player;
    }

    /** With two players, either one leaving ends the game: both get everything back. */
    @Test
    void leavingEndsTheGameAndGivesEveryoneTheirInventoryBack() {
        game.leave(alice);

        assertRestored(alice, Material.DIAMOND);
        assertRestored(bob, Material.EMERALD);
    }

    /** A seeker sent to another world (a /spawn from another plugin) leaves the game, which ends. */
    @Test
    void seekerChangingWorldLeavesTheGame() {
        PlayerMock seeker = alice.getInventory().contains(Kit.SONAR) ? alice : bob;
        seeker.teleport(server.addSimpleWorld("elsewhere").getSpawnLocation());

        assertRestored(alice, Material.DIAMOND);
        assertRestored(bob, Material.EMERALD);
    }

    private static void assertRestored(PlayerMock player, Material own) {
        assertEquals(ItemStack.of(own, 3), player.getInventory().getItem(5), player.getName());
        assertEquals(GameMode.SURVIVAL, player.getGameMode(), player.getName());
    }
}
