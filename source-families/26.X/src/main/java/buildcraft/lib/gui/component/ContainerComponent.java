package buildcraft.lib.gui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerData;

public interface ContainerComponent {
    byte R_TO_L = 1;
    byte L_TO_R = 2;
    byte U_TO_D = 4;
    byte D_TO_U = 8;

    void render(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick,
        AbstractContainerScreen<?> screen);

    void postRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick,
        AbstractContainerScreen<?> screen);

    default boolean onClick(double x, double y, int mouse) { return false; }
    default boolean mouseRelease(double x, double y, int mouse) { return false; }
    default boolean isHovering(int x, int y) { return false; }
    default void renderTooltip(GuiGraphicsExtractor guiGraphics, int x, int y) { }
    default int getX() { return 0; }
    default int getY() { return 0; }
    default int getXsize() { return 0; }
    default int getYsize() { return 0; }
    default void setup(AbstractContainerScreen<?> screen, ContainerData data) { }
    default void setDataoffset(int offset) { }
    int getNeedDataSize();
    default void onClose() { }
}
