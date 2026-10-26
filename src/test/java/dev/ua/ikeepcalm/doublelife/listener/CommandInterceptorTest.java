package dev.ua.ikeepcalm.doublelife.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandInterceptorTest {

    @Test
    void matchesWholeCommandsAndSubcommands() {
        assertTrue(CommandInterceptor.matches("co", "co"));
        assertTrue(CommandInterceptor.matches("co inspect", "co"));
        assertTrue(CommandInterceptor.matches("coi church pray", "coi church pray"));
        assertFalse(CommandInterceptor.matches("coi", "co"));
        assertFalse(CommandInterceptor.matches("coi turbo", "coi church pray"));
    }
}
