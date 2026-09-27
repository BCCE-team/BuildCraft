package buildcraft.lib.platform.storage;
// Loader boundary owner: net.minecraftforge fluid capability and FluidStack representation.

import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FluidDropRuntime;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.transport.internal.pipe.IFlowFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Forge adapter for loader-neutral legacy fluid-pipe gameplay.
 *
 * <p>All native FluidStack/capability semantics stay here. The flow itself only sees {@link FluidVolume} and
 * {@link OperationMode}.</p>
 */
public final class PlatformFluidPipeTransfer {
    public static final ResourceLocation FORGE_NBT_FORMAT =
        new ResourceLocation("buildcraft", "forge_fluid_stack_snbt_v1");
    private static final ResourceLocation FABRIC_NBT_FORMAT =
        new ResourceLocation("buildcraft", "fabric_transfer_snbt_v1");

    private PlatformFluidPipeTransfer() {}

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        return level != null && pos != null && level.hasChunkAt(pos) && PlatformStorage.fluids(level, pos, side) != null;
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
        FluidStorage<FluidStack> storage = PlatformStorage.fluids(level, pos, side);
        if (storage == null) return FluidVolume.empty();

        Predicate<FluidStack> predicate = stack -> matches(stack, matcher, preferred);
        FluidStack probe = extractOnce(storage, predicate, max, true);
        if (probe.isEmpty() || probe.getAmount() < min) return FluidVolume.empty();
        if (mode == OperationMode.SIMULATE) return toVolume(probe);

