package fr.blockhunt;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * The play area of a running game: a sphere around the arena spawn, a dome above the ground and
 * as deep below it, caves included. Nothing is built: the wall is only drawn, with particles, for
 * whoever comes near it.
 */
final class Dome {

    /** Distance to the wall, inside or outside, at which a player starts seeing it. */
    private static final double SHOW_DISTANCE = 6;

    /** Half the side of the patch of wall drawn in front of a player, in particles one block apart. */
    private static final int PATCH = 4;

    private static final Particle.DustOptions WALL = new Particle.DustOptions(Color.fromRGB(85, 255, 255), 1.5f);

    private final Location center;
    private final double radius;

    Dome(Location center, double radius) {
        this.center = center.clone();
        this.radius = radius;
    }

    /** Where a player of the game is brought back to. */
    Location center() {
        return center.clone();
    }

    /** Another world counts as outside. */
    boolean contains(Location location) {
        return center.getWorld().equals(location.getWorld()) && location.distanceSquared(center) <= radius * radius;
    }

    /** Just outside the dome, on the ground, on the side of {@code from}. */
    Location outside(Location from) {
        Vector out = from.toVector().subtract(center.toVector()).setY(0);
        if (out.lengthSquared() < 1.0E-6) {
            out = new Vector(1, 0, 0); // right at the centre: any side will do
        }
        Location spot = center.clone().add(out.normalize().multiply(radius + 2));
        spot.setY(spot.getWorld().getHighestBlockYAt(spot.getBlockX(), spot.getBlockZ()) + 1);
        spot.setYaw(from.getYaw());
        spot.setPitch(from.getPitch());
        return spot;
    }

    /**
     * Draws the patch of wall facing the player, if they are close enough to the wall to see it.
     * The viewer must be in the dome's world.
     */
    void show(Player viewer) {
        Vector toViewer = viewer.getEyeLocation().toVector().subtract(center.toVector());
        // Right at the centre there is no direction to face, and a small dome is within reach.
        if (toViewer.lengthSquared() < 1.0E-6 || Math.abs(toViewer.length() - radius) > SHOW_DISTANCE) {
            return;
        }
        Vector u = toViewer.normalize();
        // Two directions along the wall, at right angles: the patch is a grid in that plane.
        Vector a = u.getCrossProduct(Math.abs(u.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0)).normalize();
        Vector b = u.getCrossProduct(a);
        double step = 1 / radius;
        for (int i = -PATCH; i <= PATCH; i++) {
            for (int j = -PATCH; j <= PATCH; j++) {
                Vector point = u.clone().add(a.clone().multiply(i * step)).add(b.clone().multiply(j * step))
                        .normalize().multiply(radius).add(center.toVector());
                viewer.spawnParticle(Particle.DUST, point.getX(), point.getY(), point.getZ(), 1, 0, 0, 0, 0, WALL);
            }
        }
    }
}
