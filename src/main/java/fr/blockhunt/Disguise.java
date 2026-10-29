package fr.blockhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A player turned into a block. The player is hidden from everyone, a BlockDisplay follows
 * them for the look, and an Interaction gives seekers something to hit: a hidden player
 * cannot be hit, and a display has no hitbox.
 *
 * <p>Both entities are teleported every tick rather than mounted as passengers: an
 * Interaction has no transformation, so as a passenger its hitbox would float above the
 * player's head. The display's teleport duration lets the client smooth the movement.
 *
 * <p>Standing still long enough makes the hider <i>solid</i>: the display is hidden and the
 * other players get a fake block on the grid instead, indistinguishable from a real one.
 */
final class Disguise {

    /** Slightly bigger than a block: on a solid hider, the client must pick the hitbox, not the fake block. */
    private static final float HITBOX = 1.05f;

    /** Squared distance under which the player counts as not moving. */
    private static final double STILL = 1.0E-4;

    /** Squared distance from the solid spot that breaks the disguise. */
    private static final double BREAK_FREE = 0.01;

    private final Plugin plugin;
    private final Player player;
    private BlockData block;
    /** Server tick from which the look can change again. */
    private int nextChange;
    private final BlockDisplay display;
    private final Interaction hitbox;

    private Location lastPosition;
    private int stillTicks;
    /** Cell of the fake block, null while the hider moves. */
    private Block solid;
    /** Where the player stood when they became solid. */
    private Location anchor;

    Disguise(Plugin plugin, Player player, BlockData block) {
        this.plugin = plugin;
        this.player = player;
        this.block = block;

        Location at = upright(player.getLocation());
        display = player.getWorld().spawn(at, BlockDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBlock(block);
            // Origin at the feet: shift the block so it is centred on the player.
            entity.setTransformation(new Transformation(
                    new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
            entity.setTeleportDuration(2);
        });
        hitbox = player.getWorld().spawn(hitboxLocation(at), Interaction.class, entity -> {
            entity.setPersistent(false);
            entity.setInteractionWidth(HITBOX);
            entity.setInteractionHeight(HITBOX);
        });
        // Or the hider hits their own box when looking down.
        player.hideEntity(plugin, hitbox);
        for (Player viewer : player.getServer().getOnlinePlayers()) {
            hideFrom(viewer);
        }
    }

    Player player() {
        return player;
    }

    boolean owns(Entity entity) {
        return hitbox.equals(entity);
    }

    BlockData block() {
        return block;
    }

    boolean isSolid() {
        return solid != null;
    }

    /** From 0 (just moved) to 1 (solid), for the hider's XP bar. */
    float progress(int solidifyTicks) {
        return solid != null ? 1f : Math.min(1f, (float) stillTicks / Math.max(1, solidifyTicks));
    }

    int cooldownLeft() {
        return Math.max(0, nextChange - player.getServer().getCurrentTick());
    }

    /** New look, effective at once even while solid. */
    void change(BlockData newBlock, int cooldownTicks) {
        block = newBlock;
        nextChange = player.getServer().getCurrentTick() + cooldownTicks;
        display.setBlock(newBlock);
        if (solid != null) {
            sendFake();
        }
    }

    /** Also called for players who join after the disguise was applied. */
    void hideFrom(Player viewer) {
        if (!viewer.equals(player)) {
            viewer.hidePlayer(plugin, player);
        }
    }

    void tick(int solidifyTicks) {
        Location now = player.getLocation();

        if (solid != null) {
            if (now.distanceSquared(anchor) > BREAK_FREE) {
                melt();
            } else if (++stillTicks % 20 == 0) {
                // ponytail: blind resend every second, since a block update next to the cell
                // (water, redstone, a seeker clicking it) makes the client drop the fake block.
                // Listen to BlockPhysicsEvent around solid cells if a one-second flash shows.
                sendFake();
            }
            return;
        }

        Location at = upright(now);
        display.teleport(at);
        hitbox.teleport(hitboxLocation(at));

        stillTicks = lastPosition != null && now.distanceSquared(lastPosition) < STILL ? stillTicks + 1 : 0;
        lastPosition = now;
        if (stillTicks >= solidifyTicks) {
            solidify(now);
        }
    }

    /** Undoes everything. The disguise is unusable afterwards. */
    void remove() {
        if (solid != null) {
            sendReal();
        }
        display.remove();
        hitbox.remove();
        for (Player viewer : player.getServer().getOnlinePlayers()) {
            viewer.showPlayer(plugin, player);
        }
    }

    /** Drawn as nothing, by a display and by the client alike: a hider looking like it is invisible. */
    static boolean isInvisible(Material material) {
        return material.isAir() || material == Material.BARRIER || material == Material.LIGHT
                || material == Material.STRUCTURE_VOID;
    }

    /**
     * Water, grass, a slab: a full fake block there would look wrong. And a hider crouched over
     * a ledge has their centre above the void: their block would float.
     */
    static boolean canSolidifyAt(Block cell) {
        return cell.getType().isAir() && cell.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private void solidify(Location now) {
        Block cell = now.getBlock();
        if (!canSolidifyAt(cell)) {
            return; // keep moving
        }
        solid = cell;
        anchor = now;
        stillTicks = 0;

        Location center = cell.getLocation().add(0.5, 0, 0.5);
        display.teleport(center);
        hitbox.teleport(hitboxLocation(center));
        // The hider keeps seeing their display; everyone else sees the fake block.
        display.setVisibleByDefault(false);
        player.showEntity(plugin, display);
        sendFake();
        player.sendActionBar(Component.text("You are solid. Moving reveals you.", NamedTextColor.GREEN));
    }

    private void melt() {
        sendReal();
        solid = null;
        stillTicks = 0;
        display.setVisibleByDefault(true);
        player.sendActionBar(Component.text("You are moving again.", NamedTextColor.YELLOW));
    }

    private void sendFake() {
        for (Player viewer : solid.getWorld().getPlayers()) {
            if (!viewer.equals(player)) {
                viewer.sendBlockChange(solid.getLocation(), block);
            }
        }
    }

    private void sendReal() {
        for (Player viewer : solid.getWorld().getPlayers()) {
            viewer.sendBlockChange(solid.getLocation(), solid.getBlockData());
        }
    }

    /** A display takes its rotation from the location: keep the block aligned with the world. */
    private static Location upright(Location location) {
        Location copy = location.clone();
        copy.setYaw(0);
        copy.setPitch(0);
        return copy;
    }

    /** The hitbox grows from its base: lower it so it overhangs the block equally on every side. */
    private static Location hitboxLocation(Location at) {
        return at.clone().subtract(0, (HITBOX - 1) / 2, 0);
    }
}
