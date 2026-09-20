//? source if >=1.21.1
package buildcraft.lib.gui;

import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.lib.compat.minecraft.gui.BCContainerScreen;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.gui.component.ContainerComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//? }
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;

public abstract class ContainerScreenBase<T extends AbstractContainerMenu> extends BCContainerScreen<T> {
//? if >=1.21.11 {
    protected final Identifier TEXTURE_BASE;
//? } else {
    protected final ResourceLocation TEXTURE_BASE;
//? }
    protected final ContainerComponent[] components;
    protected final boolean[] listenClick;
    protected int index;
    protected int offset;
    protected int size;

//? if >=1.21.11 {
    public ContainerScreenBase(T menu, Inventory inventory, Component name, int componentSize, Identifier texture) {
//? } else {
    public ContainerScreenBase(T menu, Inventory inventory, Component name, int componentSize, ResourceLocation texture) {
//? }
        super(menu, inventory, name);
        TEXTURE_BASE = texture;
        components = new ContainerComponent[componentSize];
        listenClick = new boolean[componentSize];
        size = componentSize;
    }

    public ContainerScreenBase(T menu, Inventory inventory, Component name, int componentSize) {
        this(menu, inventory, name, componentSize, null);
    }

//? if >=1.21.11 {
    public Identifier getBaseTexture() {
//? } else {
    public ResourceLocation getBaseTexture() {
//? }
        return TEXTURE_BASE;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        for (int i = 0; i < size; i++) {
            components[i].render(guiGraphics, mouseX, mouseY, partialTick, this);
        }
        if (TEXTURE_BASE != null) {
            for (int i = 0; i < size; i++) {
                components[i].postRender(guiGraphics, mouseX, mouseY, partialTick, this);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        if (TEXTURE_BASE != null) {
            BCGraphics.blit(guiGraphics, TEXTURE_BASE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        for (int i = 0; i < size; i++) {
            if (listenClick[i] && components[i].onClick(x, y, button)) {
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        for (int i = 0; i < size; i++) {
            if (listenClick[i] && components[i].mouseRelease(x, y, button)) {
                return true;
            }
        }
        return super.mouseReleased(x, y, button);
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

    @Override
    public void onClose() {
        for (int i = 0; i < size; i++) {
            components[i].onClose();
        }
        super.onClose();
    }

    @Override
    protected void renderTooltip(GuiGraphics guiGraphics, int x, int y) {
        for (int i = 0; i < size; i++) {
            components[i].renderTooltip(guiGraphics, x, y);
        }
        super.renderTooltip(guiGraphics, x, y);
    }
}
