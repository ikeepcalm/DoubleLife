package dev.ua.ikeepcalm.doublelife.gui;

import dev.triumphteam.gui.builder.item.PaperItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionRecord;
import dev.ua.ikeepcalm.doublelife.domain.model.source.ActivityType;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Staff list → that staff member's sessions → one session's timeline. Lists come from the in-memory index;
 * a timeline's full record is read off the main thread.
 */
public class HistoryGUI {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DoubleLife plugin;

    public HistoryGUI(DoubleLife plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, String nameFilter) {
        if (nameFilter == null) {
            openStaff(viewer);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(nameFilter);
        List<SessionRecord> records = target == null ? List.of() : plugin.getHistoryService().sessionsOf(target.getUniqueId());
        if (records.isEmpty()) {
            viewer.sendMessage(ComponentUtil.warning(plugin.getLangConfig().getMessage("history.not-found", viewer, nameFilter)));
        } else {
            openSessions(viewer, records, false, null);
        }
    }

    private void openStaff(Player viewer) {
        PaginatedGui gui = paginated(msg("history.title", viewer));
        List<List<SessionRecord>> staff = plugin.getHistoryService().staff();
        if (staff.isEmpty()) {
            gui.setItem(22, info(Material.BARRIER, msg("history.empty", viewer), List.of()));
        }
        for (List<SessionRecord> records : staff) {
            long flagged = records.stream().filter(SessionRecord::isFlagged).count();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(records.getFirst().getPlayerId()))));
            gui.addItem(PaperItemBuilder.from(head)
                    .name(ComponentUtil.title(records.getFirst().getPlayerName()))
                    .lore(lore(
                            plugin.getLangConfig().getMessage("history.staff-sessions", viewer, records.size()),
                            plugin.getLangConfig().getMessage("history.staff-flagged", viewer, flagged),
                            plugin.getLangConfig().getMessage("history.staff-last", viewer, DATE.format(Instant.ofEpochMilli(records.getFirst().getEndMillis()))),
                            "",
                            msg("history.click-open", viewer)))
                    .asGuiItem(event -> openSessions(viewer, records, false, () -> openStaff(viewer))));
        }
        navigation(gui, viewer, null);
        gui.open(viewer);
    }

    private void openSessions(Player viewer, List<SessionRecord> records, boolean flaggedOnly, Runnable back) {
        PaginatedGui gui = paginated(plugin.getLangConfig().getMessage("history.sessions-title", viewer, records.getFirst().getPlayerName()));
        for (SessionRecord record : records) {
            if (flaggedOnly && !record.isFlagged()) continue;
            List<String> lines = new ArrayList<>();
            lines.add(plugin.getLangConfig().getMessage("history.duration", viewer, ComponentUtil.clock((record.getEndMillis() - record.getStartMillis()) / 1000)));
            lines.add(plugin.getLangConfig().getMessage("history.reason", viewer, reasonOf(record)));
            lines.add(plugin.getLangConfig().getMessage("history.risk", viewer, record.getRiskLevel().getDisplayName(), record.getScore()));
            if (record.getAiVerdict() != null) {
                lines.add("");
                lines.addAll(wrap("AI: " + record.getAiVerdict()));
            }
            if (record.getFlags() != null && !record.getFlags().isEmpty()) {
                lines.add("");
                record.getFlags().stream().limit(4).forEach(flag -> lines.addAll(wrap("• " + flag)));
            }
            lines.add("");
            lines.add(plugin.getLangConfig().getMessage("history.activities", viewer, record.getActivityCount()));
            lines.add(msg("history.click-open", viewer));

            gui.addItem(PaperItemBuilder.from(riskMaterial(record.getRiskLevel()))
                    .name(ComponentUtil.title(titleOf(record)))
                    .lore(lore(lines.toArray(new String[0])))
                    .asGuiItem(event -> openTimeline(viewer, record, () -> openSessions(viewer, records, flaggedOnly, back))));
        }
        gui.setItem(53, PaperItemBuilder.from(flaggedOnly ? Material.REDSTONE_TORCH : Material.LEVER)
                .name(ComponentUtil.button(msg(flaggedOnly ? "history.show-all" : "history.flagged-only", viewer)))
                .asGuiItem(event -> openSessions(viewer, records, !flaggedOnly, back)));
        navigation(gui, viewer, back);
        gui.open(viewer);
    }

    private void openTimeline(Player viewer, SessionRecord summary, Runnable back) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SessionRecord record = plugin.getHistoryService().loadFull(summary);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (record != null) showTimeline(viewer, record, back);
            });
        });
    }

    private void showTimeline(Player viewer, SessionRecord record, Runnable back) {
        PaginatedGui gui = paginated(plugin.getLangConfig().getMessage("history.timeline-title", viewer, record.getPlayerName()));
        for (SessionRecord.Entry entry : record.getActivities()) {
            List<String> lines = new ArrayList<>(wrap(entry.getDetails()));
            if (entry.getLocation() != null && !entry.getLocation().isEmpty()) {
                lines.add(entry.getLocation());
            }
            gui.addItem(PaperItemBuilder.from(activityMaterial(entry.getType()))
                    .name(ComponentUtil.title("[" + TIME.format(Instant.ofEpochMilli(entry.getMillis())) + "] " + entry.getType().getDisplayName()))
                    .lore(lore(lines.toArray(new String[0])))
                    .asGuiItem());
        }
        List<String> summary = new ArrayList<>();
        summary.add(plugin.getLangConfig().getMessage("history.reason", viewer, reasonOf(record)));
        summary.add(plugin.getLangConfig().getMessage("history.risk", viewer, record.getRiskLevel().getDisplayName(), record.getScore()));
        if (record.getAiVerdict() != null) summary.addAll(wrap("AI: " + record.getAiVerdict()));
        gui.setItem(49, info(Material.WRITABLE_BOOK, titleOf(record), summary));
        navigation(gui, viewer, back);
        gui.open(viewer);
    }

    private PaginatedGui paginated(String title) {
        PaginatedGui gui = Gui.paginated()
                .title(ComponentUtil.gradient(title, "#FFD700", "#FF6B35"))
                .rows(6)
                .pageSize(45)
                .disableAllInteractions()
                .create();
        gui.getFiller().fillBottom(new GuiItem(PaperItemBuilder.from(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build()));
        return gui;
    }

    private void navigation(PaginatedGui gui, Player viewer, Runnable back) {
        gui.setItem(48, PaperItemBuilder.from(Material.ARROW).name(ComponentUtil.button(msg("history.previous", viewer)))
                .asGuiItem(event -> gui.previous()));
        gui.setItem(50, PaperItemBuilder.from(Material.ARROW).name(ComponentUtil.button(msg("history.next", viewer)))
                .asGuiItem(event -> gui.next()));
        if (back != null) {
            gui.setItem(45, PaperItemBuilder.from(Material.OAK_DOOR).name(ComponentUtil.button(msg("history.back", viewer)))
                    .asGuiItem(event -> back.run()));
        }
    }

    private GuiItem info(Material material, String title, List<String> lines) {
        return PaperItemBuilder.from(material).name(ComponentUtil.title(title)).lore(lore(lines.toArray(new String[0]))).asGuiItem();
    }

    private static List<Component> lore(String... lines) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(line.isEmpty() ? Component.empty() : ComponentUtil.lore(line));
        }
        return lore;
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).split(" ")) {
            if (line.length() + word.length() > 45 && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String titleOf(SessionRecord record) {
        return DATE.format(Instant.ofEpochMilli(record.getStartMillis())) + " · " + record.getMode().getDisplayName();
    }

    private static String reasonOf(SessionRecord record) {
        return record.getReason() != null ? record.getReason() : "-";
    }

    private static Material riskMaterial(RiskLevel level) {
        return switch (level) {
            case HIGH -> Material.RED_CONCRETE;
            case MEDIUM -> Material.YELLOW_CONCRETE;
            case LOW -> Material.LIME_CONCRETE;
        };
    }

    private static Material activityMaterial(ActivityType type) {
        return switch (type) {
            case COMMAND -> Material.COMMAND_BLOCK;
            case GAMEMODE_CHANGE -> Material.GRASS_BLOCK;
            case ITEM_GIVE, ITEM_PICKUP -> Material.HOPPER;
            case ITEM_DROP -> Material.DROPPER;
            case BLOCK_PLACE, BLOCK_BREAK -> Material.IRON_PICKAXE;
            case CONTAINER_ACCESS, CONTAINER_TRANSFER -> Material.CHEST;
            case TELEPORT -> Material.ENDER_PEARL;
            case NOTE -> Material.WRITABLE_BOOK;
            case SESSION_START, SESSION_END -> Material.PAPER;
        };
    }

    private String msg(String key, Player player) {
        return plugin.getLangConfig().getMessage(key, player);
    }
}
