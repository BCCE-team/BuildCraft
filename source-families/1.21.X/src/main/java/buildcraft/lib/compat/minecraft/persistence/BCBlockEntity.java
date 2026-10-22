//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.persistence;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.11 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//? }

/**
 * Vanilla persistence boundary for modern BCCE machines. Subclasses override BC hooks,
 * never version-dependent vanilla serialization signatures. Existing save layouts are retained:
 * flat on 1.21.1; common data at the root and machine data in bc_legacy on 1.21.11.
 * Pipe holders opt into root data because their already-shipped 1.21.11 format is flat.
 */
public abstract class BCBlockEntity extends BlockEntity {
    private static final String MACHINE_DATA = "bc_legacy";
    @Nullable private HolderLookup.Provider persistenceRegistries;

    protected BCBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected void readCommonData(BCValueInput input) {}
    protected void writeCommonData(BCValueOutput output) {}
    protected void readData(BCValueInput input) {}
    protected void writeData(BCValueOutput output) {}
    protected boolean storesMachineDataAtRoot() { return false; }
    protected boolean requiresPersistenceRegistries() { return true; }

//? if >=1.21.11 {
    @Override
    protected final void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        persistenceRegistries = input.lookup();
        CompoundTag root = input.read(CompoundValueCodec.INSTANCE).orElseGet(CompoundTag::new);
        BCValueInput common = new BCValueInput(root, persistenceRegistries);
        readCommonData(common);
        if (storesMachineDataAtRoot()) {
            readData(common);
        } else {
            common.findCompound(MACHINE_DATA).ifPresent(tag -> readData(new BCValueInput(tag, persistenceRegistries)));
        }
    }

    @Override
    protected final void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        HolderLookup.Provider registries = level == null ? persistenceRegistries : level.registryAccess();
        CompoundTag root = new CompoundTag();
        BCValueOutput common = new BCValueOutput(root, registries);
        writeCommonData(common);
        if (registries != null || !requiresPersistenceRegistries()) {
            if (registries != null) persistenceRegistries = registries;
            CompoundTag machine = storesMachineDataAtRoot() ? root : new CompoundTag();
            writeData(new BCValueOutput(machine, registries));
            if (machine != root && !machine.isEmpty()) root.put(MACHINE_DATA, machine);
        }
        output.store(CompoundValueCodec.INSTANCE, root);
    }
//? } else {
    @Override
    protected final void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        persistenceRegistries = registries;
        BCValueInput input = new BCValueInput(tag, registries);
        readCommonData(input);
        readData(input);
    }

    @Override
    protected final void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        persistenceRegistries = registries;
        BCValueOutput output = new BCValueOutput(tag, registries);
        writeCommonData(output);
        writeData(output);
    }
//? }
}
