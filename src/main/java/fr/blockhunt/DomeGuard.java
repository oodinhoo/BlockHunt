package fr.blockhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Collection;
import java.util.function.Predicate;

/** Keeps the players of the running game inside its {@link Dome}, and everyone else out of it. */
final class DomeGuard implements Listener {

    private final Predicate<Player> playing;
    /** Play area of the running game, null between games or with arena-radius 0. */
    private Dome dome;

    DomeGuard(Predicate<Player> playing) {
        this.playing = playing;
    }

    /** The dome of the game starting, or null when it ends. */
    void guard(Dome dome) {
        this.dome = dome;
    }

    boolean contains(Location location) {
        return dome != null && dome.contains(location);
    }

    /** Draws the wall for whoever stands near it. */
    void show(Collection<? extends Player> viewers) {
        if (dome != null) {
            viewers.forEach(dome::show);
        }
    }

    /**
     * Once a second: a player of the game found outside the dome goes back to its centre. Only a
     * teleport can put them there (another plugin, an admin's /tp): walking out is stopped.
     */
    void bringBack(Collection<? extends Player> players) {
        if (dome == null) {
            return;
        }
        for (Player player : players) {
            if (!dome.contains(player.getLocation()) && player.teleport(dome.center())) {
                player.sendActionBar(Component.text("Back in the dome: the game is played inside.", NamedTextColor.YELLOW));
            }
        }
    }

    /** Sets someone who is not in the game down just outside the dome. False if the teleport is refused. */
    boolean sendOut(Player outsider) {
        outsider.leaveVehicle(); // a passenger cannot be teleported
        if (!outsider.teleport(dome.outside(outsider.getLocation()))) {
            return false;
        }
        outsider.sendActionBar(Component.text("A BlockHunt game is running in this dome.", NamedTextColor.AQUA));
        return true;
    }

    // ponytail: someone riding a horse or a boat moves through VehicleMoveEvent, which cannot be
    // cancelled: an outsider can ride into the dome. Dismount them there if it ever happens.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() && crosses(event.getPlayer(), event.getFrom(), event.getTo())) {
            // Back where they were, but still looking where they turned: cancelling would also
            // snap the camera back, every tick they keep walking into the wall.
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
            message(event.getPlayer());
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
                && crosses(event.getPlayer(), event.getFrom(), event.getTo())) {
            event.setCancelled(true);
            message(event.getPlayer());
        }
    }

    /** A player of the game leaving the dome, or anyone else entering it. */
    private boolean crosses(Player player, Location from, Location to) {
        if (dome == null || to == null) {
            return false;
        }
        boolean wasIn = dome.contains(from);
        boolean isIn = dome.contains(to);
        return playing.test(player) ? wasIn && !isIn : !wasIn && isIn;
    }

    private void message(Player player) {
        player.sendActionBar(playing.test(player)
                ? Component.text("The dome keeps you in the arena.", NamedTextColor.RED)
                : Component.text("A BlockHunt game is running in this dome.", NamedTextColor.RED));
    }
}
