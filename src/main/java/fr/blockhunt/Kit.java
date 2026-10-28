package fr.blockhunt;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** The items a game hands out: the seeker's sword and sonar, the hider's look and taunt. */
final class Kit {

    /**
     * The seekers' sonar. Not a plain compass: it is WorldEdit's default navigation wand, whose
     * right-click teleports the player before this plugin even sees the click.
     */
    static final Material SONAR = Material.RECOVERY_COMPASS;

    private Kit() {
    }

    /** Empties the inventory, and the item cooldowns the kit started with it. */
    static void clear(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.isEmpty()) {
                player.setCooldown(item, 0);
            }
        }
        player.getInventory().clear();
    }

    static void seeker(Player player) {
        clear(player);
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, item(Material.WOODEN_SWORD, "Seeker's sword"));
        inventory.setItem(1, item(SONAR, "Sonar (right-click)"));
        inventory.setHeldItemSlot(0);
    }

    static void hider(Player player, BlockData block) {
        clear(player);
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, blockItem(block));
        inventory.setItem(1, item(Material.FIREWORK_ROCKET, "Taunt (right-click)"));
        inventory.setHeldItemSlot(0);
    }

    /** The hider's current look in slot 1. Some blocks have no item form: the slot stays empty. */
    static ItemStack blockItem(BlockData block) {
        Material material = block.getMaterial();
        return material.isItem() ? item(material, "Disguise: " + name(material)) : ItemStack.empty();
    }

    static String name(Material material) {
        return material.getKey().getKey().replace('_', ' ');
    }

    private static ItemStack item(Material material, String name) {
        ItemStack item = ItemStack.of(material);
        item.editMeta(meta -> meta.itemName(Component.text(name)));
        return item;
    }
}
