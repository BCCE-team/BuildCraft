package buildcraft.lib.gui;

import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.lib.compat.minecraft.gui.BCContainerScreen;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.gui.component.ContainerComponent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;

public abstract class ContainerScreenBase<T extends AbstractContainerMenu> extends BCContainerScreen<T> {
    protected final Identifier TEXTURE_BASE;
    protected final ContainerComponent[] components;
    protected final boolean[] listenClick;
    protected int index;
    protected int offset;
    protected int size;

    public ContainerScreenBase(T menu, Inventory inventory, Component name, int componentSize, Identifier texture) {
        super(menu, inventory, name);
        TEXTURE_BASE = texture;
        components = new ContainerComponent[componentSize];
        listenClick = new boolean[componentSize];
        size = componentSize;
    }

    public ContainerScreenBase(T menu, Inventory inventory, Component name, int componentSize) {
        this(menu, inventory, name, componentSize, null);
    }

    public Identifier getBaseTexture() {
        return TEXTURE_BASE;
    }

    public void extractContents(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        super.extractContents(guiGraphics, mouseX, mouseY, partialTick);
        for (int i = 0; i < size; i++) {
            components[i].render(guiGraphics, mouseX, mouseY, partialTick, this);
        }
        if (TEXTURE_BASE != null) {
            for (int i = 0; i < size; i++) {
                components[i].postRender(guiGraphics, mouseX, mouseY, partialTick, this);
            }
        }
    }

    protected void renderBg(GuiGraphicsExtractor guiGraphics, float partialTick, int mouseX, int mouseY) {
        if (TEXTURE_BASE != null) {
            BCGraphics.blit(guiGraphics, TEXTURE_BASE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        }
    }

    public boolean mouseClicked(double x, double y, int button) {
        for (int i = 0; i < size; i++) {
            if (listenClick[i] && components[i].onClick(x, y, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(double x, double y, int button) {
        for (int i = 0; i < size; i++) {
            if (listenClick[i] && components[i].mouseRelease(x, y, button)) {
                return true;
            }
        }
        return false;
    }

    public void add(ContainerComponent component, boolean shouldListenClick) {
        if (index < 0 || index >= size) {
            BCLog.logger.error("ContainerScreenBase.add: index {} out of range {}", index, size);
            size = 0;
            return;
        }
        components[index] = component;
        listenClick[index++] = shouldListenClick;
        component.setDataoffset(offset);
        offset += component.getNeedDataSize();
    }

    public void setup(ContainerData data) {
        if (data.getCount() != offset) {
            BCLog.logger.error("ContainerScreenBase.setup: input data count does not equal component size");
            size = 0;
            return;
        }
        for (int i = 0; i < size; i++) {
            components[i].setup(this, data);
        }
    }

    public void onClose() {
        for (int i = 0; i < size; i++) {
            components[i].onClose();
        }
        super.onClose();
    }

    protected void extractTooltip(GuiGraphicsExtractor guiGraphics, int x, int y) {
        for (int i = 0; i < size; i++) {
            components[i].renderTooltip(guiGraphics, x, y);
        }
        super.extractTooltip(guiGraphics, x, y);
    }
}
