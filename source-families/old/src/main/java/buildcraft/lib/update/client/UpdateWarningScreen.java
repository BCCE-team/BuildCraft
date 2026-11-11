package buildcraft.lib.update.client;

import buildcraft.lib.update.UpdateCheckResult;
import buildcraft.lib.update.UpdatePreferences;

//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
//?}
import net.minecraft.client.Minecraft;
//? if <1.20 {
import net.minecraft.client.gui.GuiComponent;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-style optional update warning screen for the legacy Minecraft family. */
public final class UpdateWarningScreen extends Screen {
    private final Screen parent;
    private final UpdateCheckResult result;
    private final String target;
    private boolean linkCopied;

    public UpdateWarningScreen(Screen parent, UpdateCheckResult result, String target) {
        super(Component.translatable("gui.buildcraftlib.update.title"));
        this.parent = parent;
        this.result = result;
        this.target = target;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(600, Math.max(1, width - 32));
        int gap = 8;
        int leftWidth = (buttonWidth - gap) / 2;
        int rightWidth = buttonWidth - gap - leftWidth;
        int leftX = (width - buttonWidth) / 2;
        int rightX = leftX + leftWidth + gap;
        int top = height / 2 + 10;

//? if >=1.20 {
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.open"),
            button -> openReleasePage()).bounds(leftX, top, leftWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.settings"),
            button -> UpdateScreenCompat.setScreen(new UpdateSettingsScreen(this)))
            .bounds(rightX, top, rightWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.ignore"), button -> {
            UpdatePreferences.ignore(target, result.latestVersion);
            onClose();
        }).bounds(leftX, top + 26, leftWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.continue"),
            button -> onClose()).bounds(rightX, top + 26, rightWidth, 20).build());
//?} else {
        addRenderableWidget(new Button(leftX, top, leftWidth, 20,
            Component.translatable("gui.buildcraftlib.update.open"), button -> openReleasePage()));
        addRenderableWidget(new Button(rightX, top, rightWidth, 20,
            Component.translatable("gui.buildcraftlib.update.settings"), button ->
                UpdateScreenCompat.setScreen(new UpdateSettingsScreen(this))));
        addRenderableWidget(new Button(leftX, top + 26, leftWidth, 20,
            Component.translatable("gui.buildcraftlib.update.ignore"), button -> {
                UpdatePreferences.ignore(target, result.latestVersion);
                onClose();
            }));
        addRenderableWidget(new Button(rightX, top + 26, rightWidth, 20,
            Component.translatable("gui.buildcraftlib.update.continue"), button -> onClose()));
//?}
    }

    private void openReleasePage() {
        try {
            UpdateScreenCompat.openUri(result.releaseUrl);
        } catch (Exception failure) {
            try {
                Minecraft.getInstance().keyboardHandler.setClipboard(result.releaseUrl.toString());
                linkCopied = true;
            } catch (RuntimeException ignored) {
                // Keep the warning screen usable even when clipboard access is unavailable.
            }
        }
    }

    public Screen getParentScreen() {
        return parent;
    }

    @Override
    public void onClose() {
        UpdateScreenCompat.setScreen(parent);
    }

//? if >=1.20 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0x66000000);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        int y = height / 2 - 105;
        graphics.drawCenteredString(font, title, center, y, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.description"),
            center, y + 26, 0xC0C0C0);
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.installed",
            result.currentVersion), center, y + 58, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.latest",
            result.latestVersion), center, y + 76, 0xFFFF55);
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.target", target),
            center, y + 96, 0xA0A0A0);
        if (linkCopied) graphics.drawCenteredString(font,
            Component.translatable("gui.buildcraftlib.update.link_copied"), center, height - 12, 0x55FF55);
    }
//?} else {
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        int center = width / 2;
        int y = height / 2 - 105;
        GuiComponent.drawCenteredString(pose, font, title, center, y, 0xFFFFFF);
        GuiComponent.drawCenteredString(pose, font, Component.translatable("gui.buildcraftlib.update.description"),
            center, y + 26, 0xC0C0C0);
        GuiComponent.drawCenteredString(pose, font, Component.translatable("gui.buildcraftlib.update.installed",
            result.currentVersion), center, y + 58, 0xFFFFFF);
        GuiComponent.drawCenteredString(pose, font, Component.translatable("gui.buildcraftlib.update.latest",
            result.latestVersion), center, y + 76, 0xFFFF55);
        GuiComponent.drawCenteredString(pose, font, Component.translatable("gui.buildcraftlib.update.target", target),
            center, y + 96, 0xA0A0A0);
        if (linkCopied) GuiComponent.drawCenteredString(pose, font,
            Component.translatable("gui.buildcraftlib.update.link_copied"), center, height - 12, 0x55FF55);
    }
//?}
}
