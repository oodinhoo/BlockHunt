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
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Consumer;

/**
 * Block hide and seek: hiders are disguised as blocks, seekers hit blocks to find them.
 * Wiring only: the rules live in {@link Game}, the disguise in {@link Disguise}.
 */
public final class BlockHuntPlugin extends JavaPlugin {

    private static final String ADMIN = "blockhunt.admin";

    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
