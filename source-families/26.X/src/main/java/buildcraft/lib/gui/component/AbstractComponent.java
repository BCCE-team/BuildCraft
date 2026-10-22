package buildcraft.lib.gui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerData;

public abstract class AbstractComponent/* extends GuiComponent */implements ContainerComponent{

	protected AbstractContainerScreen<?> screen;
	protected ContainerData data ;
	
	protected int offset;
	
	protected boolean isPressing;
	
	protected int x;
	protected int y;
	protected int xs;//X Size
	protected int ys;//Y Size
	
	public AbstractComponent(int x, int y, int xs, int ys) {
		this.x = x;
		this.y = y;
		this.xs = xs;
		this.ys = ys;
	}
	
	//for debug
	public void resetPos(int x, int y, int xs, int ys) {
		this.x = x;
		this.y = y;
		this.xs = xs;
		this.ys = ys;
	}
	
	public void postRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, AbstractContainerScreen<?> screen) {
	}
	
	public boolean isHovering(int x, int y) {
		int dx = x-this.x;
		int dy = y-this.y;  
		return ((dx>0)&&(dx<xs))&&((dy>0)&&(dy<ys));
	}
	

	public boolean onClick(double x, double y, int mouse) {
		if(isHovering((int)(x-screen.getGuiLeft()), (int)(y-screen.getGuiTop()))) {
			isPressing = true;
			return ClickedAction(x, y, mouse);
		}
		return false;
	}
	
	public boolean ClickedAction(double x, double y, int mouse) {
		Minecraft mc = screen.getMinecraft();
		mc.gameMode.handleInventoryButtonClick(screen.getMenu().containerId, offset);
		return true;
	}
	

	public boolean mouseRelease(double x, double y, int mouse) {
		isPressing = false;
		return false;
	}


	public void setup(AbstractContainerScreen<?> screen, ContainerData data) {
		this.screen = screen;
		this.data = data;
	}

	public void onClose() {
		screen = null;
		data = null;
	}
	
	
	public void setDataoffset(int offset) {
		this.offset = offset;
	}
	

	public int getX() {
		return x;
	}
	
	public int getY() {
		return y;
	}

	public int getXsize() {
		return xs;
	}

	public int getYsize() {
		return ys;
	}
	
	
	
}
