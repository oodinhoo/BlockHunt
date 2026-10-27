package fr.blockhunt;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

/**
 * What a game takes from a player and gives back: their inventory and game mode. Both wait
 * in the player's own PDC, saved with their player data, so a crash mid-game gives them back
 * on the next join instead of losing them.
 */
final class Stash {

    private final NamespacedKey inventoryKey;
    private final NamespacedKey modeKey;

    Stash(Plugin plugin) {
        inventoryKey = new NamespacedKey(plugin, "inventory");
        modeKey = new NamespacedKey(plugin, "gamemode");
    }

    /** Empties the inventory and switches to adventure: no flying, no noclip, no block broken or placed. */
    void save(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        // getContents() has nulls for empty slots: serialize them as explicit empty stacks.
        ItemStack[] contents = Arrays.stream(player.getInventory().getContents())
                .map(item -> item == null ? ItemStack.empty() : item)
                .toArray(ItemStack[]::new);
        pdc.set(inventoryKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
        pdc.set(modeKey, PersistentDataType.STRING, player.getGameMode().name());
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
    }

    /** No-op for a player with nothing stashed. */
    void restore(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] raw = pdc.get(inventoryKey, PersistentDataType.BYTE_ARRAY);
        if (raw != null) {
            player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(raw));
            pdc.remove(inventoryKey);
        }
        String mode = pdc.get(modeKey, PersistentDataType.STRING);
        if (mode != null) {
            player.setGameMode(GameMode.valueOf(mode));
            pdc.remove(modeKey);
        }
    }
}
