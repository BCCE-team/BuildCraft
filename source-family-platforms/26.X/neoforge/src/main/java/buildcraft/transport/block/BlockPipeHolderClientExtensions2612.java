package buildcraft.transport.block;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import javax.annotation.Nullable;

import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.PluggableFacade;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

/** 26.1 client extensions that preserve pipe particles and provide facade tint values with world context. */
final class BlockPipeHolderClientExtensions2612 implements IClientBlockExtensions {
    static final BlockPipeHolderClientExtensions2612 INSTANCE = new BlockPipeHolderClientExtensions2612();

    private BlockPipeHolderClientExtensions2612() {
    }

    @Override
    public boolean addHitEffects(BlockState state, Level level, @Nullable HitResult target, ParticleEngine manager) {
        return BlockPipeHolderClientExtensions.INSTANCE.addHitEffects(state, level, target, manager);
    }

    @Override
    public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine manager) {
        return BlockPipeHolderClientExtensions.INSTANCE.addDestroyEffects(state, level, pos, manager);
    }

    @Override
    public void collectDynamicTintValues(BlockState state, BlockAndTintGetter level, BlockPos pos, IntList tintValues) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TilePipeHolder pipeHolder)) {
            return;
        }

        var blockColors = Minecraft.getInstance().getBlockColors();
        for (Direction side : Direction.values()) {
            PipePluggable pluggable = pipeHolder.getPluggable(side);
            if (!(pluggable instanceof PluggableFacade facade)) {
                continue;
            }
            int phase = facade.activeState;
            if (phase < 0 || phase >= facade.states.phasedStates.length) {
                continue;
            }

            FacadePhasedState facadeState = facade.states.phasedStates[phase];
            BlockState sourceState = facadeState.stateInfo.state;
            List<BlockTintSource> sources = blockColors.getTintSources(sourceState);
            if (!sources.isEmpty()) {
                for (int tintIndex = 0; tintIndex < sources.size(); tintIndex++) {
                    BlockTintSource source = sources.get(tintIndex);
                    if (source != null) {
                        setEncodedTint(tintValues, tintIndex, side, source.colorInWorld(sourceState, level, pos));
                    }
                }
                continue;
            }

            // A source block may itself use NeoForge's dynamic tint hook. Mirror those values into the facade's
            // legacy encoded tint slots as well, while avoiding recursion for a pipe facade of the pipe block itself.
            if (sourceState.getBlock() != state.getBlock()) {
                IntList dynamic = new IntArrayList();
                IClientBlockExtensions.of(sourceState).collectDynamicTintValues(sourceState, level, pos, dynamic);
                for (int tintIndex = 0; tintIndex < dynamic.size(); tintIndex++) {
                    setEncodedTint(tintValues, tintIndex, side, dynamic.getInt(tintIndex));
                }
            }
        }
    }

    private static void setEncodedTint(IntList values, int blockTintIndex, Direction side, int colour) {
        int encoded = blockTintIndex * Direction.values().length + side.ordinal();
        while (values.size() <= encoded) {
            values.add(-1);
        }
        values.set(encoded, colour);
    }
}
