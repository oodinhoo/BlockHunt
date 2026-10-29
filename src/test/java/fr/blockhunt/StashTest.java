package fr.blockhunt;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        stash.save(player, player.getLocation());
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

    /**
     * Stored data it cannot read: the rest is still given back, and the unreadable part is set
     * aside for an admin rather than failing every join, or being overwritten by the next game.
     */
    @Test
    void unreadableDataIsSetAsideAndTheRestRestored() {
        Plugin plugin = MockBukkit.createMockPlugin();
        Stash stash = new Stash(plugin);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(3, ItemStack.of(Material.DIAMOND, 5));
        stash.save(player, player.getLocation());
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, "gamemode"), PersistentDataType.STRING, "FLYING");
        pdc.set(new NamespacedKey(plugin, "position"), PersistentDataType.STRING, "not a position");

        stash.restore(player);

        assertEquals(ItemStack.of(Material.DIAMOND, 5), player.getInventory().getItem(3), "the readable part");
        assertEquals("FLYING", pdc.get(new NamespacedKey(plugin, "gamemode-unreadable"), PersistentDataType.STRING));
        assertEquals("not a position",
                pdc.get(new NamespacedKey(plugin, "position-unreadable"), PersistentDataType.STRING));
        assertFalse(pdc.has(new NamespacedKey(plugin, "gamemode")), "set aside, not read again on every join");
        assertFalse(pdc.has(new NamespacedKey(plugin, "position")));

        stash.save(player, player.getLocation());
        stash.restore(player);
        assertEquals("FLYING", pdc.get(new NamespacedKey(plugin, "gamemode-unreadable"), PersistentDataType.STRING),
                "the next game does not overwrite it");
    }

    /** The same part unreadable after two games: the first one set aside is not lost to the second. */
    @Test
    void aSecondUnreadablePartIsKeptNextToTheFirst() {
        Plugin plugin = MockBukkit.createMockPlugin();
        Stash stash = new Stash(plugin);
        PlayerMock player = server.addPlayer();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        NamespacedKey mode = new NamespacedKey(plugin, "gamemode");

        stash.save(player, player.getLocation());
        pdc.set(mode, PersistentDataType.STRING, "FLYING");
        stash.restore(player);
        stash.save(player, player.getLocation());
        pdc.set(mode, PersistentDataType.STRING, "SWIMMING");
        stash.restore(player);

        assertEquals("FLYING", pdc.get(new NamespacedKey(plugin, "gamemode-unreadable"), PersistentDataType.STRING));
        assertEquals("SWIMMING",
                pdc.get(new NamespacedKey(plugin, "gamemode-unreadable-2"), PersistentDataType.STRING));
        assertTrue(player.nextComponentMessage() != null, "the player is told");
    }

    @Test
    void anUnreadableInventoryIsSetAside() {
        Plugin plugin = MockBukkit.createMockPlugin();
        Stash stash = new Stash(plugin);
        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        stash.save(player, player.getLocation());
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] garbage = {1, 2, 3};
        pdc.set(new NamespacedKey(plugin, "inventory"), PersistentDataType.BYTE_ARRAY, garbage);

        stash.restore(player);

        assertEquals(GameMode.CREATIVE, player.getGameMode(), "the readable part");
        assertArrayEquals(garbage, pdc.get(new NamespacedKey(plugin, "inventory-unreadable"),
                PersistentDataType.BYTE_ARRAY));
        assertFalse(pdc.has(new NamespacedKey(plugin, "inventory")));
    }

    /** Closing puts crafting grid and cursor items back in the inventory, before it is saved. */
    @Test
    void saveClosesTheOpenInventoryFirst() {
        Stash stash = new Stash(MockBukkit.createMockPlugin());
        PlayerMock player = server.addPlayer();
        player.openInventory(server.createInventory(null, 9));

        stash.save(player, player.getLocation());

        // Back to the player's own view: what a closed inventory shows.
        assertEquals(InventoryType.CRAFTING, player.getOpenInventory().getType());
    }

    /** Back after a crash, but the world they stood in was deleted: the main world's spawn. */
    @Test
    void restoreToADeletedWorldGoesToTheMainSpawn() {
        Stash stash = new Stash(MockBukkit.createMockPlugin());
        PlayerMock player = server.addPlayer();
        // Never added to the server: as gone as a deleted world.
        stash.save(player, new Location(new WorldMock(), 10, 70, 10));
        player.setLocation(new Location(player.getWorld(), 100, 70, 100));

        stash.restore(player);

        assertEquals(server.getWorlds().getFirst().getSpawnLocation(), player.getLocation());
    }

    /**
     * A refused teleport back (a mount, another plugin) keeps the position, and the next game
     * must not replace it with the arena: the player still goes home in the end.
     */
    @Test
    void refusedTeleportBackKeepsWhereThePlayerCameFrom() {
        Plugin plugin = MockBukkit.createMockPlugin();
        Stash stash = new Stash(plugin);
        PlayerMock player = server.addPlayer();
        Location home = new Location(player.getWorld(), 10, 70, 10);
        Location arena = new Location(player.getWorld(), 100, 70, 100);
        RefuseTeleports refuse = new RefuseTeleports();
        server.getPluginManager().registerEvents(refuse, plugin);

        stash.save(player, home);
        player.setLocation(arena);
        stash.restore(player);
        assertEquals(arena, player.getLocation(), "refused: still in the arena");

        stash.save(player, arena); // the next game, started from the arena
        refuse.on = false;
        stash.restore(player);
        assertEquals(home, player.getLocation());
    }

    /** Cancels every teleport while {@code on}. */
    public static final class RefuseTeleports implements Listener {
        boolean on = true;

        @EventHandler
        public void onTeleport(PlayerTeleportEvent event) {
            event.setCancelled(on);
        }
    }
}
