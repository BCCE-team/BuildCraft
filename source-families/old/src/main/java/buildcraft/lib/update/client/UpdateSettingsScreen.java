package buildcraft.lib.update.client;

import buildcraft.lib.update.UpdatePreferences;
import com.mojang.blaze3d.vertex.PoseStack;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiComponent;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client-local update settings with the standard vanilla screen background. */
public final class UpdateSettingsScreen extends Screen {
    private final Screen parent;
    private Button automaticButton;
    private boolean ignoredVersionsReset;

    public UpdateSettingsScreen(Screen parent) {
        super(Component.translatable("gui.buildcraftlib.update.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(360, Math.max(1, width - 40));
        int x = (width - buttonWidth) / 2;
        int y = height / 2 - 32;
        automaticButton = makeButton(Component.empty(), button -> {
            UpdatePreferences.setEnabled(!UpdatePreferences.isEnabled());
            refreshToggleLabel();
        }, x, y, buttonWidth, 20);
        addRenderableWidget(automaticButton);
        refreshToggleLabel();
        addRenderableWidget(makeButton(Component.translatable("gui.buildcraftlib.update.settings.check_now"),
            button -> {
                UpdateNotificationManager.checkNow();
                Screen destination = parent instanceof UpdateWarningScreen warning
                    ? warning.getParentScreen() : parent;
                UpdateScreenCompat.setScreen(destination);
            }, x, y + 26, buttonWidth, 20));
        addRenderableWidget(makeButton(Component.translatable("gui.buildcraftlib.update.settings.reset_ignored"),
            button -> {
                UpdatePreferences.clearIgnoredVersions();
                ignoredVersionsReset = true;
            }, x, y + 52, buttonWidth, 20));
        addRenderableWidget(makeButton(Component.translatable("gui.done"),
            button -> onClose(), x, y + 78, buttonWidth, 20));
    }

    private void refreshToggleLabel() {
        if (automaticButton != null) {
            automaticButton.setMessage(Component.translatable("gui.buildcraftlib.update.settings.automatic",
                Component.translatable(UpdatePreferences.isEnabled() ? "options.on" : "options.off")));
        }
    }

    private Button makeButton(Component label, Button.OnPress action, int x, int y, int w, int h) {
//? if >=1.20 {
        return Button.builder(label, action).bounds(x, y, w, h).build();
//?} else {
        return new Button(x, y, w, h, label, action);
//?}
    }

    @Override
    public void onClose() {
        UpdateScreenCompat.setScreen(parent);
    }

//? if >=1.20 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        int titleY = height / 2 - 82;
        graphics.drawCenteredString(font, title, center, titleY, 0xFFFFFF);
        graphics.drawCenteredString(font,
            Component.translatable("gui.buildcraftlib.update.settings.description"),
            center, titleY + 20, 0xC0C0C0);
        if (ignoredVersionsReset) {
            graphics.drawCenteredString(font,
                Component.translatable("gui.buildcraftlib.update.settings.reset_done"),
                center, height - 28, 0x55FF55);
        }
    }
//?} else {
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        int center = width / 2;
        int titleY = height / 2 - 82;
        GuiComponent.drawCenteredString(pose, font, title, center, titleY, 0xFFFFFF);
        GuiComponent.drawCenteredString(pose, font,
            Component.translatable("gui.buildcraftlib.update.settings.description"),
            center, titleY + 20, 0xC0C0C0);
        if (ignoredVersionsReset) {
            GuiComponent.drawCenteredString(pose, font,
                Component.translatable("gui.buildcraftlib.update.settings.reset_done"),
                center, height - 28, 0x55FF55);
        }
    }
//?}
}
