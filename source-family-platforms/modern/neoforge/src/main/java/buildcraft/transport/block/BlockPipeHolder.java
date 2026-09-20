//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.block;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import buildcraft.lib.internal.block.ICustomPaintHandler;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.PipeActivationResult;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.IItemPluggable;
import buildcraft.transport.internal.WireNode;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableModelKey;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.BCTransportItems;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.item.ItemWire;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.wire.EnumWireBetween;
import buildcraft.transport.wire.WireManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import buildcraft.lib.compat.GameProfileCompat;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import buildcraft.lib.compat.RegistryCompat;

public class BlockPipeHolder extends BlockBCTile_Neptune implements ICustomPaintHandler, EntityBlock, SimpleWaterloggedBlock {

	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

	public static final VoxelShape BOX_CENTER = Shapes.box(0.25D, 0.25D, 0.25D, 0.75D, 0.75D, 0.75D);
	public static final VoxelShape BOX_DOWN = Shapes.box(0.25D, 0, 0.25D, 0.75D, 0.25D, 0.75D);
	public static final VoxelShape BOX_UP = Shapes.box(0.25D, 0.75D, 0.25, 0.75D, 1D, 0.75D);
	public static final VoxelShape BOX_NORTH = Shapes.box(0.25D, 0.25D, 0, 0.75D, 0.75D, 0.25D);
	public static final VoxelShape BOX_SOUTH = Shapes.box(0.25D, 0.25D, 0.75D, 0.75D, 0.75D, 1D);
	public static final VoxelShape BOX_WEST = Shapes.box(0, 0.25D, 0.25D, 0.25D, 0.75D, 0.75D);
	public static final VoxelShape BOX_EAST = Shapes.box(0.75D, 0.25D, 0.25D, 1D, 0.75D, 0.75D);
	public static final VoxelShape[] BOX_FACES = { BOX_DOWN, BOX_UP, BOX_NORTH, BOX_SOUTH, BOX_WEST, BOX_EAST };
	
    private static final VoxelShape[] PIPE_SHAPE_CACHE = new VoxelShape[64];
    private static final int MAX_CONNECTION_SHAPE_CACHE = 512;
    private static final Map<PipeShapeKey, PipeShapeData> CONNECTION_SHAPE_CACHE = new ConcurrentHashMap<>();
//    private static final VoxelShape EXPENDED_CENTER = Shapes.box(0.125D, 0.125D, 0.125D, 0.875D, 0.875D, 0.875D);

	

	private static final Identifier ADVANCEMENT_LOGIC_TRANSPORTATION = Identifier.parse(
			"buildcrafttransport:logic_transportation");

