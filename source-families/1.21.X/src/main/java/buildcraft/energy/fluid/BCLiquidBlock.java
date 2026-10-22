//? source if >=1.21.1
package buildcraft.energy.fluid;

import java.util.Optional;
import buildcraft.energy.BCEnergyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import buildcraft.lib.compat.RegistryCompat;

public class BCLiquidBlock extends LiquidBlock {
	
    private final FlowingFluid sourceFluid;
	public final boolean isStick;
	public final int igniteOdds;
	public final int burnOdds;

	public BCLiquidBlock(FlowingFluid source, Properties propertes, boolean isStick, int igniteOdds, int burnOdds) {
		super(source, RegistryCompat.blockProperties(propertes));
        this.sourceFluid = source;
		this.isStick = isStick;
		this.igniteOdds = igniteOdds;
		this.burnOdds = burnOdds;
	}

	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean submerged) {
		if(BCEnergyConfig.oilIsSticky && isStick)
			entity.makeStuckInBlock(state	, new Vec3(0.25D, (double)0.05F, 0.25D));
		if (sourceFluid.getFluidType().getTemperature() > 350) {
			entity.lavaHurt();
		}
	}

	public Optional<SoundEvent> getPickupSound() {
		return super.getPickupSound();
	}

	public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
		return BCEnergyConfig.enableOilBurn ? igniteOdds : 0;
	}
	
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction)
    {
        return BCEnergyConfig.enableOilBurn ? burnOdds : 0;
    }

	public boolean isFireSource(BlockState state, LevelReader level, BlockPos pos, Direction direction) {
		return false;//BCEnergyConfig.enableOilBurn && igniteOdds > 0;
	}
	
}
