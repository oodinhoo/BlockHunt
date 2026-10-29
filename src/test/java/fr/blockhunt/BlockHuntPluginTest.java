package fr.blockhunt;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** config.yml read at startup: a duration the game cannot work with is replaced, and said. */
class BlockHuntPluginTest {

    /** Each key and the smallest value the game works with, stated here on their own. */
    private static final Map<String, Integer> MINIMUMS = Map.of(
            "solidify-seconds", 1, "hide-seconds", 1, "seek-seconds", 1, "countdown-seconds", 0,
            "change-cooldown-seconds", 0, "sonar-cooldown-seconds", 0, "taunts", 0);

    private final List<String> warnings = new ArrayList<>();

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static YamlConfiguration config() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("hide-seconds", 30);
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(defaults);
        return config;
    }

    @Test
    void eachDurationBelowItsMinimumIsRaisedToIt() {
        MINIMUMS.forEach((key, minimum) -> {
            YamlConfiguration config = config();
            config.set(key, minimum - 1);

            BlockHuntPlugin.validate(config, logger());

            assertEquals(minimum, config.getInt(key), key);
            assertTrue(warnings.contains(key + " is " + (minimum - 1) + ", at least " + minimum + " is needed: "
                    + minimum + " is used instead."), key);
        });
    }

    @Test
    void eachDurationAtItsMinimumIsLeftAlone() {
        MINIMUMS.forEach((key, minimum) -> {
            YamlConfiguration config = config();
            config.set(key, minimum);

            BlockHuntPlugin.validate(config, logger());

            assertEquals(minimum, config.getInt(key), key);
        });
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aValueThatIsNotAWholeNumberFallsBackToTheDefault() {
        YamlConfiguration config = config();
        config.set("hide-seconds", "abc");

        BlockHuntPlugin.validate(config, logger());

        assertEquals(30, config.getInt("hide-seconds"));
        assertTrue(warnings.contains("hide-seconds is abc, not a whole number: 30 is used instead."));
    }

    @Test
    void aValueThatIsNotAWholeNumberWithNoDefaultFallsBackToTheMinimum() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("seek-seconds", "abc");

        BlockHuntPlugin.validate(config, logger());

        assertEquals(1, config.getInt("seek-seconds"));
        assertTrue(warnings.contains("seek-seconds is abc, not a whole number: 1 is used instead."));
    }

    /** A key the admin removed keeps reading its default: nothing to replace, nothing to say. */
    @Test
    void anUnsetKeyIsLeftAlone() {
        YamlConfiguration config = new YamlConfiguration();

        BlockHuntPlugin.validate(config, logger());

        MINIMUMS.keySet().forEach(key -> assertFalse(config.isSet(key), key));
        assertTrue(warnings.isEmpty());
    }

    /** The config.yml the plugin ships must start without a single warning. */
    @Test
    void theShippedConfigIsValid() {
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));

        BlockHuntPlugin.validate(shipped, logger());

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** /bh setspawn must not write the in-memory replacements over what the admin wrote. */
    @Test
    void savingTheSpawnLeavesTheRestOfTheAdminsFileAsWritten(@TempDir Path folder) throws IOException {
        ServerMock server = MockBukkit.mock();
        try {
            WorldMock world = server.addSimpleWorld("arena");
            File file = folder.resolve("config.yml").toFile();
            Files.writeString(file.toPath(), "hide-seconds: abc\n");

            BlockHuntPlugin.saveSpawn(file, new Location(world, 1.5, 70, 2.5));

            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
            assertEquals("abc", onDisk.getString("hide-seconds"));
            assertEquals(new Location(world, 1.5, 70, 2.5), onDisk.getLocation("spawn"));
        } finally {
            MockBukkit.unmock();
        }
    }
}
