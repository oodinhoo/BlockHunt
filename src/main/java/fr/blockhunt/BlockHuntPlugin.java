package fr.blockhunt;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Block hide and seek: hiders are disguised as blocks, seekers hit blocks to find them.
 * Wiring only: the rules live in {@link Game}, the disguise in {@link Disguise}.
 */
public final class BlockHuntPlugin extends JavaPlugin {

    private static final String ADMIN = "blockhunt.admin";

    /** The smallest value each duration or count works with: 0 solidify-seconds melts every tick. */
    private static final Map<String, Integer> MINIMUMS = Map.of(
            "solidify-seconds", 1, "hide-seconds", 1, "seek-seconds", 1, "countdown-seconds", 0,
            "change-cooldown-seconds", 0, "sonar-cooldown-seconds", 0, "taunts", 0);

    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Replaced by taunt-max-seconds and taunt-range: a tuned value would be ignored without a word.
        if (getConfig().isSet("taunt-seconds")) {
            getLogger().warning("taunt-seconds is no longer read: set taunt-max-seconds and taunt-range instead.");
        }
        validate(getConfig(), getLogger());
        game = new Game(this);
        getServer().getPluginManager().registerEvents(game, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("blockhunt")
                        .then(Commands.literal("join")
                                .executes(context -> asPlayer(context, game::join)))
                        .then(Commands.literal("leave")
                                .executes(context -> asPlayer(context, game::leave)))
                        .then(Commands.literal("start")
                                .requires(source -> source.getSender().hasPermission(ADMIN))
                                .executes(context -> {
                                    game.start(context.getSource().getSender());
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("setspawn")
                                .requires(source -> source.getSender().hasPermission(ADMIN))
                                .executes(context -> asPlayer(context, this::setSpawn)))
                        .then(Commands.literal("disguise")
                                .requires(source -> source.getSender().hasPermission(ADMIN))
                                .then(Commands.argument("block", ArgumentTypes.blockState())
                                        .executes(context -> asPlayer(context, player -> game.toggleDisguise(player,
                                                context.getArgument("block", BlockState.class).getBlockData())))))
                        .build(),
                "Block hide and seek.",
                List.of("bh")));
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.stop();
        }
    }

    /**
     * Once at startup: a value out of range, or that is no whole number (read as 0), is replaced in
     * memory and said in the console, rather than breaking a game without a word.
     */
    static void validate(FileConfiguration config, Logger logger) {
        MINIMUMS.forEach((key, minimum) -> {
            if (!config.isSet(key)) {
                return;
            }
            Configuration defaults = config.getDefaults();
            int fallback = defaults != null && defaults.isInt(key) ? defaults.getInt(key) : minimum;
            if (!config.isInt(key)) {
                logger.warning(key + " is " + config.get(key) + ", not a whole number: " + fallback + " is used instead.");
                config.set(key, fallback);
            } else if (config.getInt(key) < minimum) {
                logger.warning(key + " is " + config.getInt(key) + ", at least " + minimum + " is needed: " + minimum
                        + " is used instead.");
                config.set(key, minimum);
            }
        });
    }

    private void setSpawn(Player player) {
        getConfig().set("spawn", player.getLocation());
        saveConfig();
        player.sendActionBar(Component.text("Arena spawn set.", NamedTextColor.AQUA));
        game.refreshLobby();
    }

    private static int asPlayer(CommandContext<CommandSourceStack> context, Consumer<Player> action) {
        if (context.getSource().getExecutor() instanceof Player player) {
            action.accept(player);
        } else {
            context.getSource().getSender().sendMessage(Component.text("Players only.", NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }
}
