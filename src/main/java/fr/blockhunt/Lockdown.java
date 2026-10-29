package fr.blockhunt;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * What players of a running game cannot do: get hurt or hungry, move the kit, take an item in, or
 * run another plugin's commands. Nothing else of the game's state is needed.
 */
final class Lockdown implements Listener {

    private final Predicate<Player> playing;

    Lockdown(Predicate<Player> playing) {
        this.playing = playing;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && playing.test(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && playing.test(player)) {
            event.setCancelled(true);
        }
    }

    // An item taken from a frame or an armor stand would land in the kit inventory, which the
    // end-of-game restore overwrites: the item would be gone for good. Also covers the "at"
    // variant, which shares this handler list, and so armor stand manipulation that follows it.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (playing.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // The kit stays where it is: no moving, dropping, swapping or picking up during a game.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && playing.test(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (playing.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (playing.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && playing.test(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Only /bh during a game: another plugin's /home or /tpa would move a frozen seeker or cross
     * the dome, its /sell hand or /hat would take the copied block out of the game.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!playing.test(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        label = label.startsWith("blockhunt:") ? label.substring("blockhunt:".length()) : label;
        if (!label.equals("bh") && !label.equals("blockhunt")) {
            event.setCancelled(true);
            Game.deny(event.getPlayer(), "Only /bh works during a game.");
        }
    }
}
