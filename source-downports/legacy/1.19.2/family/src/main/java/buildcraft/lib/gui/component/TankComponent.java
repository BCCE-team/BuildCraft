package buildcraft.lib.gui.component;

import java.util.List;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;

public class TankComponent extends AbstractComponent{

	protected int typeCache = Integer.MIN_VALUE;
	protected Fluid fluidCache;
	protected final int capacity;
	protected final int capacityOffset;
	protected final byte renderType;
	protected /*final*/ int tankx;
	protected /*final*/ int tanky;

	public TankComponent(int x, int y, int sx, int sy, int capacity, int tankx, int tanky) {
		this(x, y, sx, sy, capacity, tankx, tanky, -1, U_TO_D);
	}

	public TankComponent(int x, int y, int sx, int sy, int capacity, int tankx, int tanky, int capacityOffset) {
		this(x, y, sx, sy, capacity, tankx, tanky, capacityOffset, U_TO_D);
	}

	public TankComponent(int x, int y, int sx, int sy, int capacity, int tankx, int tanky, byte type) {
		this(x, y, sx, sy, capacity, tankx, tanky, -1, type);
	}

	public TankComponent(int x, int y, int sx, int sy, int capacity, int tankx, int tanky, int capacityOffset, byte type) {
		super(x, y, sx, sy);
		this.capacity = capacity;
		this.capacityOffset = capacityOffset;
		renderType = type;
		this.tankx = tankx;
		this.tanky = tanky;
	}

	//for debug
	public void resetSpritePos(int tankx, int tanky) {
		this.tankx = tankx;
		this.tanky = tanky;
	}

	@Override
	public void render(PoseStack pose, int mouseX, int mouseY, float partialTick, AbstractContainerScreen<?> screen) {
		int leftpos = screen.getGuiLeft();
		int toppos = screen.getGuiTop();
		int type = data.get(offset);
		if(type != typeCache)  {
			typeCache = type;
			fluidCache = TankContainerData.getFluid(type);
		}
		int capacity = getCapacity();
		int amount = Math.max(0, data.get(offset + 1));
		if (this.fluidCache != null && amount > 0 && capacity > 0) {
			int filled = Math.min(ys, Math.max(1, (int) ((long) ys * amount / capacity)));
			FluidRenderer.drawFluidForGui(this.fluidCache, leftpos + x, toppos + y + ys,
				leftpos + x + xs, toppos + y + ys - filled, pose.last());
		}
	}

	@Override
	public void postRender(PoseStack pose, int mouseX, int mouseY, float partialTick, AbstractContainerScreen<?> screen) {
		if(tankx>=0&&tanky>=0)
			screen.blit(pose, screen.getGuiLeft()+x, screen.getGuiTop()+y+1, tankx, tanky, xs, ys);
	}

	@Override
	public void renderTooltip(PoseStack pose, int x, int y) {
		if (super.isHovering(x - screen.getGuiLeft(), y - screen.getGuiTop())) {
			screen.renderComponentTooltip(pose,
				getToolTip(fluidCache, Math.max(0, data.get(offset + 1)), getCapacity()), x, y);
		}
	}


	@Override
	public int getNeedDataSize() {
		return capacityOffset >= 0 ? Math.max(2, capacityOffset + 1) : 2;
	}

	protected int getCapacity() {
		if (capacityOffset >= 0 && data != null) {
			int dynamicCapacity = data.get(offset + capacityOffset);
			if (dynamicCapacity > 0) {
				return dynamicCapacity;
			}
		}
		return capacity;
	}

	protected List<Component> getToolTip(Fluid fluid,int amount, int capacity) {
        List<Component> toolTip = Lists.newArrayList();
        if (amount > 0 && fluid != null) {
        	toolTip.add(fluid.getFluidType().getDescription());
        }
        toolTip.add((LocaleUtil.localizeFluidStaticAmount(amount, capacity)).withStyle(ChatFormatting.GRAY));
        return toolTip ;
    }



}
