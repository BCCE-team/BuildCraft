//? source if >=26.2
package buildcraft.lib.gui;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.compat.minecraft.text.BCTextFormat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.FluidCompat;

public class TankContainerData implements ContainerData{
	
	/** Each logical 32-bit field uses two 16-bit ContainerData slots. */
    public static final int LEN = 10;
	protected final Tank[] tanks ;
	protected final int count;
	
	public TankContainerData(Tank... tanks) {
		this.tanks = tanks;
		this.count = tanks.length;
	}

	public int get(int p) {
		if(p >= LEN*count||p<0) {
			BCLog.logger.error("TankContainer.get: %d index out of range : %d",p , count*LEN);
			return 0;
		}
        int value = switch ((p % LEN) / 2) {
			case 0 -> BuiltInRegistries.FLUID.getId(tanks[p/LEN].getFluidType());
			case 1 -> tanks[p/LEN].getFluidAmount();
			case 2 -> BuiltInRegistries.FLUID.getId(tanks[p/LEN].getFluidForRender().getFluid());
			case 3 -> tanks[p/LEN].getFluidForRender().getAmount();
			case 4 -> tanks[p/LEN].getCapacity();
			default -> 0;
        };
        return ((p & 1) == 0 ? value : value >>> 16) & 0xFFFF;
		 
	}

	public void set(int index, int info) {
/*		if(playerId != 0xFFFF&&(index <size*2 && index >= 0&&(index&1)==0))
			tanks[index/2].onGuiClicked(playerId);*/
	}

	public int getCount() {
		return count*LEN;
	}
	
	public FluidStack getFluidStack(int index) {
		return tanks[index].getFluidInTank(0);
	}
	
	public static Fluid getFluid(int id) {
		return BuiltInRegistries.FLUID.byId(id);
	}
	
    /** Undo vanilla DataSlot's signed 16-bit transport without assuming a particular loader. */
    public static int getPacked(ContainerData data, int lowWordIndex) {
        return (data.get(lowWordIndex) & 0xFFFF) | ((data.get(lowWordIndex + 1) & 0xFFFF) << 16);
    }

	public static FluidStack getFluidStack(ContainerData data, int point) {
		return new FluidStack(BuiltInRegistries.FLUID.byId(getPacked(data, point + 0)), getPacked(data, point + 2));
	}
	
	public static FluidStack getRenderFluidStack(ContainerData data, int point) {
		return new FluidStack(BuiltInRegistries.FLUID.byId(getPacked(data, point + 4)), getPacked(data, point + 6));
	}
	
	public static Fluid getFluidType(ContainerData data, int point) {
		return BuiltInRegistries.FLUID.byId(getPacked(data, point + 0));
	}
	
	public static int getFluidAmount(ContainerData data, int point) {
		return getPacked(data, point + 2);
	}
	
	public static Fluid getRenderFluidId(ContainerData data, int point) {
		return BuiltInRegistries.FLUID.byId(getPacked(data, point + 4));
	}
	
	public static int getRenderFluidAmount(ContainerData data, int point) {
		return getPacked(data, point + 6);
	}
	
	public static int getTankCapacity(ContainerData data, int point) {
		return getPacked(data, point + 8);
	}
	
	public static ToolTip getTankToopTip(ContainerData data, int point) {
		return new ToolTip() {
	        public void refresh() {
	            clear();
	            int amount = getPacked(data, point + 2);
	            FluidStack fluidStack = BuiltInRegistries.FLUID.byId(getPacked(data, point + 4)) == Fluids.EMPTY ? FluidStack.EMPTY
	            		: new FluidStack(BuiltInRegistries.FLUID.byId(getPacked(data, point + 4)), getPacked(data, point + 6));;
	            if (fluidStack != FluidStack.EMPTY && amount > 0) {
	                add(FluidCompat.getDisplayName(fluidStack));
	            }
	            add(LocaleUtil.localizeFluidStaticAmount(amount, getPacked(data, point + 8)).setStyle(Style.EMPTY.withColor(BCTextFormat.GRAY.getColor())));
	            FluidStack serverFluid = new FluidStack(BuiltInRegistries.FLUID.byId(getPacked(data, point + 0)), getPacked(data, point + 2));
	            if (serverFluid != FluidStack.EMPTY && serverFluid.getAmount() > 0) {
	                add(Component.translatable("tooltip.buildcraftlib.fluid.server_side_on_client").setStyle(Style.EMPTY.withColor(BCTextFormat.RED.getColor())));
	                add(FluidCompat.getDisplayName(serverFluid));
	                add(LocaleUtil.localizeFluidStaticAmount(serverFluid.getAmount(), getPacked(data, point + 8)));
	            }
	        }
	    };

	}
	

}
