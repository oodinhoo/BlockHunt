package fr.blockhunt;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one path that must never lose data: a player's own inventory around a game. */
class StashTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void restoreGivesBackInventoryAndGameMode() {
        Stash stash = new Stash(MockBukkit.createMockPlugin());
        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        // Slots 0 to 2 stay empty: getContents() returns nulls there.
        player.getInventory().setItem(3, ItemStack.of(Material.DIAMOND, 5));
        player.getInventory().setItemInOffHand(ItemStack.of(Material.SHIELD));

        stash.save(player);
        assertTrue(player.getInventory().isEmpty());
        assertEquals(GameMode.ADVENTURE, player.getGameMode());

        player.getInventory().setItem(0, ItemStack.of(Material.COMPASS)); // the kit
        stash.restore(player);
        assertEquals(ItemStack.of(Material.DIAMOND, 5), player.getInventory().getItem(3));
        assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertTrue(player.getInventory().getItem(0) == null || player.getInventory().getItem(0).isEmpty());
        assertEquals(GameMode.CREATIVE, player.getGameMode());

        // Nothing left in the PDC: a second restore (the next join) changes nothing.
        player.setGameMode(GameMode.SURVIVAL);
        stash.restore(player);
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        assertEquals(ItemStack.of(Material.DIAMOND, 5), player.getInventory().getItem(3));
    }

    /** Closing puts crafting grid and cursor items back in the inventory, before it is saved. */
    @Test
    void saveClosesTheOpenInventoryFirst() {
        Stash stash = new Stash(MockBukkit.createMockPlugin());
        PlayerMock player = server.addPlayer();
        player.openInventory(server.createInventory(null, 9));

        stash.save(player);

        // Back to the player's own view: what a closed inventory shows.
        assertEquals(InventoryType.CRAFTING, player.getOpenInventory().getType());
    }
}
