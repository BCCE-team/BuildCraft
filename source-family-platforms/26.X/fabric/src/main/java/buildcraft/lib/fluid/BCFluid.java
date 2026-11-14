/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.fluid;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import buildcraft.lib.fluid.FluidStack;

public abstract class BCFluid extends FlowingFluid {
	private final Properties properties;
	private int colour = 0xFFFFFFFF, light = 0xFF_FF_FF_FF, dark = 0xFF_FF_FF_FF;
	private boolean isFlammable = false;
	private int lightOpacity = 0;
	private MapColor mapColour = MapColor.COLOR_BLACK;
	private int heatLevel;// int heat
	private boolean heatable;
	private String blockName;
	/** Negative means unlimited. Natural worldgen oil uses a bounded radius from a source block. */
	private int maxSourceSpreadDistance = -1;

	protected BCFluid(Properties properties) {
        super();
        this.properties = Objects.requireNonNull(properties, "properties");
        this.maxSourceSpreadDistance = properties.maxSourceSpreadDistance;
        this.heatLevel = properties.heat;
        this.colour = properties.color;
        this.isFlammable = properties.flammable;
        // Fluid density must have one canonical lookup for blocks, transfer and rendering.
        FabricFluidProperties.registerDensity(this, properties.density);
	}

    @Override public Fluid getSource() { return properties.source.get(); }
    @Override public Fluid getFlowing() { return properties.flowing.get(); }
    @Override protected boolean canConvertToSource(ServerLevel world) { return properties.canConvertToSource; }
    @Override protected void beforeDestroyingBlock(LevelAccessor world, BlockPos pos, BlockState state) {
        Block.dropResources(state, world, pos, state.hasBlockEntity() ? world.getBlockEntity(pos) : null);
    }
    @Override protected int getSlopeFindDistance(LevelReader level) { return properties.slopeFindDistance; }
    @Override protected int getDropOff(LevelReader level) { return properties.dropOff; }
    @Override public int getTickDelay(LevelReader level) { return properties.tickDelay; }
    @Override public Item getBucket() { return properties.bucket.get(); }
    @Override protected float getExplosionResistance() { return properties.explosionResistance; }
    @Override protected BlockState createLegacyBlock(FluidState state) {
        return properties.block.get().defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,
            getLegacyLevel(state));
    }
    @Override public boolean isSame(Fluid other) { return other == this || other == getSource() || other == getFlowing(); }


	protected void spread(ServerLevel getter, BlockPos orginPos, BlockState ignoredState, FluidState fluidState) {
		boolean lighterThanAir = FabricFluidProperties.isLighterThanAir(this);
		if (!lighterThanAir) {
			super.spread(getter, orginPos, getter.getBlockState(orginPos), fluidState);
			return;
		}

		if (!fluidState.isEmpty()) {

			BlockState blockstate = getter.getBlockState(orginPos);
			BlockPos blockpos = lighterThanAir ? orginPos.above() : orginPos.below();
			BlockState blockstate1 = getter.getBlockState(blockpos);
			FluidState fluidstate = this.getNewLiquid(getter, blockpos, blockstate1);
			if (this.canSpreadTo(getter, orginPos, blockstate, Direction.UP, blockpos, blockstate1, getter.getFluidState(blockpos), fluidstate.getType())) {
				this.spreadTo(getter, blockpos, blockstate1, Direction.UP, fluidstate);
				//this.sourceNeighborCount(p_76011_, p_76012_) >= 3)
				int i = 0;
				{

					for (Direction direction1 : Direction.Plane.HORIZONTAL) {
						BlockPos blockpos1 = orginPos.relative(direction1);
						FluidState fluidstate1 = getter.getFluidState(blockpos1);
						if (fluidstate1.getType().isSame(this) && fluidstate1.isSource()) {
							++i;
						}
					}
				}
				if (i >= 3) {
					// this.spreadToSides(p_76011_, p_76012_, p_76013_, blockstate);
					{
						int i0 = fluidState.getAmount() - this.getDropOff(getter);
						if (blockstate.getValue(FALLING)) {
							i0 = 7;
						}

						if (i0 > 0) {
							Map<Direction, FluidState> map = this.getSpread(getter, orginPos, blockstate);

							for (Map.Entry<Direction, FluidState> entry : map.entrySet()) {
								Direction direction2 = entry.getKey();
								FluidState fluidstate2 = entry.getValue();
								BlockPos blockpos2 = orginPos.relative(direction2);
								BlockState blockstate2 = getter.getBlockState(blockpos2);
								if (this.canSpreadTo(getter, orginPos, blockstate, direction2, blockpos2, blockstate2,
										getter.getFluidState(blockpos2), fluidstate2.getType())) {
									this.spreadTo(getter, blockpos2, blockstate2, direction2, fluidstate2);
								}
							}

						}
					}
				}
			} else if (fluidState.isSource()
					|| !(canPassThroughWall0(Direction.UP, getter, orginPos, blockstate, blockpos, blockstate1)
							&& (blockstate1.getFluidState().getType().isSame(this)
									|| this.canHoldFluid(getter, blockpos, blockstate1, fluidstate.getType())))) {
				//this.spreadToSides(p_76011_, p_76012_, p_76013_, blockstate);
				{
					int i1 = fluidState.getAmount() - this.getDropOff(getter);
					if (fluidState.getValue(FALLING)) {
						i1 = 7;
					}

					if (i1 > 0) {
						Map<Direction, FluidState> map = this.getSpread(getter, orginPos, blockstate);

						for (Map.Entry<Direction, FluidState> entry : map.entrySet()) {
							Direction direction2 = entry.getKey();
							FluidState fluidstate2 = entry.getValue();
							BlockPos blockpos2 = orginPos.relative(direction2);
							BlockState blockstate2 = getter.getBlockState(blockpos2);
							if (this.canSpreadTo(getter, orginPos, blockstate, direction2, blockpos2, blockstate2,
									getter.getFluidState(blockpos2), fluidstate2.getType())) {
								this.spreadTo(getter, blockpos2, blockstate2, direction2, fluidstate2);
							}
						}

					}
				}
			}

		}
	}

	private boolean canHoldFluid(BlockGetter p_75973_, BlockPos p_75974_, BlockState p_75975_, Fluid p_75976_) {
		Block block = p_75975_.getBlock();
		if (block instanceof LiquidBlockContainer) {
			return ((LiquidBlockContainer) block).canPlaceLiquid(null, p_75973_, p_75974_, p_75975_, p_75976_);
		} else if (!(block instanceof DoorBlock) && !p_75975_.is(BlockTags.SIGNS) && !p_75975_.is(Blocks.LADDER)
				&& !p_75975_.is(Blocks.SUGAR_CANE) && !p_75975_.is(Blocks.BUBBLE_COLUMN)) {
			if (p_75975_.is(Blocks.NETHER_PORTAL) || p_75975_.is(Blocks.END_PORTAL)
					|| p_75975_.is(Blocks.END_GATEWAY) || p_75975_.is(Blocks.STRUCTURE_VOID)
					|| !p_75975_.getFluidState().isEmpty()) {
				return false;
			}
			return !p_75975_.isSolid();
		} else {
			return false;
		}
	}

    /**
     * Equivalent to vanilla's private FlowingFluid.canPassThroughWall: test the
     * union of the two collision faces instead of relying on non-portable
     * NeoForge reflection or over-restrictive full-face solidity checks.
     */
    private boolean canPassThroughWall0(Direction direction, BlockGetter level,
                                       BlockPos sourcePos, BlockState source,
                                       BlockPos targetPos, BlockState target) {
        VoxelShape first = source.getCollisionShape(level, sourcePos);
        VoxelShape second = target.getCollisionShape(level, targetPos);
        if (first == Shapes.block() && second == Shapes.block()) return false;
        return !Shapes.mergedFaceOccludes(first, second, direction);
    }

	protected FluidState getNewLiquid(ServerLevel p_76036_, BlockPos p_76037_, BlockState p_76038_) {
		if (!FabricFluidProperties.isLighterThanAir(this))
			return super.getNewLiquid(p_76036_, p_76037_, p_76038_);
		int i = 0;
		int j = 0;

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos blockpos = p_76037_.relative(direction);
			BlockState blockstate = p_76036_.getBlockState(blockpos);
			FluidState fluidstate = blockstate.getFluidState();
			if (fluidstate.getType().isSame(this) && canPassThroughWall0(direction, p_76036_, p_76037_,
					p_76038_, blockpos, blockstate)) {
                boolean canCreateSource = canConvertToSource(p_76036_);
                if (fluidstate.isSource() && canCreateSource) {
                    ++j;
                }

				i = Math.max(i, fluidstate.getAmount());
			}
		}

		if (j >= 2) {
			BlockState blockstate1 = p_76036_.getBlockState(p_76037_.above());
			FluidState fluidstate1 = blockstate1.getFluidState();
			if (blockstate1.isSolid() || fluidstate1.getType().isSame(this) && fluidstate1.isSource()) {
				return this.getSource(false);
			}
		}

		BlockPos blockpos1 = p_76037_.below();
		BlockState blockstate2 = p_76036_.getBlockState(blockpos1);
		FluidState fluidstate2 = blockstate2.getFluidState();
		if (!fluidstate2.isEmpty() && fluidstate2.getType().isSame(this) && canPassThroughWall0(Direction.DOWN,
				p_76036_, p_76037_, p_76038_, blockpos1, blockstate2)) {
			return this.getFlowing(8, true);
		} else {
			int k = i - this.getDropOff(p_76036_);
			return k <= 0 ? Fluids.EMPTY.defaultFluidState() : this.getFlowing(k, false);
		}
	}

	private static boolean isWater(FluidState state) {
		Fluid fluid = state.getType();
		return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER || state.is(FluidTags.WATER);
	}

	protected void spreadTo(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
			FluidState fluidState) {
        // Vanilla FlowingFluid.spread() does not call the BuildCraft canSpreadTo override.
        // Enforce source-radius rules at the final shared placement boundary as well.
        if (isWater(level.getFluidState(pos)) || !isWithinSourceSpreadLimit(level, pos)) {
			return;
		}
		super.spreadTo(level, pos, state, direction, fluidState);
	}

	protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluidIn,
			Direction direction) {
		// Preserve BuildCraft fluid cells against incompatible water replacement while respecting density rules.
		// stable in oceans and only allow a genuinely denser, non-water fluid to displace them from above.
		if (fluidIn == Fluids.EMPTY || isWater(fluidIn.defaultFluidState())) {
			return false;
		}
		return direction == Direction.DOWN
			&& FabricFluidProperties.density(fluidIn) > FabricFluidProperties.density(this);
	}

	protected boolean canSpreadTo(BlockGetter p_75978_, BlockPos p_75979_, BlockState p_75980_, Direction p_75981_,
			BlockPos p_75982_, BlockState p_75983_, FluidState fluidState, Fluid p_75985_) {
		if (!isWithinSourceSpreadLimit(p_75978_, p_75982_)) {
			return false;
		}
		if (isWater(fluidState)) {
			return false;
		}
		return canPassThroughWall0(p_75981_, p_75978_, p_75979_, p_75980_, p_75982_, p_75983_)
			&& fluidState.canBeReplacedWith(p_75978_, p_75982_, p_75985_, p_75981_)
			&& canHoldFluid(p_75978_, p_75982_, p_75983_, p_75985_)
			&& (fluidState.isEmpty() || FabricFluidProperties.density(fluidState.getType()) < FabricFluidProperties.density(this));
	}

	private boolean isWithinSourceSpreadLimit(BlockGetter level, BlockPos targetPos) {
		int limit = maxSourceSpreadDistance;
		if (limit < 0) {
			return true;
		}
		for (int distance = 0; distance <= limit; distance++) {
			for (int dx = -distance; dx <= distance; dx++) {
				int yzDistance = distance - Math.abs(dx);
				for (int dy = -yzDistance; dy <= yzDistance; dy++) {
					int dz = yzDistance - Math.abs(dy);
					FluidState candidate = level.getFluidState(targetPos.offset(dx, dy, dz));
					if (candidate.isSource() && candidate.getType().isSame(this)) {
						return true;
					}
					if (dz != 0) {
						candidate = level.getFluidState(targetPos.offset(dx, dy, -dz));
						if (candidate.isSource() && candidate.getType().isSame(this)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	public BCFluid setMaxSourceSpreadDistance(int distance) {
		this.maxSourceSpreadDistance = distance < 0 ? -1 : distance;
		return this;
	}

	public Component getBareLocalizedName(FluidStack stack) {
		return stack == null ? Component.empty() : stack.getHoverName();
	}

	public void setMapColour(MapColor mapColour) {
		this.mapColour = mapColour;
	}

	public final MapColor getMapColour() {
		return this.mapColour;
	}

	public void setFlammable(boolean isFlammable) {
		this.isFlammable = isFlammable;
	}

	public final boolean isFlammable() {
		return isFlammable;
	}

	public void setLightOpacity(int lightOpacity) {
		this.lightOpacity = lightOpacity;
	}

	public final int getLightOpacity() {
		return lightOpacity;
	}

	public void setBlockName(String blockName) {
		this.blockName = blockName;
	}

	public String getBlockName() {
		return blockName;
	}

	public int getColor() {
		return colour;
	}

	public int getLightColour() {
		return light;
	}

	public int getDarkColour() {
		return dark;
	}

	public BCFluid setColour(int colour) {
		this.colour = colour;
		return this;
	}

	public BCFluid setColour(int light, int dark) {
		this.light = light;
		this.dark = dark;
		this.colour = 0xFF_FF_FF_FF;
		return this;
	}

	public BCFluid setHeat(int heat) {
		this.heatLevel = heat;
		return this;
	}

	public int getHeatValue() {
		return heatLevel;
	}

	public BCFluid setHeatable(boolean value) {
		heatable = value;
		return this;
	}

	public boolean isHeatable() {
		return heatable;
	}

    /** Vanilla/Fabric equivalent of BaseFlowingFluid.Properties for BCCE custom fluids. */
    public static final class Properties {
        private final Supplier<? extends Fluid> source;
        private final Supplier<? extends Fluid> flowing;
        private final Supplier<? extends Block> block;
        private final Supplier<? extends Item> bucket;
        private int slopeFindDistance = 4;
        private int dropOff = 1;
        private int tickDelay = 5;
        private int density = 1000;
        private boolean canConvertToSource;
        private float explosionResistance = 100F;
        private int maxSourceSpreadDistance = -1;
        private int heat;
        private int color = 0xFFFFFFFF;
        private boolean flammable;

        public Properties(Supplier<? extends Fluid> source, Supplier<? extends Fluid> flowing,
                          Supplier<? extends Block> block, Supplier<? extends Item> bucket) {
            this.source = Objects.requireNonNull(source);
            this.flowing = Objects.requireNonNull(flowing);
            this.block = Objects.requireNonNull(block);
            this.bucket = Objects.requireNonNull(bucket);
        }
        public Properties slopeFindDistance(int value) { slopeFindDistance = Math.max(1,value); return this; }
        public Properties dropOff(int value) { dropOff = Math.max(1,value); return this; }
        public Properties tickDelay(int value) { tickDelay = Math.max(1,value); return this; }
        public Properties density(int value) { density = value; return this; }
        public Properties canConvertToSource(boolean value) { canConvertToSource = value; return this; }
        public Properties explosionResistance(float value) { explosionResistance = Math.max(0F,value); return this; }
        public Properties maxSourceSpreadDistance(int value) { maxSourceSpreadDistance = value; return this; }
        public Properties heat(int value) { heat = value; return this; }
        public Properties color(int value) { color = value; return this; }
        public Properties flammable(boolean value) { flammable = value; return this; }
    }

	public static class Flowing extends BCFluid {
		public Flowing(Properties properties) {
			super(properties);
			registerDefaultState(getStateDefinition().any().setValue(LEVEL, 7));
		}

		protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
			super.createFluidStateDefinition(builder);
			builder.add(LEVEL);
		}

		public int getAmount(FluidState state) {
			return state.getValue(LEVEL);
		}

		public boolean isSource(FluidState state) {
			return false;
		}
	}

	public static class Source extends BCFluid {
		public Source(Properties properties) {
			super(properties);
		}

		public int getAmount(FluidState state) {
			return 8;
		}

		public boolean isSource(FluidState state) {
			return true;
		}
	}

}