	public BlockPipeHolder() {
		super(RegistryCompat.blockProperties(BlockBehaviour.Properties.of()).mapColor(MapColor.STONE).sound(SoundType.STONE).strength(0.25f)
				.explosionResistance(3.0f).noOcclusion());
		registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false));
	}


    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WATERLOGGED);
    }

    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            state = defaultBlockState();
        }
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return state.setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    protected BlockState updateShape(BlockState state, LevelReader world, ScheduledTickAccess scheduledTickAccess, BlockPos pos, Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            scheduledTickAccess.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        return super.updateShape(state, world, scheduledTickAccess, pos, direction, neighbourPos, neighbourState, random);
    }

    public void initializeClient(Consumer<IClientBlockExtensions> consumer) {
        consumer.accept(BlockPipeHolderClientExtensions.INSTANCE);
    }

	// ticks
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level lev, BlockState bs, BlockEntityType<T> bet) {
		return bet == BCTransportBlocks.PIPE_HOLDER_BE.get() ? ($0, pos, $1, BlockEntity) -> {
			if (BlockEntity instanceof TilePipeHolder) {
				((TilePipeHolder) BlockEntity).update();
			}
		} : null;
	}

	// basics

	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TilePipeHolder(pos, state);
	}

	public RenderShape getRenderShape(BlockState p_60550_) {
		return RenderShape.MODEL;
	}

	public VoxelShape getVisualShape(BlockState p_48735_, BlockGetter p_48736_, BlockPos p_48737_,
			CollisionContext p_48738_) {
		return Shapes.empty();
	}

	public VoxelShape getOcclusionShape(BlockState p_60578_, BlockGetter p_60579_, BlockPos p_60580_) {
		return Shapes.empty();
	}

	public boolean propagatesSkylightDown(BlockState p_48740_, BlockGetter p_48741_, BlockPos p_48742_) {
		return true;
	}

	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext p_60575_) {
//		return getShape(state, level, pos, CollisionContext.empty());
		return getInteractionShape(state, level, pos);
	}

	public boolean isCollisionShapeFullBlock(BlockState p_181242_, BlockGetter p_181243_, BlockPos p_181244_) {
		return false;
	}

	public boolean isOcclusionShapeFullBlock(BlockState p_222959_, BlockGetter p_222960_, BlockPos p_222961_) {
		return false;
	}

	public boolean hasDynamicShape() {
		return true;
	}

	@Nullable
	public BCBlockHitResult rayTrace(BlockGetter world, BlockPos pos, Player player) {
		Vec3 start = player.getEyePosition();
		double reachDistance = player.blockInteractionRange();
		Vec3 end = start.add(player.getLookAngle().normalize().scale(reachDistance));
		return rayTrace(world, pos, start, end);
	}
	
	public BCBlockHitResult rayTrace(BlockGetter world, BlockPos pos, Vec3 start, Vec3 end) {
        Pipe pipe = getPipe(world, pos, false).getPipe();
        VoxelShape centerShape = pipe == Pipe.EMPTY ? BOX_CENTER : getCachedConnectionShapes(pipe).combined();
        return rayTrace(world, pos, start, end, centerShape);
    }

	@Nullable
	public BCBlockHitResult rayTrace(BlockGetter world, BlockPos pos, Vec3 start, Vec3 end, VoxelShape centerShapes) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return new BCBlockHitResult(Shapes.block().clip(start, end, pos), 400);
		}
		BlockHitResult preResult = centerShapes.clip(start, end, pos);
		Vec3 preClip = preResult == null ? null : preResult.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
		double preDist = preResult == null ? Double.MAX_VALUE : preResult.getLocation().distanceToSqr(start);
		double maxX = start.x - end.x;
		int sign = maxX > 0 ? 1 : -1;
		double[] octant = new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
		Vec3 vec = end.subtract(start);
		Vec3 dvec = vec.scale(0.001);
		{
			double xoff = pos.getX() + 0.5;
			double yoff = pos.getY() + 0.5;
			double zoff = pos.getZ() + 0.5;
			Vec3 start0 = start.subtract(xoff, yoff, zoff);
			Vec3 end0 = end.subtract(xoff, yoff, zoff);
			if(Mth.abs((float) vec.x) > 1e-2) {
				double y1 = (start0.y * end0.x - start0.x * end0.y)/vec.x;
				double z1 = (start0.z * end0.x - start0.x * end0.z)/vec.x;
				int octant1 = (dvec.x > 0 ? 0b1 : 0) | (y1 + dvec.y > 0 ? 0b10 : 0) | (z1 + dvec.z > 0 ? 0b100 : 0);
				int octant2 = ((~octant1)&0b1) | (y1 - dvec.y > 0 ? 0b10 : 0) | (z1 - dvec.z > 0 ? 0b100 : 0);
				double dx = sign*(start0.x+dvec.x);
				octant[octant1] = dx> 0 ? Math.min(octant[octant1], dx) : octant[octant1];
				dx = sign*(start0.x-dvec.x);
				octant[octant2] = dx> 0? Math.min(octant[octant2], dx) : octant[octant2];
			}
			if(Mth.abs((float) vec.y) > 1e-2) {
				double x1 = (start0.x * end0.y - start0.y * end0.x)/vec.y;
				double z1 = (start0.z * end0.y - start0.y * end0.z)/vec.y;
				int octant1 = (x1 + dvec.x > 0 ? 0b1 : 0) | (dvec.y > 0 ? 0b10 : 0) | (z1 + dvec.z > 0 ? 0b100 : 0);
				int octant2 = (x1 - dvec.x > 0 ? 0b1 : 0) | ((~octant1)&0b10) | (z1 - dvec.z > 0 ? 0b100 : 0);
				double dx = sign*(start0.x - x1+dvec.x);
				octant[octant1] = dx > 0 ? Math.min(octant[octant1], dx) : octant[octant1];
				dx = sign*(start0.x - x1-dvec.x);
				octant[octant2] = dx > 0 ? Math.min(octant[octant2], dx) : octant[octant2];
			}
			if(Mth.abs((float) vec.z) > 1e-2) {
				double x1 = (start0.x * end0.z - start0.z * end0.x)/vec.z;
				double y1 = (start0.y * end0.z - start0.z * end0.y)/vec.z;
				int octant1 = (x1 + dvec.x > 0 ? 0b1 : 0) | (y1 + dvec.y > 0 ? 0b10 : 0) | (dvec.z > 0 ? 0b100 : 0);
				int octant2 = (x1 - dvec.x > 0 ? 0b1 : 0) | (y1 - dvec.y > 0 ? 0b10 : 0) | ((~octant1)&0b100);
				double dx = sign*(start0.x - x1+dvec.x);
				octant[octant1] = dx > 0 ? Math.min(octant[octant1], dx) : octant[octant1];
				dx = sign*(start0.x - x1-dvec.x);
				octant[octant2] = dx > 0 ? Math.min(octant[octant2], dx) : octant[octant2];
			}
		}

		
		double closest = Double.MAX_VALUE;
		int closestOctant = -1;//zyx
		boolean[] caculated = new boolean[6+8+36];
		BlockHitResult bestResult = preResult;
		int bestPart = 0;
		double bestDistance = preDist;
		Arrays.fill(caculated, false);
		Direction[] plugs = new Direction[3];
		do {
			closest = Double.MAX_VALUE;
			for(int i = 0;i<8;i++) {
				if(closest > octant[i]) {
					closest = octant[i];
					closestOctant = i;
				}
			}
			if(closestOctant != -1)
				octant[closestOctant] = Double.MAX_VALUE;
			if(closestOctant == -1)
				break;
			
			EnumWirePart parts;
			EnumWireBetween[] betweens = new EnumWireBetween[6];
			int directionId = (((closestOctant>>1)|(closestOctant<<2)))&0b111;//xzy
			for(int j = 0b0;j<5;j+=2,directionId>>=1) {
				plugs[j/2] = !caculated[(directionId&0b1)+j] ? Direction.values()[(directionId&0b1)+j] : null;
				caculated[(directionId&0b1)+j] = true;
//				BCLog.d(""+closestOctant + ":" + Direction.values()[(directionId&0b1)+j]);
			}
			int partId = (~((closestOctant>>2) | (closestOctant<<2) | (closestOctant&0b010)))&0b111; 
			parts = !caculated[6 + partId] ? EnumWirePart.VALUES[partId] : null;//xyz

			int octant1 = (~closestOctant)&0b111;
			for(int i = 0;i<3;i++) {
				int index = (((octant1>>(i&0b1))|(octant1>>(((~(i|(i>>>1)))&0b1)*2)))&0b11) + i *4;
				betweens[i] = !caculated[6+8+index] ? EnumWireBetween.VALUES[index] : null;
				caculated[6+8+index] = true;
				index += 12 + 4*i + (((octant1&(0b1<<i))<<(2-i)));
				betweens[i+3] = !caculated[6+8+index] ? EnumWireBetween.VALUES[index] : null;
				caculated[6+8+index] = true;
			}
/*			betweens[0] = EnumWireBetween.VALUES[(octant1|octant1>>2)&0b11];
			betweens[1] = EnumWireBetween.VALUES[(octant1>>1|octant1)&0b11+4];
			betweens[2] = EnumWireBetween.VALUES[(octant1|octant1)&0b11+8];//Center
			betweens[3] = EnumWireBetween.VALUES[(octant1|octant1>>2)&0b11+12+(octant1&0b1)<<2];//Between
			betweens[4] = EnumWireBetween.VALUES[(octant1>>1|octant1)&0b11+20+(octant1&0b10)<<1];
			betweens[5] = EnumWireBetween.VALUES[(octant1|octant1)&0b11+28+(octant1&0b100)];*/
			for(Direction face : plugs) {
				if(face == null) continue;
				PipePluggable pluggable = tile.getPluggable(face);
				if (pluggable != PipePluggable.EMPTY) {
					BlockHitResult clip = pluggable.getBoundingBox().clip(start, end, pos);
					if(clip == null) continue;
					double distance = clip.getLocation().distanceToSqr(start);
					if(distance < bestDistance) {
						bestDistance = distance;
						bestResult = clip;
						bestPart = face.ordinal() + 1 + 6;
					}
				}
			}
			WireManager wireManager = tile.getWireManager();
			DyeColor dyeColor = parts == null ? null : wireManager.parts.get(parts);
			if(dyeColor != null ) {
				BlockHitResult clip = parts.boundingBox.clip(start, end, pos);
				if(clip != null) {
					double distance = clip.getLocation().distanceToSqr(start);
					if(distance < bestDistance) {
						bestDistance = distance;
						bestResult = clip;
						bestPart = parts.ordinal() + 1 + 6 + 6;
					}
				}
			}
			for(EnumWireBetween between : betweens) {
				if(wireManager.betweens.get(between) != null){
					BlockHitResult clip = between.boundingBox.clip(start, end, pos);
					if(clip == null) continue;
					double distance = clip.getLocation().distanceToSqr(start);
					if(distance < bestDistance) {
						bestDistance = distance;
						bestResult = clip;
						bestPart = between.ordinal() + 1 + 6 + 6 + 8;
					}
				}
			}
		}while(closest != Double.MAX_VALUE/* && bestResult == preResult*/);
