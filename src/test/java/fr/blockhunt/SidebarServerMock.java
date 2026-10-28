package fr.blockhunt;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.RenderType;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scoreboard.ObjectiveMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardManagerMock;
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardMock;

import java.util.HashMap;
import java.util.Map;

/**
 * MockBukkit with the scoreboard calls {@link Sidebar} needs and MockBukkit does not implement:
 * the custom name and number format of a score and of an objective. They are only stored.
 */
// shortcut: drop this class once MockBukkit implements Score#customName and Score#numberFormat.
// Any subclass of ServerMock inherits its unchecked getBanList signature: not ours to fix.
@SuppressWarnings("unchecked")
class SidebarServerMock extends ServerMock {

    private final ScoreboardManagerMock scoreboards = new ScoreboardManagerMock() {
        @Override
        public ScoreboardMock getNewScoreboard() {
            return new Board();
        }
    };

    @Override
    public ScoreboardManagerMock getScoreboardManager() {
        return scoreboards;
    }

    private static final class Board extends ScoreboardMock {
        @Override
        public ObjectiveMock registerNewObjective(String name, Criteria criteria, Component displayName) {
            return new Objective(this, name, displayName, criteria);
        }
    }

    private static final class Objective extends ObjectiveMock {
        private final Map<String, Score> scores = new HashMap<>();
        private NumberFormat format;

        Objective(ScoreboardMock board, String name, Component displayName, Criteria criteria) {
            super(board, name, displayName, criteria, RenderType.INTEGER);
        }

        /** Not in the board's own objective map, which is private: it would refuse every call. */
        @Override
        public boolean isRegistered() {
            return true;
        }

        @Override
        public ScoreMock getScore(String entry) {
            return scores.computeIfAbsent(entry, key -> new Score(this, key));
        }

        @Override
        public NumberFormat numberFormat() {
            return format;
        }

        @Override
        public void numberFormat(NumberFormat format) {
            this.format = format;
        }
    }

    private static final class Score extends ScoreMock {
        private Component name;
        private NumberFormat format;

        Score(ObjectiveMock objective, String entry) {
            super(objective, entry);
        }

        @Override
        public Component customName() {
            return name;
        }

        @Override
        public void customName(Component name) {
            this.name = name;
        }

        @Override
        public NumberFormat numberFormat() {
            return format;
        }

        @Override
        public void numberFormat(NumberFormat format) {
            this.format = format;
        }
    }
}
