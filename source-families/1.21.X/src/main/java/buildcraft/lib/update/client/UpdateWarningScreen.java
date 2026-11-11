//? source if >=1.21.1
package buildcraft.lib.update.client;

import buildcraft.lib.update.UpdateCheckResult;
import buildcraft.lib.update.UpdatePreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-style optional update warning screen for the 1.21 family. */
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

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0x66000000);
    }

    private static int guiTextColor(int rgb) {
//? if >=1.21.6 {
        return 0xFF000000 | (rgb & 0x00FFFFFF);
//? } else {
        return rgb;
//? }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
//? if <1.21.11 {
        renderBackground(graphics, mouseX, mouseY, partialTick);
//?}
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = width / 2;
        int y = height / 2 - 105;
        graphics.drawCenteredString(font, title, center, y, guiTextColor(0xFFFFFF));
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.description"),
            center, y + 26, guiTextColor(0xC0C0C0));
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.installed",
            result.currentVersion), center, y + 58, guiTextColor(0xFFFFFF));
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.latest",
            result.latestVersion), center, y + 76, guiTextColor(0xFFFF55));
        graphics.drawCenteredString(font, Component.translatable("gui.buildcraftlib.update.target", target),
            center, y + 96, guiTextColor(0xA0A0A0));
        if (linkCopied) graphics.drawCenteredString(font,
            Component.translatable("gui.buildcraftlib.update.link_copied"), center, height - 12, guiTextColor(0x55FF55));
    }
}