//		BCLog.logger.debug(set.toString());
		return bestResult == preResult ? (preResult == null ? null :
			new BCBlockHitResult(preResult, computHitFacing(preClip)))
				: new BCBlockHitResult(bestResult, bestPart);
	}

	@Nullable
	public static EnumWirePart rayTraceWire(BlockPos pos, Vec3 start, Vec3 end) {//DEBUG
		Vec3 realStart = start.subtract(pos.getX(), pos.getY(), pos.getZ());
		//Vec3 realEnd = end.subtract(pos.getX(), pos.getY(), pos.getZ());
		EnumWirePart best = null;
		double dist = 1000;
		for (EnumWirePart part : EnumWirePart.VALUES) {
			// to Debug
			BlockHitResult trace = part.boundingBoxPossible.clip(start, end, pos);
			if (trace != null) {
				if (best == null) {
					best = part;
					dist = trace.getLocation().distanceToSqr(realStart);
				} else {
					double nextDist = trace.getLocation().distanceToSqr(realStart);
					if (dist > nextDist) {
						best = part;
						dist = nextDist;
					}
				}
			}
		}
		return best;
	}

	@Nullable
	public static Direction getPartSideHit(Direction facing, int part) {
		if (part <= 0) {
			return facing;
		}
		if (part <= 6) {
			return Direction.values()[part - 1];
		}
		if (part <= 6 + 6) {
			return Direction.values()[part - 1 - 6];
		}
		return null;
	}
    
    @Nullable
    public static EnumWirePart getWirePartHit(int subHit) {
        if (subHit <= 6 + 6) {
            return null;
        } else if (subHit <= 6 + 6 + 8) {
            return EnumWirePart.VALUES[subHit - 1 - 6 - 6];
        } else {
            return null;
        }
    }

    @Nullable
    public static EnumWireBetween getWireBetweenHit(int subHit) {
        if (subHit <= 6 + 6 + 8) {
            return null;
        } else if (subHit <= 6 + 6 + 8 + EnumWireBetween.VALUES.length) {
            return EnumWireBetween.VALUES[subHit - 1 - 6 - 6 - 8];
        } else {
            return null;
        }
    }
    
	public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext collisionContext) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return BOX_CENTER;
		}
		if (!(collisionContext instanceof EntityCollisionContext context)) {
			return getInteractionShape(state, world, pos);
		}
		Entity entity = context.getEntity();
		double reachDistance = 4.5D;
		if(entity == null)
			return getInteractionShape(state, world, pos);
		if(entity instanceof Player player)
			reachDistance = player.blockInteractionRange();
		Vec3 carmpos = entity.getEyePosition();
		Vec3 vec31 = entity.getLookAngle();
		Vec3 vec32 = carmpos.add(vec31.x * reachDistance, vec31.y * reachDistance, vec31.z * reachDistance);
		
		VoxelShape[] allShape = getAllShape(world, pos);
		VoxelShape centerShape = tile.getPipe() == Pipe.EMPTY
			? allShape[0]
			: getCachedConnectionShapes(tile.getPipe()).combined();
		if(allShape.length == 1)
			return allShape[0] == null ? BOX_CENTER : allShape[0];
		BCBlockHitResult trace = rayTrace(world, pos, carmpos, vec32, centerShape);
		VoxelShape hitShape = trace != null ? allShape[trace.subHit] : null;
		return hitShape == null ? Shapes.empty() : hitShape;//Shapes.create(hitShape.bounds().inflate(1 / 32.0));

	}
	
	static int computHitOctant(Vec3 pos) {//zyx
		return (int)((pos.z >0.5 ? 0b100 : 0)|(pos.y>0.5 ? 0b10 : 0)|(pos.x>0.5 ? 0b1 : 0));
	}

	static int computHitFacing(Vec3 clip) {
		double x = clip.x;
		double y = clip.y;
		double z = clip.z;
//		BCLog.logger.debug(clip.toString());
		if (x > 0.75 + 1.0E-7D) {// East
//			BCLog.logger.debug("East");
			return 6;
		}
		if (x < 0.25 - 1.0E-7D) {// West
//			BCLog.logger.debug("West");
			return 5;
		}
		if (z > 0.75 + 1.0E-7D) {// South
//			BCLog.logger.debug("South");
			return 4;
		}
		if (z < 0.25 - 1.0E-7D) {// North
//			BCLog.logger.debug("North");
			return 3;
		}
		if (y > 0.75 + 1.0E-7D) {// Up
//			BCLog.logger.debug("Up");
			return 2;
		}
		if (y < 0.25 - 1.0E-7D) {// down
//			BCLog.logger.debug("Down");
			return 1;
		}
//		BCLog.logger.debug("Center");
		return 0;
	}

	static int computSubhit(TilePipeHolder tile, Vec3 inside, int octant) {//zyx
		Direction[] plugs = new Direction[3];
		EnumWirePart parts;
		EnumWireBetween[] betweens = new EnumWireBetween[6];
		int directionId = ((octant>>1)|(octant<<2))&0b111;//xzy
		for(int j = 0b0;j<0b110;j+=2,directionId>>=1)
			plugs[j/2] = Direction.values()[(directionId&0b1)+j];
		
		parts = EnumWirePart.VALUES[(~((octant>>2) | (octant<<2) | (octant&0b010)))&0b111];
		int octant1 = (~octant)&0b111;
		betweens[0] = EnumWireBetween.VALUES[(octant1|octant1>>2)&0b11];
		betweens[1] = EnumWireBetween.VALUES[(octant1>>1|octant1)&0b11+0b100];
		betweens[2] = EnumWireBetween.VALUES[(octant1|octant1)&0b11+0b1000];//Center
		betweens[3] = EnumWireBetween.VALUES[(octant1|octant1>>2)&0b11+0b1100+(octant1&0b1)<<2];//Between
		betweens[4] = EnumWireBetween.VALUES[(octant1>>1|octant1)&0b11+0b1100+(octant1&0b10)<<1];
		betweens[5] = EnumWireBetween.VALUES[(octant1|octant1)&0b11+0b1100+(octant1&0b100)];
		for(Direction face : plugs) {
			PipePluggable pluggable = tile.getPluggable(face);
			if (pluggable != PipePluggable.EMPTY&&pluggable.getBoundingBox().bounds().contains(inside)) {
				return face.ordinal() + 1 + 6;
			}
		}
		WireManager wireManager = tile.getWireManager();
		DyeColor dyeColor = wireManager.parts.get(parts);
		if(dyeColor != null && parts.boundingBox.bounds().contains(inside))
			return parts.ordinal() + 1 + 6 + 6;
		for(EnumWireBetween between : betweens) {
			if(wireManager.betweens.get(between) != null && between.boundingBox.bounds().contains(inside))
				return between.ordinal() + 1 + 6 + 6 + 8;
		}
		return computHitFacing(inside);
	}

	public VoxelShape[] getAllShape(BlockGetter world, BlockPos pos) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return new VoxelShape[] {BOX_CENTER};
		}
		VoxelShape[] result = new VoxelShape[1+6+6+8+36];
		boolean added = false;
		Pipe pipe = tile.getPipe();
		if (pipe != Pipe.EMPTY) {
			added = true;
			PipeShapeData cached = getCachedConnectionShapes(pipe);
			result[0] = BOX_CENTER;
			for (Direction face : Direction.values()) {
				VoxelShape connection = cached.faces()[face.get3DDataValue()];
				if (connection != null) {
					result[face.get3DDataValue() + 1] = connection;
				}
			}
		}
		for (Direction face : Direction.values()) {
			PipePluggable pluggable = tile.getPluggable(face);
			if(pluggable != PipePluggable.EMPTY) {
				VoxelShape bb = pluggable.getBoundingBox();
				result[1+ 6 + face.get3DDataValue()] = bb;//Pluggable ,subHit [7, 12]
			}
		}
		for (EnumWirePart part : tile.getWireManager().parts.keySet()) {
			result[1 + 6 + 6 + part.ordinal()] = part.boundingBox;
			added = true;
		}
		for (EnumWireBetween between : tile.getWireManager().betweens.keySet()) {
			result[1 + 6 + 6 + 8 + between.ordinal()] = between.boundingBox;
			added = true;
		}
		if(added)
			return result;
		// A holder whose client-side pipe data has not arrived yet must remain a small,
		// targetable pipe placeholder rather than an invisible full cube.
		return new VoxelShape[] {BOX_CENTER};
	}

	public VoxelShape getInteractionShape(BlockState state, BlockGetter world, BlockPos pos) {
		VoxelShape[] allShape = getAllShape(world, pos);
		VoxelShape voxelShape = allShape[0];
		int lenth = allShape.length;
		for(int i = 1;i < lenth;i++) {
			voxelShape = allShape[i] != null ? Shapes.or(voxelShape, allShape[i]) : voxelShape;
		}
		return voxelShape;
	}

	// Kept as a compatibility helper for callers that already have the hit result.
	public ItemStack getPickBlock(BlockState state, BlockHitResult target, Level world, BlockPos pos, Player player) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return ItemStack.EMPTY;
		}
		int subHit = 0;
		if (target != null) {
			Vec3 location = target.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
			subHit = computSubhit(tile, location, computHitOctant(location));
		}
		return getPickStack(tile, subHit);
	}

	private static ItemStack getPickStack(TilePipeHolder tile, int subHit) {
		if (subHit <= 6) {
			Pipe pipe = tile.getPipe();
			if (pipe != Pipe.EMPTY) {
				PipeDefinition def = pipe.getDefinition();
				Item item = (Item) PipeApi.pipeRegistry.getItemForPipe(def);
				if (item != null) {
					ItemStack stack = new ItemStack(item, 1);
					ItemPipeHolder.setPipeColor(stack, pipe.getColour());
					return stack;
				}
			}
		} else if (subHit <= 12) {
			Direction face = Direction.values()[subHit - 7];
			PipePluggable plug = tile.getPluggable(face);
			if (plug != PipePluggable.EMPTY) {
				return plug.getPickStack();
			}
		} else {
			EnumWirePart part = getWirePartHit(subHit);
			EnumWireBetween between = getWireBetweenHit(subHit);
			if (part != null && tile.wireManager.getColorOfPart(part) != null) {
				return new ItemStack(BCTransportItems.wires.get(tile.wireManager.getColorOfPart(part)), 1);
			} else if (between != null && tile.wireManager.getColorOfPart(between.parts[0]) != null) {
				return new ItemStack(BCTransportItems.wires.get(tile.wireManager.getColorOfPart(between.parts[0])), 1);
			}
		}
		return ItemStack.EMPTY;
	}

    @Nullable
    public static Direction rayTracePluggableSide(Level world, BlockPos pos, Player player) {
        if (world == null || pos == null || player == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockPipeHolder pipeBlock)) {
            return null;
        }
        BCBlockHitResult trace = pipeBlock.rayTrace(world, pos, player);
        if (trace == null || trace.subHit <= 6 || trace.subHit > 12) {
            return null;
        }
        return Direction.values()[trace.subHit - 7];
    }

    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        InteractionResult result = activatePipe(state, world, pos, player, hand, hit);
        if (result == InteractionResult.PASS) return InteractionResult.PASS;
        if (result == InteractionResult.FAIL) return InteractionResult.FAIL;
        return InteractionResult.SUCCESS;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        return activatePipe(state, world, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    private InteractionResult activatePipe(BlockState state, Level world, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult re) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null)
			return InteractionResult.PASS;
		BCBlockHitResult trace = rayTrace(world, pos, player);
		int subHit;
		Vec3 location;
		if (trace != null && trace.result != null) {
			re = trace.result;
			subHit = trace.subHit;
			location = re.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
		} else {
			Vec3 carmpos = player.getEyePosition();
			Vec3 dvec = re.getLocation().subtract(carmpos).scale(0.0125);
			location = re.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()).add(dvec);
			subHit = computSubhit(tile, location, computHitOctant(location));
		}
		Direction realSide = subHit == 0 ? re.getDirection() : getPartSideHit(re.getDirection(), subHit);
		if (realSide == null)
			realSide = re.getDirection();
		if (subHit > 6 && subHit <= 12) {
			PipePluggable existing = tile.getPluggable(realSide);
			if (existing != PipePluggable.EMPTY)
				return existing.onPluggableActivate(player, re, world);
		}

		EnumPipePart part = subHit == 0 ? EnumPipePart.CENTER : EnumPipePart.fromFacing(realSide);

		ItemStack held = player.getItemInHand(hand);
		Item item = held.isEmpty() ? null : held.getItem();
		PipePluggable existing = tile.getPluggable(realSide);
		if (item instanceof IItemPluggable && existing == PipePluggable.EMPTY) {
			IItemPluggable itemPlug = (IItemPluggable) item;
			PipePluggable plug = itemPlug.onPlace(held, tile, realSide, player, hand);
			if (plug == PipePluggable.EMPTY) {
				return InteractionResult.PASS;
			} else {
				tile.replacePluggable(realSide, plug);
				plug.onPlacedBy(player);
				if (!player.isCreative()) {
					held.shrink(1);
				}
				return InteractionResult.SUCCESS;
			}
		}
		if (existing != PipePluggable.EMPTY) {
			InteractionResult plugResult = existing.onPluggableActivate(player, re, world);
			if (plugResult != InteractionResult.PASS) {
				return plugResult;
			}
		}


		if (item instanceof ItemWire wire) {
			EnumWirePart wirePartHit = getWirePartHit(subHit);
			EnumWirePart wirePart;
			TilePipeHolder attachTile = tile;
			if (wirePartHit != null) {
				WireNode node = new WireNode(pos, wirePartHit);
				node = node.offset(re.getDirection());
				wirePart = node.part;
				if (!node.pos.equals(pos)) {
					attachTile = getPipe(world, node.pos, false);
				}
			} else {
				wirePart = EnumWirePart.get((location.x + 1) % 1 > 0.5, (location.y % 1 + 1) % 1 > 0.5,
						(location.z % 1 + 1) % 1 > 0.5);
			}
			if (wirePart != null && attachTile != null) {
				DyeColor colour = wire.getType();
				boolean attached = attachTile.getWireManager().addPart(wirePart, colour);
				attachTile.scheduleNetworkUpdate(IPipeHolder.PipeMessageReceiver.WIRES);
				if (attached) {
					WireNode from = new WireNode(attachTile.getPipePos(), wirePart);

					boolean isNowConnected = false;
					for (Direction dir : Direction.values()) {
						WireNode to = from.offset(dir);
						if (to.pos == attachTile.getPipePos()) {
							if (attachTile.getWireManager().getColorOfPart(to.part) == colour) {
								isNowConnected = true;
								break;
							}
						} else {
							BlockEntity localTile = attachTile.getLocalTile(to.pos);
							if (localTile instanceof TilePipeHolder) {
								if (((TilePipeHolder) localTile).getWireManager().getColorOfPart(to.part) == colour) {
									isNowConnected = true;
									break;
								}
							}
						}
					}
					if (isNowConnected) {
						AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_LOGIC_TRANSPORTATION);
					}

					if (!player.isCreative()) {
						held.shrink(1);
					}
				}
				if (attached) {
					return InteractionResult.SUCCESS;
				}
			}
		}
		Pipe pipe = tile.getPipe();
		if (pipe == Pipe.EMPTY) {
			return InteractionResult.PASS;
		}
        Direction apiSide = part.face != null ? part.face : re.getDirection();
        PipeActivationResult apiActivation = pipe.activateApiComponents(
            apiSide, player.getItemInHand(hand),
            AutomationActor.player(player.getUUID(), GameProfileCompat.name(player.getGameProfile())),
            world.isClientSide() ? OperationMode.SIMULATE : OperationMode.EXECUTE
        );
        if (apiActivation == PipeActivationResult.SUCCESS) {
            return InteractionResult.SUCCESS;
        }
        if (apiActivation == PipeActivationResult.DENIED || apiActivation == PipeActivationResult.FAILED) {
            return InteractionResult.FAIL;
        }
		if (pipe.behaviour.onPipeActivate(player, re, world, part)) {
			return InteractionResult.SUCCESS;
		}
		if (pipe.flow.onFlowActivate(player, re, world, part)) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public boolean onDestroyedByPlayer(BlockState state, Level world, BlockPos pos, Player player, ItemStack toolStack, boolean willHarvest, FluidState fluid) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid);
		}

		NonNullList<ItemStack> toDrop = NonNullList.create();
		BCBlockHitResult t = rayTrace(world, pos, player);
		if (t == null) {
			return super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid);
		}
		int subHit = t.subHit;
		BlockHitResult trace = t.result;
		
		Direction side = null;
		EnumWirePart part = null;
		EnumWireBetween between = null;

		if (trace != null && subHit > 6) {
			side = getPartSideHit(trace.getDirection(), subHit);
			part = getWirePartHit(subHit);
			between = getWireBetweenHit(subHit);
		}
		
		if (world.isClientSide()) {
//			return super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid);
			this.spawnDestroyParticles(world, player, pos, state);
			if(side == null && part == null && between ==null ) {
				
				world.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(player, state));
				return true;
			}
			return false;
		}

		if (side != null) {
			removePluggable(side, tile, toDrop);
		} else if (part != null) {
			ItemStack stack = new ItemStack(BCTransportItems.wires.get(tile.wireManager.getColorOfPart(part)), 1);
			toDrop.add(stack);
			tile.wireManager.removePart(part);
			tile.scheduleNetworkUpdate(IPipeHolder.PipeMessageReceiver.WIRES);
		} else if (between != null) {
			ItemStack stack = new ItemStack(BCTransportItems.wires.get(tile.wireManager.getColorOfPart(between.parts[0])), between.to == null ? 2 : 1);
			toDrop.add(stack);
			if (between.to == null) {
				tile.wireManager.removeParts(Arrays.asList(between.parts));
			} else {
				tile.wireManager.removePart(between.parts[0]);
			}
			tile.scheduleNetworkUpdate(IPipeHolder.PipeMessageReceiver.WIRES);
		} else {
//  		toDrop.addAll(getDrops(state, (ServerLevel) world, pos, null));
			return super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid);
		}
		if (!player.isCreative()) {
			InventoryUtil.dropAll(world, pos, toDrop);
		}
		return false;
