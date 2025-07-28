package dev.ua.ikeepcalm.doublelife.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.stream.Collectors;

public class ComponentUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    // Brand colors for DoubleLife
    public static final TextColor PRIMARY_COLOR = TextColor.fromHexString("#FFD700"); // Gold
    public static final TextColor SECONDARY_COLOR = TextColor.fromHexString("#FF6B35"); // Orange-Red  
    public static final TextColor ACCENT_COLOR = TextColor.fromHexString("#4ECDC4"); // Teal
    public static final TextColor SUCCESS_COLOR = TextColor.fromHexString("#45B7D1"); // Light Blue
    public static final TextColor WARNING_COLOR = TextColor.fromHexString("#FFA726"); // Orange
    public static final TextColor ERROR_COLOR = TextColor.fromHexString("#EF5350"); // Red

    /**
     * Creates a gradient text component using MiniMessage
     */
    public static Component gradient(String text, String fromColor, String toColor) {
        String gradientText = "<gradient:" + fromColor + ":" + toColor + ">" + text + "</gradient>";
        return MINI_MESSAGE.deserialize(gradientText);
    }

    /**
     * Creates a rainbow gradient text
     */
    public static Component rainbow(String text) {
        return MINI_MESSAGE.deserialize("<rainbow>" + text + "</rainbow>");
    }

    /**
     * Creates the main plugin prefix with gradient
     */
    public static Component pluginPrefix() {
        return Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(gradient("DoubleLife", "#FFD700", "#FF6B35"))
                .append(Component.text("]", NamedTextColor.DARK_GRAY))
                .append(Component.space())
                .build();
    }

    /**
     * Creates a success message with prefix
     */
    public static Component success(String message) {
        return Component.text()
                .append(pluginPrefix())
                .append(Component.text(message, SUCCESS_COLOR))
                .build();
    }

    /**
     * Creates a warning message with prefix
     */
    public static Component warning(String message) {
        return Component.text()
                .append(pluginPrefix())
                .append(Component.text(message, WARNING_COLOR))
                .build();
    }

    /**
     * Creates an error message with prefix
     */
    public static Component error(String message) {
        return Component.text()
                .append(pluginPrefix())
                .append(Component.text(message, ERROR_COLOR))
                .build();
    }

    /**
     * Creates an info message with prefix
     */
    public static Component info(String message) {
        return Component.text()
                .append(pluginPrefix())
                .append(Component.text(message, NamedTextColor.WHITE))
                .build();
    }

    /**
     * Creates a title component with gradient and styling
     */
    public static Component title(String text) {
        return Component.text()
                .content(text)
                .style(Style.style()
                        .color(PRIMARY_COLOR)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                        .build())
                .build();
    }

    /**
     * Creates a subtitle component
     */
    public static Component subtitle(String text) {
        return Component.text()
                .content(text)
                .style(Style.style()
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false).build())
                .build();
    }

    /**
     * Creates lore text with proper formatting (no italics)
     */
    public static Component lore(String text) {
        return Component.text()
                .content(text)
                .style(Style.style()
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false).build())
                .build();
    }

    /**
     * Creates lore text with custom color
     */
    public static Component lore(String text, TextColor color) {
        return Component.text()
                .content(text)
                .style(Style.style()
                        .color(color)
                        .decoration(TextDecoration.ITALIC, false).build())
                .build();
    }

    /**
     * Creates lore from multiple lines
     */
    public static List<Component> loreLines(String... lines) {
        return List.of(lines).stream()
                .map(ComponentUtil::lore)
                .collect(Collectors.toList());
    }

    /**
     * Creates an animated/pulsing text effect using MiniMessage
     */
    public static Component pulse(String text, String color) {
        return MINI_MESSAGE.deserialize("<color:" + color + "><bold>" + text + "</bold></color>");
    }

    /**
     * Creates a progress bar component
     */
    public static Component progressBar(int current, int max, int length) {
        StringBuilder bar = new StringBuilder();
        int filled = (int) ((double) current / max * length);

        bar.append("<gradient:#45B7D1:#4ECDC4>");
        for (int i = 0; i < filled; i++) {
            bar.append("█");
        }
        bar.append("</gradient>");

        bar.append("<color:#3C3C3C>");
        for (int i = filled; i < length; i++) {
            bar.append("█");
        }
        bar.append("</color>");

        return MINI_MESSAGE.deserialize(bar.toString());
    }

    /**
     * Creates a time duration component with styling
     */
    public static Component duration(long seconds) {
        if (seconds >= 3600) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            return Component.text()
                    .append(Component.text(hours + "h ", PRIMARY_COLOR))
                    .append(Component.text(minutes + "m ", SECONDARY_COLOR))
                    .append(Component.text(secs + "s", ACCENT_COLOR))
                    .build();
        } else if (seconds >= 60) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return Component.text()
                    .append(Component.text(minutes + "m ", PRIMARY_COLOR))
                    .append(Component.text(secs + "s", SECONDARY_COLOR))
                    .build();
        } else {
            return Component.text(seconds + "s", PRIMARY_COLOR);
        }
    }

    /**
     * Creates a button-style component for GUIs
     */
    public static Component button(String text) {
        return Component.text()
                .content("▶ " + text)
                .style(Style.style()
                        .color(PRIMARY_COLOR)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false).build())
                .build();
    }

    /**
     * Creates a status indicator component
     */
    public static Component status(String text, boolean active) {
        String indicator = active ? "●" : "○";
        TextColor color = active ? SUCCESS_COLOR : NamedTextColor.GRAY;

        return Component.text()
                .append(Component.text(indicator + " ", color))
                .append(Component.text(text, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false)
                .build();
    }

    /**
     * Creates a divider line for GUI sections
     */
    public static Component divider() {
        return Component.text()
                .content("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .build();
    }
}