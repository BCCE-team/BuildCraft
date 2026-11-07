package buildcraft.factory.client.gui;

import java.util.List;

import buildcraft.lib.gui.TankContainerData;
import buildcraft.lib.gui.component.TankComponent;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;

/**
 * Heat Exchanger tank widget with the full network stride.
 *
 * <p>{@link TankComponent} normally reserves only ten values when it has a
 * dynamic capacity at offset eight. Modern {@link TankContainerData} sends
 * fourteen values per tank (including client/server fluid component IDs).
 * Without this specialization the screen rejects all four widgets at setup.
 */
public final class HeatExchangeTankComponent extends TankComponent {
    public HeatExchangeTankComponent(int x, int y, int width, int height) {
        super(x, y, width, height, 0, -1, -1, 8);
    }

    @Override
    public int getNeedDataSize() {
        return TankContainerData.LEN;
    }

    @Override
    public boolean isHovering(int mouseX, int mouseY) {
        // Match the exact BCU tank rectangle, including the top/left pixel.
        return mouseX >= x && mouseX < x + xs && mouseY >= y && mouseY < y + ys;
    }

    @Override
    protected List<Component> getToolTip(Fluid fluid, int amount, int capacity) {
        if (amount <= 0) {
            // BCU: an empty tank has one concise line, not a misleading 0 / capacity.
            return List.of(Component.translatable("buildcraft.tank.empty",
                LocaleUtil.localizeFluidStaticAmount(Math.max(0, capacity))));
        }
        return super.getToolTip(fluid, amount, capacity);
    }
}
