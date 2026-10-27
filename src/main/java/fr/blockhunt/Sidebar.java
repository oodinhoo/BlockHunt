package fr.blockhunt;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/**
 * One player's own sidebar: a label on the left, a value on the right, no score numbers.
 * Each line is a fixed entry ("line0", "line1"...) whose custom name and number format carry
 * the text, so a line changes without flicker.
 */
final class Sidebar {

    /** A label and its value. {@link #EMPTY} is a blank spacer line. */
    record Line(String label, Component value) {
    }

    static final Line EMPTY = new Line("", Component.empty());

    private final Player player;
    /** The board the player had before, given back by {@link #remove()}. */
    private final Scoreboard previous;
    private final Scoreboard board;
    private final Objective objective;
    private int shown;

    Sidebar(Player player) {
        this.player = player;
        previous = player.getScoreboard();
        board = player.getServer().getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("blockhunt", Criteria.DUMMY,
                Component.text("BLOCK HUNT", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        player.setScoreboard(board);
    }

    void show(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Score score = objective.getScore("line" + i);
            // The sidebar sorts by descending score: the first line gets the highest.
            score.setScore(lines.size() - i);
            score.customName(Component.text(lines.get(i).label()));
            score.numberFormat(NumberFormat.fixed(lines.get(i).value()));
        }
        // A role change can shorten the list: drop the lines left over.
        for (int i = lines.size(); i < shown; i++) {
            board.resetScores("line" + i);
        }
        shown = lines.size();
    }

    void remove() {
        player.setScoreboard(previous);
    }
}