//	        LogUtils.getLogger().debug("destorybyp "+world.isClientSide());
		//return super.onDestroyedByPlayer(state, world, pos, player, toolStack, willHarvest, fluid);
	}

	public List<ItemStack> getDrops(BlockState p_60537_, LootParams.Builder builder) {// Pipe-holder drops include the pipe and all attached pluggables/wires.
		NonNullList<ItemStack> toDrop = NonNullList.create();
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		if (blockEntity != null && blockEntity instanceof TilePipeHolder tile) {
			for (Direction face : Direction.values()) {
				PipePluggable pluggable = tile.getPluggable(face);
				if (pluggable != null) {
					pluggable.addDrops(toDrop, 1);// 1 is meaningless
				}
			}
			for (DyeColor color : tile.wireManager.parts.values()) {
				ItemStack stack = new ItemStack(BCTransportItems.wires.get(color), 1);
				toDrop.add(stack);
			}
			Pipe pipe = tile.getPipe();
			if (pipe != Pipe.EMPTY) {
				pipe.addDrops(toDrop, 1);// 1 is meaningless
			}
			return toDrop;
		}
		else {
			BCLog.logger.debug(getDescriptionId() + ": the BlockEntity in "
					+ builder.getOptionalParameter(LootContextParams.ORIGIN) + " can not be null!");
			return toDrop;
		}
	}

	public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
		Entity exploder = explosion.getDirectSourceEntity();
		if (exploder != null) {
			Vec3 subtract = exploder.position().subtract(Vec3.atLowerCornerOf(pos).add(VecUtil.VEC_HALF)).normalize();
			Direction side = Arrays.stream(Direction.values())
					.min(Comparator.comparing(facing -> Vec3.atLowerCornerOf(facing.getUnitVec3i()).distanceTo(subtract)))
					.orElseThrow(IllegalArgumentException::new);
			TilePipeHolder tile = getPipe(level, pos, true);
			if (tile != null) {
				PipePluggable pluggable = tile.getPluggable(side);
				if (pluggable != PipePluggable.EMPTY) {
					float explosionResistance = pluggable.getExplosionResistance(exploder, explosion);
					if (explosionResistance > 0) {
						return explosionResistance;
					}
				}
			}
		}
		return super.getExplosionResistance(state, level, pos, explosion);
	}

	protected void entityInside(BlockState state, Level world, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean submerged) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return;
		}
		Pipe pipe = tile.getPipe();
		if (pipe != Pipe.EMPTY) {
			pipe.getBehaviour().onEntityCollide(entity);
		}
	}

	public void playerDestroy(Level world, Player player, BlockPos pos, BlockState state, BlockEntity be,
			ItemStack stack) {
		super.playerDestroy(world, player, pos, state, be, stack);
	}

	public boolean canBeConnectedTo(BlockGetter world, BlockPos pos, Direction facing) {
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile == null) {
			return false;
		}
		PipePluggable pluggable = tile.getPluggable(facing);
		return pluggable != null && pluggable.canBeConnected();
	}

	private static void removePluggable(Direction side, TilePipeHolder tile, NonNullList<ItemStack> toDrop) {
		PipePluggable removed = tile.replacePluggable(side, PipePluggable.EMPTY);
		if (removed != PipePluggable.EMPTY) {
			removed.onRemove();
			removed.addDrops(toDrop, 0);
		}
	}

	public static TilePipeHolder getPipe(BlockGetter access, BlockPos pos, boolean requireServer) {
		if (access instanceof Level) {
			return getPipe((Level) access, pos, requireServer);
		}
		if (requireServer) {
			return null;
		}
		BlockEntity tile = access.getBlockEntity(pos);
		if (tile instanceof TilePipeHolder) {
			return (TilePipeHolder) tile;
		}
		return null;
	}

	public static TilePipeHolder getPipe(Level world, BlockPos pos, boolean requireServer) {
		if (requireServer && world.isClientSide()) {
			return null;
		}
		BlockEntity tile = world.getBlockEntity(pos);
		if (tile instanceof TilePipeHolder) {
			return (TilePipeHolder) tile;
		}
		return null;
	}

	// Block overrides

	public boolean addLandingEffects(BlockState state, ServerLevel worldObj, BlockPos blockPosition,
			BlockState BlockState, LivingEntity entity, int numberOfParticles) {
		BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, BlockState);
		worldObj.sendParticles(particle, entity.getX(), entity.getY(), entity.getZ(), numberOfParticles, 0.0D, 0.0D, 0.0D, (double)0.15F);
		return true;
	}
	

	// paint

	public InteractionResult attemptPaint(Level world, BlockPos pos, BlockState state, Vec3 hitPos, Direction hitSide,
			DyeColor paintColour) {
		TilePipeHolder tile = getPipe(world, pos, true);
		if (tile == null) {
			return InteractionResult.PASS;
		}

		Pipe pipe = tile.getPipe();
		if (pipe == Pipe.EMPTY) {
			return InteractionResult.FAIL;
		}
		if (pipe.getColour() == paintColour || !pipe.definition.canBeColoured) {
			return InteractionResult.FAIL;
		} else {
			pipe.setColour(paintColour);
			pipe.getHolder().getPipeTile().requestModelDataUpdate();
			return InteractionResult.SUCCESS;
		}
	}

	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		TilePipeHolder tile = getPipe(level, pos, false);
		return tile == null ? ItemStack.EMPTY : getPickStack(tile, 0);
	}

	/**
	 * NeoForge pick-block overload. Keeping this in addition to the vanilla three-argument method preserves
	 * compatibility with 1.21.1 patch levels that route pick-block through the extension hook.
	 */
	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData, Player player) {
		TilePipeHolder tile = getPipe(level, pos, false);
		if (tile == null) {
			return ItemStack.EMPTY;
		}
		int subHit = 0;
		if (player != null) {
			BCBlockHitResult trace = rayTrace(level, pos, player);
			if (trace != null) {
				subHit = trace.subHit;
			}
		}
		return getPickStack(tile, subHit);
	}

	public boolean canConnectRedstone(BlockState state, BlockGetter world, BlockPos pos, @Nullable Direction side) {
		if (side == null)
			return false;
		TilePipeHolder tile = getPipe(world, pos, false);
		if (tile != null) {
			PipePluggable pluggable = tile.getPluggable(side.getOpposite());
			return pluggable != PipePluggable.EMPTY && pluggable.canConnectToRedstone(side);
		}
		return false;
	}

	public boolean isSignalSource(BlockState p_60571_) {
		return true;
	}

	public int getSignal(BlockState p_60483_, BlockGetter p_60484_, BlockPos p_60485_, Direction p_60486_) {
		return getDirectSignal(p_60483_, p_60484_, p_60485_, p_60486_);
	}

	public int getDirectSignal(BlockState p_60559_, BlockGetter blockAccess, BlockPos pos, Direction side) {
		if (side == null) {
			return 0;
		}
		TilePipeHolder tile = getPipe(blockAccess, pos, false);
		if (tile != null) {
			return tile.getRedstoneOutput(side.getOpposite());
		}
		return 0;
	}

	/** a wrapper of {@link BlockHitResult} */
	protected static class BCBlockHitResult {
		int subHit;// as RayTraceResult in 1.12.2
		public BlockHitResult result;

		BCBlockHitResult(BlockHitResult result, int subHit) {
			this.result = result;
			this.subHit = subHit;
		}

	}
	
    private static PipeShapeData getCachedConnectionShapes(Pipe pipe) {
        PipeShapeKey key = new PipeShapeKey(
            Float.floatToIntBits(pipe.getConnectedDist(Direction.DOWN)),
            Float.floatToIntBits(pipe.getConnectedDist(Direction.UP)),
            Float.floatToIntBits(pipe.getConnectedDist(Direction.NORTH)),
            Float.floatToIntBits(pipe.getConnectedDist(Direction.SOUTH)),
            Float.floatToIntBits(pipe.getConnectedDist(Direction.WEST)),
            Float.floatToIntBits(pipe.getConnectedDist(Direction.EAST))
        );
        PipeShapeData cached = CONNECTION_SHAPE_CACHE.get(key);
        if (cached != null) return cached;

        PipeShapeData created = createConnectionShapes(key);
        if (CONNECTION_SHAPE_CACHE.size() >= MAX_CONNECTION_SHAPE_CACHE) {
            return created;
        }
        PipeShapeData raced = CONNECTION_SHAPE_CACHE.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private static PipeShapeData createConnectionShapes(PipeShapeKey key) {
        int[] bits = { key.down(), key.up(), key.north(), key.south(), key.west(), key.east() };
        VoxelShape[] faces = new VoxelShape[6];
        VoxelShape combined = BOX_CENTER;
        for (Direction face : Direction.values()) {
            float distance = Float.intBitsToFloat(bits[face.get3DDataValue()]);
            if (distance <= 0) continue;

            VoxelShape connection = BOX_FACES[face.get3DDataValue()];
            if (distance != 0.25F) {
                Vec3 center = VecUtil.offset(new Vec3(0.5, 0.5, 0.5), face, 0.25 + (distance / 2));
                Vec3 radius = VecUtil.replaceValue(new Vec3(0.25, 0.25, 0.25), face.getAxis(), distance / 2);
                connection = Shapes.create(BoundingBoxUtil.makeFrom(center.subtract(radius), center.add(radius)));
            }
            faces[face.get3DDataValue()] = connection;
            combined = Shapes.or(combined, connection);
        }
        return new PipeShapeData(combined, faces);
    }

    private record PipeShapeKey(int down, int up, int north, int south, int west, int east) {}

    private record PipeShapeData(VoxelShape combined, VoxelShape[] faces) {}

    public static final VoxelShape getCachedPipeShape(Direction[] ds, int len) {
    	int index = 0;
    	for(int i = 0;i<len;i++) {
    		if(ds != null)
    		index+= 1<<ds[i].ordinal();
    	}
    	VoxelShape shape =  PIPE_SHAPE_CACHE[index];
    	if(shape == null) {
    		shape = BOX_CENTER;
    		for(int i = 0;i<len;i++)
    			shape = Shapes.or(shape, BOX_FACES[ds[i].get3DDataValue()]);
    		 PIPE_SHAPE_CACHE[index] = shape;
    	}
    	return shape;
    	
    }

	// NeoForge 1.21.1 no longer declares this legacy render-properties hook.
	public Object getRenderPropertiesInternal() {
		return this;
	}
    
    
}