        // Execute the exact probed variant. Legacy Forge capabilities are not transactional, so we deliberately
        // revalidate immediately before the mutation and never drain a different tank/variant as a fallback.
        int requested = Math.min(max, probe.getAmount());
        FluidStack executed = drainExact(storage, probe, requested, false);
        if (executed.isEmpty() || executed.getAmount() < min) return FluidVolume.empty();
        return toVolume(executed);
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
        FluidStorage<FluidStack> storage = PlatformStorage.fluids(level, pos, side);
        if (storage == null) return FluidAmount.ZERO;
        FluidStack nativeStack = toNative(offered);
        if (nativeStack.isEmpty()) return FluidAmount.ZERO;
        int filled = storage.fill(nativeStack, mode == OperationMode.SIMULATE);
        return FluidAmount.of(Math.max(0, Math.min(nativeStack.getAmount(), filled)));
    }

    private static FluidStack extractOnce(
        FluidStorage<FluidStack> storage, Predicate<FluidStack> predicate, int max, boolean simulate
    ) {
        if (storage instanceof FilteredFluidStorage<FluidStack> filtered) {
            FluidStack result = filtered.drain(predicate, max, simulate);
            return result == null ? FluidStack.EMPTY : result;
        }
        for (int tank = 0; tank < storage.getTanks(); tank++) {
            FluidStack contents = storage.getFluidInTank(tank);
            if (contents == null || contents.isEmpty() || !predicate.test(contents)) continue;
            FluidStack result = drainExact(storage, contents, max, simulate);
            if (!result.isEmpty()) return result;
        }
        return FluidStack.EMPTY;
    }

    private static FluidStack drainExact(
        FluidStorage<FluidStack> storage, FluidStack template, int max, boolean simulate
    ) {
        FluidStack request = template.copy();
        request.setAmount(Math.min(max, Math.max(0, template.getAmount())));
        FluidStack drained = storage.drain(request, simulate);
        if (drained == null || drained.isEmpty()) return FluidStack.EMPTY;
        if (!FluidCompatRegistry.areEquivalent(template, drained)) {
            throw new IllegalStateException("Fluid endpoint drained a different variant than it advertised");
        }
        return drained;
    }

    private static boolean matches(
        FluidStack stack, @Nullable FluidMatcher matcher, @Nullable FluidVolume preferred
    ) {
        if (stack == null || stack.isEmpty()) return false;
        FluidVolume candidate = toVolume(stack);
        if (candidate.isEmpty()) return false;
        if (preferred != null && !preferred.isEmpty() && !equivalent(preferred, candidate)) return false;
        return matcher == null || matcher.matches(candidate.requireVariant(), FuelApiBridge.MATCH_CONTEXT);
    }

    /** Evaluates an API matcher with the Forge tag context. */
    public static boolean matches(FluidMatcher matcher, FluidVolume candidate) {
        return matcher != null && candidate != null && !candidate.isEmpty()
            && matcher.matches(candidate.requireVariant(), FuelApiBridge.MATCH_CONTEXT);
    }

    /** Adapts the existing Forge fluid-filter contract without leaking it into common pipe gameplay. */
    public static FluidMatcher matcher(IFluidFilter filter) {
        if (filter == null) return FluidMatcher.none();
        return (variant, context) -> {
            FluidStack stack = toNative(FluidVolume.of(variant, 1));
            return !stack.isEmpty() && filter.matches(stack);
        };
    }

    public static boolean equivalent(@Nullable FluidVolume first, @Nullable FluidVolume second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return (first == null || first.isEmpty()) && (second == null || second.isEmpty());
        }
        FluidStack a = toNative(first.withAmount(FluidAmount.of(1)));
        FluidStack b = toNative(second.withAmount(FluidAmount.of(1)));
        return !a.isEmpty() && !b.isEmpty()
            ? FluidCompatRegistry.areEquivalent(a, b)
            : first.requireVariant().equals(second.requireVariant());
    }

    public static FluidVolume canonicalize(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidVolume.empty();
        FluidStack nativeStack = toNative(volume);
        if (nativeStack.isEmpty()) return volume;
        FluidStack canonical = FluidCompatRegistry.canonicalize(nativeStack);
        return canonical == null || canonical.isEmpty() ? FluidVolume.empty() : toVolume(canonical);
    }

    public static FluidVolume toVolume(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getFluid() == Fluids.EMPTY || stack.getAmount() <= 0) {
            return FluidVolume.empty();
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) return FluidVolume.empty();
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) return FluidVolume.of(buildcraft.api.v2.fluid.FluidVariant.of(id), stack.getAmount());
        FluidComponentPayload payload = FluidComponentPayload.of(
            FORGE_NBT_FORMAT,
            new StringTagVisitor().visit(tag).getBytes(StandardCharsets.UTF_8)
        );
        return FluidVolume.of(buildcraft.api.v2.fluid.FluidVariant.of(id, payload), stack.getAmount());
    }

    public static FluidStack toNative(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidStack.EMPTY;
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(volume.requireVariant().fluidId());
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        long amount = volume.amount().milliBuckets();
        if (amount <= 0 || amount > Integer.MAX_VALUE) return FluidStack.EMPTY;
        FluidStack stack = new FluidStack(fluid, (int) amount);
        FluidComponentPayload components = volume.requireVariant().components();
        if (!components.isEmpty()) {
            ResourceLocation format = components.formatId().orElse(null);
            if (!FORGE_NBT_FORMAT.equals(format) && !FABRIC_NBT_FORMAT.equals(format)) return FluidStack.EMPTY;
            try {
                stack.setTag(TagParser.parseTag(new String(components.copyCanonicalBytes(), StandardCharsets.UTF_8)));
            } catch (CommandSyntaxException malformed) {
                return FluidStack.EMPTY;
            }
        }
        return stack;
    }

    /** Reads pre-Stage-5.5 Forge FluidStack NBT for save compatibility. */
    public static FluidVolume readLegacyNbt(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return FluidVolume.empty();
        FluidStack stack = FluidStack.loadFluidStackFromNBT(tag);
        return toVolume(stack);
    }

    public static Component displayName(FluidVolume volume) {
        FluidStack stack = toNative(volume == null || volume.isEmpty()
            ? FluidVolume.empty()
            : volume.withAmount(FluidAmount.of(1)));
        return stack.isEmpty() ? Component.literal("empty") : stack.getDisplayName();
    }

    public static void addFluidDrops(NonNullList<ItemStack> drops, FluidVolume volume) {
        FluidStack stack = toNative(volume);
        if (!stack.isEmpty()) FluidDropRuntime.addFluidDrops(drops, stack);
    }

    /** Forge capability shell for the common insert-only pipe section contract. */
    public static IFluidHandler expose(IFlowFluid flow, Direction side) {
        return new IFluidHandler() {
            @Override public int getTanks() { return 1; }

            @Override
            public FluidStack getFluidInTank(int tank) {
                return tank == 0 ? toNative(flow.getFluidInSection(side)) : FluidStack.EMPTY;
            }

            @Override public int getTankCapacity(int tank) { return tank == 0 ? flow.getFluidSectionCapacity() : 0; }

            @Override
            public boolean isFluidValid(int tank, FluidStack stack) {
                return tank == 0 && flow.isFluidValidForSection(side, toVolume(stack));
            }

            @Override
            public int fill(FluidStack resource, FluidAction action) {
                return flow.insertFluidsExternal(
                    toVolume(resource), side,
                    action.execute() ? OperationMode.EXECUTE : OperationMode.SIMULATE
                );
            }

            @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
            @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
        };
    }
}
