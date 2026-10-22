package buildcraft.lib.internal.enums;

import java.util.Locale;
import java.util.function.BiFunction;

import buildcraft.lib.internal.properties.BuildCraftProperties;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public enum EnumSpring implements StringRepresentable {
    WATER(5, -1, Blocks.WATER.defaultBlockState()),
    OIL(6000, 8, null); // Set in BuildCraftEnergy

    public static final EnumSpring[] VALUES = values();

    public final int tickRate, chance;
    public BlockState liquidBlock;
    public boolean canGen = true;
    public BiFunction<BlockPos, BlockState, BlockEntity> tileConstructor;

    private final String lowerCaseName = name().toLowerCase(Locale.ROOT);

    EnumSpring(int tickRate, int chance, BlockState liquidBlock) {
        this.tickRate = tickRate;
        this.chance = chance;
        this.liquidBlock = liquidBlock;
    }

    public static EnumSpring fromState(BlockState state) {
        return state.getValue(BuildCraftProperties.SPRING_TYPE);
    }

    public String getSerializedName() {
        return lowerCaseName;
    }
}
