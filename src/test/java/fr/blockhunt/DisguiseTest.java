package fr.blockhunt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disguise rules. Turning solid itself is not run here: MockBukkit does not implement
 * Entity#setVisibleByDefault. The rule deciding where, and the stillness leading to it, are.
 */
class DisguiseTest {

    private ServerMock server;
    private Plugin plugin;
    private WorldMock world;
    private PlayerMock hider;
    private PlayerMock seeker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("arena");
        hider = server.addPlayer();
        seeker = server.addPlayer();
        hider.setLocation(new Location(world, 0.5, 70, 0.5));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** The cell (0, 70, 0), holding {@code inside}, above {@code below}. */
    private Block cell(Material inside, Material below) {
        world.getBlockAt(0, 69, 0).setType(below);
        Block cell = world.getBlockAt(0, 70, 0);
        cell.setType(inside);
        return cell;
    }

    @Test
    void solidifiesOnlyInAnEmptyCellOverSolidGround() {
        assertTrue(Disguise.canSolidifyAt(cell(Material.AIR, Material.STONE)));
        assertFalse(Disguise.canSolidifyAt(cell(Material.AIR, Material.AIR)), "over the void");
        assertFalse(Disguise.canSolidifyAt(cell(Material.WATER, Material.STONE)), "in water");
        assertFalse(Disguise.canSolidifyAt(cell(Material.SHORT_GRASS, Material.STONE)), "in grass");
    }

    @Test
    void blocksDrawnAsNothingAreInvisible() {
        for (Material invisible : List.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.BARRIER,
                Material.LIGHT, Material.STRUCTURE_VOID, Material.MOVING_PISTON)) {
            assertTrue(Disguise.isInvisible(invisible), invisible.name());
        }
        assertFalse(Disguise.isInvisible(Material.STONE));
        assertFalse(Disguise.isInvisible(Material.TORCH), "not solid, but drawn");
        assertFalse(Disguise.isInvisible(Material.GLASS), "see-through, but drawn");
    }

    @Test
    void hiderIsHiddenFromOthersUntilRemoved() {
        Disguise disguise = new Disguise(plugin, hider, Material.OAK_PLANKS.createBlockData());
        assertFalse(seeker.canSee(hider));

        disguise.remove();
        assertTrue(seeker.canSee(hider));
    }

    @Test
    void standingStillFillsTheProgressAndMovingResetsIt() {
        int solidifyTicks = 100; // never reached here
        Disguise disguise = new Disguise(plugin, hider, Material.OAK_PLANKS.createBlockData());

        disguise.tick(solidifyTicks); // the first tick only records the position
        for (int i = 0; i < 5; i++) {
            disguise.tick(solidifyTicks);
        }
        assertEquals(0.05f, disguise.progress(solidifyTicks), 1e-6);

        hider.setLocation(new Location(world, 1.5, 70, 0.5));
        disguise.tick(solidifyTicks);
        assertEquals(0f, disguise.progress(solidifyTicks));
    }
}
