package dev.ua.ikeepcalm.doublelife.gui;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.util.ComponentUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public class ReasonDialog {

    private final DoubleLife plugin;

    public ReasonDialog(DoubleLife plugin) {
        this.plugin = plugin;
    }

    /**
     * Shows a one-field text dialog; {@code onConfirm} gets the trimmed text on the main thread.
     * Escape or the cancel button does nothing.
     */
    public void ask(Player player, String keyPrefix, Consumer<String> onConfirm) {
        String title = plugin.getLangConfig().getMessage(keyPrefix + ".title", player);
        String prompt = plugin.getLangConfig().getMessage(keyPrefix + ".prompt", player);
        String label = plugin.getLangConfig().getMessage(keyPrefix + ".input", player);
        String confirm = plugin.getLangConfig().getMessage(keyPrefix + ".confirm", player);
        String cancel = plugin.getLangConfig().getMessage("dialog.cancel", player);

        DialogAction confirmAction = DialogAction.customClick((response, audience) -> {
            String text = response.getText("reason");
            Bukkit.getScheduler().runTask(plugin, () -> onConfirm.accept(text == null ? "" : text.trim()));
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(ComponentUtil.gradient(title, "#FFD700", "#FF6B35"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(Component.text(prompt))))
                        .inputs(List.of(DialogInput.text("reason", Component.text(label)).width(300).maxLength(200).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text(confirm, NamedTextColor.GREEN)).action(confirmAction).build(),
                        ActionButton.builder(Component.text(cancel, NamedTextColor.GRAY)).build())));

        player.showDialog(dialog);
    }
}
