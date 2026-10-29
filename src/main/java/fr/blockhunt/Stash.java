package fr.blockhunt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a game takes from a player and gives back: their inventory, game mode and position. All
 * wait in the player's own PDC, saved with their player data, so a crash mid-game gives them back
 * on the next join instead of losing them.
 */
final class Stash {

    private final NamespacedKey inventoryKey;
    private final NamespacedKey modeKey;
    private final NamespacedKey positionKey;
    private final Logger logger;

    Stash(Plugin plugin) {
        logger = plugin.getLogger();
        inventoryKey = new NamespacedKey(plugin, "inventory");
        modeKey = new NamespacedKey(plugin, "gamemode");
        positionKey = new NamespacedKey(plugin, "position");
    }

    /**
     * Empties the inventory and switches to adventure: no flying, no noclip, no block broken or
     * placed. {@code back} is where the player stood before the game took them to the arena.
     */
    void save(Player player, Location back) {
        // Items in the 2x2 crafting grid or on the cursor are not in getContents(): closing puts
        // them back in the inventory, or they would land in the kit later and be wiped with it.
        player.closeInventory();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        // getContents() has nulls for empty slots: serialize them as explicit empty stacks.
        ItemStack[] contents = Arrays.stream(player.getInventory().getContents())
                .map(item -> item == null ? ItemStack.empty() : item)
                .toArray(ItemStack[]::new);
        pdc.set(inventoryKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
        pdc.set(modeKey, PersistentDataType.STRING, player.getGameMode().name());
        // One left by a refused teleport back is where the player really came from: the arena
        // they still stand in must not replace it.
        if (!pdc.has(positionKey)) {
            pdc.set(positionKey, PersistentDataType.STRING, back.getWorld().getUID() + " " + back.getX() + " "
                    + back.getY() + " " + back.getZ() + " " + back.getYaw() + " " + back.getPitch());
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
    }

    /**
     * No-op for a player with nothing stashed. The position is kept when the teleport fails, as
     * for a player quitting: their next join takes them back. A part that cannot be read is set
     * aside, and the others are still given back; a failure to apply one that was read keeps it,
     * for the next join.
     */
    void restore(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] raw = pdc.get(inventoryKey, PersistentDataType.BYTE_ARRAY);
        if (raw != null) {
            ItemStack[] items = read(player, inventoryKey, PersistentDataType.BYTE_ARRAY, raw,
                    ItemStack::deserializeItemsFromBytes);
            if (items != null) {
                player.getInventory().setContents(items);
                pdc.remove(inventoryKey);
            }
        }
        String stored = pdc.get(modeKey, PersistentDataType.STRING);
        if (stored != null) {
            GameMode mode = read(player, modeKey, PersistentDataType.STRING, stored, GameMode::valueOf);
            if (mode != null) {
                player.setGameMode(mode);
                pdc.remove(modeKey);
            }
        }
        String position = pdc.get(positionKey, PersistentDataType.STRING);
        if (position != null) {
            Location back = read(player, positionKey, PersistentDataType.STRING, position,
                    value -> location(player.getServer(), value));
            if (back != null && player.teleport(back)) {
                pdc.remove(positionKey);
            }
        }
    }

    /** The stored value decoded, or null once set aside because it cannot be. */
    private <T, R> R read(Player player, NamespacedKey key, PersistentDataType<T, T> type, T value,
            Function<T, R> decode) {
        try {
            return decode.apply(value);
        } catch (RuntimeException e) {
            setAside(player, key, type, value, e);
            return null;
        }
    }

    /**
     * Moved to {@code <key>-unreadable} for an admin to look at: left in place, it would fail again on
     * every join, and the next game's save would overwrite it. One set aside before is kept too.
     */
    private <T> void setAside(Player player, NamespacedKey key, PersistentDataType<T, T> type, T value,
            RuntimeException error) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        NamespacedKey aside = new NamespacedKey(key.getNamespace(), key.getKey() + "-unreadable");
        for (int n = 2; pdc.has(aside); n++) {
            aside = new NamespacedKey(key.getNamespace(), key.getKey() + "-unreadable-" + n);
        }
        pdc.set(aside, type, value);
        pdc.remove(key);
        logger.log(Level.SEVERE, "Could not give " + player.getName() + " (" + player.getUniqueId() + ") their "
                + key.getKey() + " back: kept as " + aside + " in their player data.", error);
        player.sendMessage(Component.text("Part of what BlockHunt kept for you could not be given back. An admin "
                + "can recover it from your player data.", NamedTextColor.RED));
    }

    /** "world-uuid x y z yaw pitch". A world deleted since then: the main world's spawn. */
    private static Location location(Server server, String position) {
        String[] parts = position.split(" ");
        World world = server.getWorld(UUID.fromString(parts[0]));
        if (world == null) {
            return server.getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
    }
}
