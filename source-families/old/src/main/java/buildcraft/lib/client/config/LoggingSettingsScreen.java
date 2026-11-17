package buildcraft.lib.client.config;

import buildcraft.lib.internal.debug.BCLogProfile;
import buildcraft.lib.internal.debug.BCLogProfile.Profile;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
//?}

/** Core > Debug settings for Forge and the optional Fabric Mod Menu integration. */
public final class LoggingSettingsScreen extends Screen {
    private final Screen parent;
    private Profile selected;
    private boolean verboseRobotLogging;
    private String errorMessage;
    private Button[] options;

    public LoggingSettingsScreen(Screen parent) {
        super(Component.translatable("gui.buildcraft.logging.title"));
        this.parent = parent;
        CoreDebugSettingsFile.Settings saved = CoreDebugSettingsFile.read();
        this.selected = saved.profile();
        this.verboseRobotLogging = saved.verboseRobotLogging();
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(310, Math.max(1, width - 32));
        int x = (width - buttonWidth) / 2;
        int y = height / 2 - 92;
        Profile[] profiles = Profile.values();
        options = new Button[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            final Profile choice = profiles[i];
            options[i] = makeButton(Component.empty(), button -> {
                selected = choice;
                errorMessage = null;
                refreshButtons();
            }, x, y + i * 24, buttonWidth, 20);
            addRenderableWidget(options[i]);
        }
        refreshButtons();
        int bottomY = y + profiles.length * 24 + 6;
        Button robotTraceButton = makeButton(Component.empty(), button -> {
            verboseRobotLogging = !verboseRobotLogging;
            errorMessage = null;
            button.setMessage(robotLabel());
        }, x, bottomY, buttonWidth, 20);
        robotTraceButton.setMessage(robotLabel());
        addRenderableWidget(robotTraceButton);
        bottomY += 26;
        int half = (buttonWidth - 6) / 2;
        addRenderableWidget(makeButton(Component.translatable("gui.buildcraft.logging.save"),
            button -> saveAndClose(), x, bottomY, half, 20));
        addRenderableWidget(makeButton(Component.translatable("gui.cancel"),
            button -> onClose(), x + half + 6, bottomY, buttonWidth - half - 6, 20));
    }

    private void refreshButtons() {
        if (options == null) return;
        Profile[] profiles = Profile.values();
        for (int i = 0; i < profiles.length; i++) {
            Component label = Component.translatable(profileKey(profiles[i]));
            options[i].setMessage(profiles[i] == selected
                ? Component.literal("[ ").append(label).append(" ]") : label);
        }
    }

    private Component robotLabel() {
        return Component.translatable("gui.buildcraft.logging.robot_verbose")
            .append(": ")
            .append(Component.translatable(verboseRobotLogging ? "options.on" : "options.off"));
    }

    private static String profileKey(Profile value) {
        return "gui.buildcraft.logging." + value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private void saveAndClose() {
        try {
            CoreDebugSettingsFile.save(selected, verboseRobotLogging);
            onClose();
        } catch (IOException | SecurityException exception) {
            errorMessage = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private Button makeButton(Component label, Button.OnPress action, int x, int y, int w, int h) {
//? if >=1.20 {
        return Button.builder(label, action).bounds(x, y, w, h).build();
//?} else {
        return new Button(x, y, w, h, label, action);
//?}
    }

    private interface TextDrawer { void draw(Component component, int y, int color); }

    private void drawText(TextDrawer drawer) {
        int top = height / 2 - 116;
        drawer.draw(title, top, 0xFFFFFF);
        drawer.draw(Component.translatable("gui.buildcraft.logging.description"), top + 18, 0xC0C0C0);
        drawer.draw(Component.translatable("gui.buildcraft.logging.detail." + selected.name().toLowerCase(java.util.Locale.ROOT)),
            top + 31, 0xC0C0C0);
        if (BCLogProfile.hasExternalOverride()) {
            drawer.draw(Component.translatable("gui.buildcraft.logging.override"), height - 39, 0xFFFF55);
        } else {
            drawer.draw(Component.translatable("gui.buildcraft.logging.restart"), height - 39, 0xC0C0C0);
        }
        if (errorMessage != null) {
            drawer.draw(Component.translatable("gui.buildcraft.logging.save_error"), height - 24, 0xFF5555);
        }
    }

//? if >=1.20 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawText((component, y, color) -> graphics.drawCenteredString(font, component, width / 2, y, color));
    }
//?} else {
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        drawText((component, y, color) -> GuiComponent.drawCenteredString(pose, font, component, width / 2, y, color));
    }
//?}

}
