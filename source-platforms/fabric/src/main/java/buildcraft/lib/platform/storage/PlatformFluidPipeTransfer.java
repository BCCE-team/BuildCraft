package buildcraft.lib.platform.storage;
// Loader boundary owner: net.fabricmc Transfer API semantics are delegated through PlatformStorage.

import javax.annotation.Nullable;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.internal.transfer.FabricFluidVariants;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.OperationScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric Transfer API adapter used by loader-neutral legacy fluid-pipe gameplay. */
public final class PlatformFluidPipeTransfer {
    private PlatformFluidPipeTransfer() {}

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        return level != null && pos != null && level.hasChunkAt(pos) && PlatformStorage.fluidTransfer(level, pos, side) != null;
    }

    public static FluidVolume extract(
        Level level,
        BlockPos pos,
        @Nullable BlockEntity tile,
        Direction side,
        @Nullable FluidMatcher matcher,
        @Nullable FluidVolume preferred,
        int min,
        int max,
        OperationMode mode
    ) {
        if (level == null || pos == null || !level.hasChunkAt(pos) || max <= 0 || min < 0 || min > max) return FluidVolume.empty();
        FluidTransferAccess access = PlatformStorage.fluidTransfer(level, pos, side);
        if (access == null) return FluidVolume.empty();

        FluidMatcher effective = matcher == null ? FluidMatcher.any() : matcher;
        if (preferred != null && !preferred.isEmpty()) {
            FluidMatcher preferredMatcher = FluidMatcher.exact(preferred.requireVariant());
            effective = preferredMatcher.and(effective);
        }

        FluidTransferResult probe;
        try (OperationScope scope = OperationScope.open(OperationMode.SIMULATE)) {
            probe = access.extract(effective, FluidAmount.of(max), scope);
        }
        if (probe.transferredAmount().milliBuckets() < min) return FluidVolume.empty();
        if (mode == OperationMode.SIMULATE) return probe.transferred();

        try (OperationScope scope = OperationScope.open(OperationMode.EXECUTE)) {
            FluidTransferResult result = access.extract(effective, FluidAmount.of(max), scope);
            if (result.transferredAmount().milliBuckets() < min) {
                scope.markFailed();
                return FluidVolume.empty();
            }
            return result.transferred();
        }
    }

    public static FluidAmount insert(
        Level level,
        BlockPos pos,
        @Nullable BlockEntity tile,
        Direction side,
        FluidVolume offered,
        OperationMode mode
    ) {
        if (level == null || pos == null || !level.hasChunkAt(pos) || offered == null || offered.isEmpty()) return FluidAmount.ZERO;
        FluidTransferAccess access = PlatformStorage.fluidTransfer(level, pos, side);
        if (access == null) return FluidAmount.ZERO;
        try (OperationScope scope = OperationScope.open(mode)) {
            return access.insert(offered, scope).transferredAmount();
        }
    }

    /** Evaluates an API matcher with Fabric's registry/tag context. */
    public static boolean matches(FluidMatcher matcher, FluidVolume candidate) {
        return matcher != null && candidate != null && !candidate.isEmpty()
            && matcher.matches(candidate.requireVariant(), FabricFluidVariants.MATCH_CONTEXT);
    }

    public static boolean equivalent(@Nullable FluidVolume first, @Nullable FluidVolume second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return (first == null || first.isEmpty()) && (second == null || second.isEmpty());
        }
        FluidVolume a = canonicalize(first.withAmount(FluidAmount.of(1)));
        FluidVolume b = canonicalize(second.withAmount(FluidAmount.of(1)));
        return !a.isEmpty() && !b.isEmpty()
            ? a.requireVariant().equals(b.requireVariant())
            : first.requireVariant().equals(second.requireVariant());
    }

    /** Normalizes accepted cross-loader SNBT payloads to Fabric's canonical component format. */
    public static FluidVolume canonicalize(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidVolume.empty();
        var nativeVariant = FabricFluidVariants.fromApi(volume.requireVariant()).orElse(null);
        if (nativeVariant == null) return volume;
        return FluidVolume.of(FabricFluidVariants.toApi(nativeVariant), volume.amount());
    }

    /** Reads the pre-Stage-5.5 Forge FluidStack NBT shape when a world is moved to Fabric. */
    public static FluidVolume readLegacyNbt(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return FluidVolume.empty();
        String idString = tag.contains("FluidName") ? tag.getString("FluidName")
            : tag.contains("FluidType") ? tag.getString("FluidType") : "";
        if (idString.isEmpty()) return FluidVolume.empty();
        ResourceLocation id;
        try {
            id = new ResourceLocation(idString);
        } catch (RuntimeException invalid) {
            return FluidVolume.empty();
        }
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return FluidVolume.empty();
        int amount = Math.max(1, tag.contains("Amount") ? tag.getInt("Amount") : 1);
        CompoundTag nbt = tag.contains("Tag") ? tag.getCompound("Tag") : null;
        var nativeVariant = nbt == null || nbt.isEmpty()
            ? net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid)
            : net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid, nbt);
        return FluidVolume.of(FabricFluidVariants.toApi(nativeVariant), amount);
    }

    public static Component displayName(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return Component.literal("empty");
        return Component.literal(volume.requireVariant().fluidId().toString());
    }

    /** Fluid shards are still a legacy item-format concern; Fabric transport itself must not invent Forge NBT. */
    public static void addFluidDrops(NonNullList<ItemStack> drops, FluidVolume volume) {
        // Deliberately no-op until the Fabric item/container representation is registered with the gameplay modules.
    }
}
