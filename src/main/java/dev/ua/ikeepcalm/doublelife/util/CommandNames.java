package dev.ua.ikeepcalm.doublelife.util;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a typed command to every name it can be restricted under, so a namespace prefix
 * ({@code /minecraft:tp}) or an alias cannot bypass a restriction. Main thread only.
 */
public final class CommandNames {

    // Vanilla redirects that the Bukkit command map exposes as unrelated commands
    private static final Map<String, String> VANILLA_REDIRECTS = Map.of(
            "teleport", "tp",
            "tp", "teleport",
            "tell", "msg",
            "w", "msg");

    private CommandNames() {
    }

    /**
     * @param names every name the typed label resolves to: the label and the command's name and aliases
     * @param args  the arguments after the label
     */
    public record Parsed(Set<String> names, List<String> args) {

        public boolean matchesAny(List<String> candidates) {
            for (String candidate : candidates) {
                if (names.contains(normalize(candidate))) {
                    return true;
                }
            }
            return false;
        }

        public boolean is(String name) {
            return names.contains(name);
        }

        public String firstArg() {
            return args.isEmpty() ? null : args.getFirst();
        }
    }

    /** Parses a {@code PlayerCommandPreprocessEvent} message. */
    public static Parsed parse(String message) {
        String text = message.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String[] parts = text.split("\\s+");
        String rawLabel = parts[0].toLowerCase(Locale.ROOT);
        return new Parsed(resolveNames(rawLabel), Arrays.asList(parts).subList(1, parts.length));
    }

    private static Set<String> resolveNames(String rawLabel) {
        Set<String> names = new HashSet<>();
        names.add(stripNamespace(rawLabel));

        Command command = Bukkit.getCommandMap().getCommand(rawLabel);
        if (command == null) {
            command = Bukkit.getCommandMap().getCommand(stripNamespace(rawLabel));
        }
        if (command != null) {
            names.add(normalize(command.getName()));
            names.add(normalize(command.getLabel()));
            for (String alias : command.getAliases()) {
                names.add(normalize(alias));
            }
        }

        for (String name : Set.copyOf(names)) {
            String redirect = VANILLA_REDIRECTS.get(name);
            if (redirect != null) {
                names.add(redirect);
            }
        }
        names.remove("");
        return Set.copyOf(names);
    }

    private static String normalize(String label) {
        return stripNamespace(label.toLowerCase(Locale.ROOT));
    }

    private static String stripNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 && colon < label.length() - 1 ? label.substring(colon + 1) : label;
    }
}
