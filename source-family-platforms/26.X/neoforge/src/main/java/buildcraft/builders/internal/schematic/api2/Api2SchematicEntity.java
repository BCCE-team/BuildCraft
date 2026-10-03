package buildcraft.builders.internal.schematic.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.schematic.EntitySchematicAdapter;
import buildcraft.api.v2.schematic.SchematicPlacementContext;
import buildcraft.api.v2.schematic.SchematicResult;
import buildcraft.api.v2.schematic.SnapshotElement;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.internal.schematic.legacy.SchematicEntityContext;
import buildcraft.lib.fluid.FuelApiBridge;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/** Internal compatibility shell for addon-owned API2 entity snapshot elements. */
public final class Api2SchematicEntity implements ISchematicEntity {
    private final SnapshotElement element;
    private final EntitySchematicAdapter adapter;

    public Api2SchematicEntity(SnapshotElement element, EntitySchematicAdapter adapter) {
        this.element = element;
        this.adapter = adapter;
    }

    public SnapshotElement element() { return element; }
    public EntitySchematicAdapter adapter() { return adapter; }

    @Override public ResourceLocation typeId() { return element.typeId(); }
    @Override public void init(SchematicEntityContext context) { }
    @Override public Vec3 getPos() { return adapter.relativePosition(element); }
    @Override public List<ItemStack> computeRequiredItems(Level level) {
        return adapter.requirements(element, new SchematicPlacementContext(level, BlockPos.ZERO, AutomationActor.unknown(), OperationMode.SIMULATE)).items();
    }
    @Override public List<FluidStack> computeRequiredFluids(Level level) {
        return adapter.requirements(element, new SchematicPlacementContext(level, BlockPos.ZERO, AutomationActor.unknown(), OperationMode.SIMULATE)).fluids().stream()
            .map(FuelApiBridge::stackOf).filter(stack -> !stack.isEmpty()).collect(Collectors.toList());
    }
    @Override public ISchematicEntity getRotated(Rotation rotation) { return new Api2SchematicEntity(adapter.rotate(element, rotation), adapter); }
    @Override public Entity build(BlockGetter world, BlockPos basePos) {
        if (!(world instanceof Level level)) return null;
        return place(level, basePos) ? findPlacedEntity(level, basePos) : null;
    }
    @Override public Entity buildWithoutChecks(BlockGetter world, BlockPos basePos) { return build(world, basePos); }
    @Override public CompoundTag serializeNBT() { return Api2SnapshotPersistence.write(element); }
    @Override public void deserializeNBT(CompoundTag nbt) { throw new UnsupportedOperationException("API2 snapshot wrappers are immutable"); }

    public boolean place(Level level, BlockPos basePos) {
        SchematicResult result = adapter.place(element, new SchematicPlacementContext(level, basePos, basePos, AutomationActor.unknown(), OperationMode.EXECUTE));
        return result.status() == SchematicResult.Status.SUCCESS;
    }

    private Entity findPlacedEntity(Level level, BlockPos basePos) {
        Vec3 expected = Vec3.atLowerCornerOf(basePos).add(getPos());
        return level.getEntities(null, new net.minecraft.world.phys.AABB(expected, expected).inflate(1.0)).stream().findFirst().orElse(null);
    }
}

