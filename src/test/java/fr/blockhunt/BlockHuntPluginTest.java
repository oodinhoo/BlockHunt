package fr.blockhunt;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** config.yml read at startup: a duration the game cannot work with is replaced, and said. */
class BlockHuntPluginTest {

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
    void durationsBelowTheirMinimumAreRaisedToIt() {
        YamlConfiguration config = config();
        config.set("solidify-seconds", 0);
        config.set("seek-seconds", -5);

        BlockHuntPlugin.validate(config, logger());

        assertEquals(1, config.getInt("solidify-seconds"), "0 would turn solid and melt every tick");
        assertEquals(1, config.getInt("seek-seconds"));
        assertTrue(warnings.contains("solidify-seconds is 0, at least 1 is needed: 1 is used instead."));
        assertTrue(warnings.contains("seek-seconds is -5, at least 1 is needed: 1 is used instead."));
    }

    @Test
    void aValueThatIsNotAWholeNumberFallsBackToTheDefault() {
        YamlConfiguration config = config();
        config.set("hide-seconds", "abc");

        BlockHuntPlugin.validate(config, logger());

        assertEquals(30, config.getInt("hide-seconds"));
        assertTrue(warnings.contains("hide-seconds is abc, not a whole number: 30 is used instead."));
    }

    /** A cooldown or a countdown of 0 is valid: no wait at all. */
    @Test
    void validValuesAreLeftAsTheyAre() {
        YamlConfiguration config = config();
        config.set("sonar-cooldown-seconds", 0);
        config.set("hide-seconds", 45);

        BlockHuntPlugin.validate(config, logger());

        assertEquals(0, config.getInt("sonar-cooldown-seconds"));
        assertEquals(45, config.getInt("hide-seconds"));
        assertTrue(warnings.isEmpty());
    }
}
