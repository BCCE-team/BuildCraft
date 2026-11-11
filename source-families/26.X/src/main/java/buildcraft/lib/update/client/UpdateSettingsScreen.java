package buildcraft.lib.update.client;

import buildcraft.lib.update.UpdatePreferences;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        automaticButton = Button.builder(Component.empty(), button -> {
            UpdatePreferences.setEnabled(!UpdatePreferences.isEnabled());
            refreshToggleLabel();
        }).bounds(x, y, buttonWidth, 20).build();
        addRenderableWidget(automaticButton);
        refreshToggleLabel();
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.settings.check_now"),
            button -> {
                UpdateNotificationManager.checkNow();
                Screen destination = parent instanceof UpdateWarningScreen warning
                    ? warning.getParentScreen() : parent;
                UpdateScreenCompat.setScreen(destination);
            }).bounds(x, y + 26, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.buildcraftlib.update.settings.reset_ignored"),
            button -> {
                UpdatePreferences.clearIgnoredVersions();
                ignoredVersionsReset = true;
            }).bounds(x, y + 52, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
            button -> onClose()).bounds(x, y + 78, buttonWidth, 20).build());
    }

    private void refreshToggleLabel() {
        if (automaticButton != null) {
            automaticButton.setMessage(Component.translatable("gui.buildcraftlib.update.settings.automatic",
                Component.translatable(UpdatePreferences.isEnabled() ? "options.on" : "options.off")));
        }
    }

    @Override
    public void onClose() {
        UpdateScreenCompat.setScreen(parent);
    }

    /**
     * Let Screen submit its regular panorama/dirt background. The warning screen is
     * retained only as the return destination and must never be rendered underneath.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSettingsText(GuiGraphicsExtractor graphics) {
        int center = width / 2;
        int titleY = height / 2 - 82;
        drawCentered(graphics, title, center, titleY, 0xFFFFFFFF);
        drawCentered(graphics, Component.translatable("gui.buildcraftlib.update.settings.description"),
            center, titleY + 20, 0xFFC0C0C0);
        if (ignoredVersionsReset) {
            drawCentered(graphics, Component.translatable("gui.buildcraftlib.update.settings.reset_done"),
                center, height - 28, 0xFF55FF55);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component component, int center, int y, int colour) {
        graphics.text(font, component, center - font.width(component) / 2, y, colour, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        renderSettingsText(graphics);
    }
}
