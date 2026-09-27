package buildcraft.transport.pipe.flow;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Stable loader-neutral on-disk/network representation for legacy pipe fluid identity. */
final class FluidPipeData {
    private static final String MARKER = "bcFluidVolume";
    private static final String ID = "id";
    private static final String AMOUNT = "amount";
    private static final String COMPONENT_FORMAT = "componentFormat";
    private static final String COMPONENT_DATA = "componentData";

    private FluidPipeData() {}

    static CompoundTag write(FluidVolume volume) {
        CompoundTag tag = new CompoundTag();
        if (volume == null || volume.isEmpty()) return tag;
        tag.putBoolean(MARKER, true);
        FluidVariant variant = volume.requireVariant();
        tag.putString(ID, variant.fluidId().toString());
        tag.putLong(AMOUNT, volume.amount().milliBuckets());
        FluidComponentPayload components = variant.components();
        if (!components.isEmpty()) {
            tag.putString(COMPONENT_FORMAT, components.formatId().orElseThrow().toString());
            tag.putByteArray(COMPONENT_DATA, components.copyCanonicalBytes());
        }
        return tag;
    }

    static FluidVolume read(CompoundTag tag) {
        if (tag == null || tag.isEmpty() || !tag.getBoolean(MARKER)) return FluidVolume.empty();
        String rawId = tag.getString(ID);
        long amount = Math.max(0L, tag.getLong(AMOUNT));
        if (rawId.isEmpty() || amount <= 0) return FluidVolume.empty();
        try {
            ResourceLocation id = new ResourceLocation(rawId);
            FluidComponentPayload components = FluidComponentPayload.empty();
            String rawFormat = tag.getString(COMPONENT_FORMAT);
            byte[] bytes = tag.getByteArray(COMPONENT_DATA);
            if (!rawFormat.isEmpty() || bytes.length > 0) {
                if (rawFormat.isEmpty() || bytes.length == 0) return FluidVolume.empty();
                components = FluidComponentPayload.of(new ResourceLocation(rawFormat), bytes);
            }
            return FluidVolume.of(FluidVariant.of(id, components), FluidAmount.of(amount));
        } catch (RuntimeException malformed) {
            return FluidVolume.empty();
        }
    }
}
