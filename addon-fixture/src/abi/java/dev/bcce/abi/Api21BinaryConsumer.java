package dev.bcce.abi;

import buildcraft.api.v2.ApiLifecycle;
import buildcraft.api.v2.ApiVersion;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import java.nio.file.Path;

/** Compiled ONLY against the immutable API 2.1 release. No Minecraft bootstrap needed. */
public final class Api21BinaryConsumer implements MjPort {
    @Override public MjTransferResult insert(MjAmount offered, OperationMode mode) {
        return MjTransferResult.of(offered, offered);
    }
    @Override public MjTransferResult extract(MjAmount requested, OperationMode mode) {
        return MjTransferResult.none(requested);
    }
    @Override public MjAmount stored() { return MjAmount.ZERO; }
    @Override public MjAmount capacity() { return MjAmount.ofMj(8); }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path loaded = Path.of(BuildCraftApi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(loaded.toRealPath().equals(Path.of(args[1]).toRealPath()), "Loaded wrong BuildCraft JAR: " + loaded);
        ApiVersion version = BuildCraftApi.VERSION;
        require(version.toString().equals(args[0]) && version.major() == 2
                && version.compareTo(new ApiVersion(2, 1, 0)) >= 0, "Unexpected API version: " + version);
        MjPort port = new Api21BinaryConsumer();
        MjAmount offered = MjAmount.ofMj(2).plus(MjAmount.ofMicro(3));
        require(port.canInsert() && port.canExtract(), "Inherited default methods");
        require(port.insert(offered, OperationMode.SIMULATE).completed(), "Old implementation invocation");
        require(port.extract(offered, OperationMode.EXECUTE).remainder().equals(offered), "Result linkage");
        require(port.capacity().microMj() == 8_000_000L && port.stored().isZero(), "Amount linkage");
        require(OperationMode.SIMULATE.isSimulation() && !OperationMode.EXECUTE.isSimulation(), "Enum semantics");
        require(ApiLifecycle.valueOf("FROZEN") == ApiLifecycle.FROZEN, "Lifecycle enum linkage");
        System.out.println("Old API 2.1 binary consumer passed against " + version);
    }
}
