package buildcraft.lib.platform.storage;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.EnumPipePart;

import net.minecraft.core.Direction;

/**
 * Loader-neutral sided storage catalogue owned by a block entity or another composed object.
 *
 * <p>The catalogue describes which storage is available on each pipe part. Platform code is
 * responsible for exporting the returned views through Forge/NeoForge capabilities. Suppliers
 * are deliberately evaluated for every lookup so rotation, configuration and removal-sensitive
 * views keep the lifecycle they had before the platform boundary was introduced.</p>
 */
public final class StorageMap {
    private final Map<EnumPipePart, Supplier<? extends ItemStorage>> items = new EnumMap<>(EnumPipePart.class);
    private final Map<EnumPipePart, Supplier<? extends FluidStorage<?>>> fluids = new EnumMap<>(EnumPipePart.class);
    private final Map<EnumPipePart, Supplier<? extends EnergyStorage>> energy = new EnumMap<>(EnumPipePart.class);

    public void addItems(ItemStorage storage, EnumPipePart... parts) {
        addItems(() -> storage, parts);
    }

    public void addItems(Supplier<? extends ItemStorage> storage, EnumPipePart... parts) {
        put(items, storage, parts);
    }

    public void addItems(Function<Direction, ? extends ItemStorage> storage, EnumPipePart... parts) {
        putSided(items, storage, parts);
    }

    public void addFluids(FluidStorage<?> storage, EnumPipePart... parts) {
        addFluids(() -> storage, parts);
    }

    public void addFluids(Supplier<? extends FluidStorage<?>> storage, EnumPipePart... parts) {
        put(fluids, storage, parts);
    }

    public void addFluids(Function<Direction, ? extends FluidStorage<?>> storage, EnumPipePart... parts) {
        putSided(fluids, storage, parts);
    }

    public void addEnergy(EnergyStorage storage, EnumPipePart... parts) {
        addEnergy(() -> storage, parts);
    }

    public void addEnergy(Supplier<? extends EnergyStorage> storage, EnumPipePart... parts) {
        put(energy, storage, parts);
    }

    public void addEnergy(Function<Direction, ? extends EnergyStorage> storage, EnumPipePart... parts) {
        putSided(energy, storage, parts);
    }

    @Nullable
    public ItemStorage items(@Nullable Direction side) {
        return get(items, side);
    }

    @Nullable
    public FluidStorage<?> fluids(@Nullable Direction side) {
        return get(fluids, side);
    }

    @Nullable
    public EnergyStorage energy(@Nullable Direction side) {
        return get(energy, side);
    }

    private static <T> void put(
        Map<EnumPipePart, Supplier<? extends T>> map,
        Supplier<? extends T> value,
        EnumPipePart... parts
    ) {
        if (value == null || parts == null) return;
        for (EnumPipePart part : parts) {
            if (part != null) map.put(part, value);
        }
    }

    private static <T> void putSided(
        Map<EnumPipePart, Supplier<? extends T>> map,
        Function<Direction, ? extends T> value,
        EnumPipePart... parts
    ) {
        if (value == null || parts == null) return;
        for (EnumPipePart part : parts) {
            if (part != null) map.put(part, () -> value.apply(part.face));
        }
    }

    @Nullable
    private static <T> T get(Map<EnumPipePart, Supplier<? extends T>> map, @Nullable Direction side) {
        Supplier<? extends T> supplier = map.get(EnumPipePart.fromFacing(side));
        return supplier == null ? null : supplier.get();
    }
}
