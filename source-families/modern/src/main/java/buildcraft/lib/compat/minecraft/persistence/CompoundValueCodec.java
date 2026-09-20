//? source if >=1.21.11
package buildcraft.lib.compat.minecraft.persistence;

import java.util.stream.Stream;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.nbt.CompoundTag;

/** Flatten a staged BCCE compound into ValueIO without nesting or changing its value types. */
final class CompoundValueCodec extends MapCodec<CompoundTag> {
    static final CompoundValueCodec INSTANCE = new CompoundValueCodec();
    private CompoundValueCodec() {}

    @Override
    public <T> DataResult<CompoundTag> decode(DynamicOps<T> ops, MapLike<T> input) {
        return CompoundTag.CODEC.parse(ops, ops.createMap(input.entries()));
    }

    @Override
    public <T> RecordBuilder<T> encode(CompoundTag input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        DataResult<MapLike<T>> encoded = CompoundTag.CODEC.encodeStart(ops, input).flatMap(ops::getMap);
        prefix.withErrorsFrom(encoded);
        encoded.result().ifPresent(values -> values.entries().forEach(entry -> prefix.add(entry.getFirst(), entry.getSecond())));
        return prefix;
    }

    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) { return Stream.empty(); }
}
