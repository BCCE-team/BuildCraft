package buildcraft.robotics.zone;

import net.minecraft.core.BlockPos;

/** Stable block-relative 112x112 footprint. Its origin need not align with chunks after a move. */
public record ProcessorDiskLayout(int offsetX, int offsetZ) {
    public static final int WIDTH = 112, LAYERS = 16, BYTES_PER_LAYER = WIDTH * WIDTH / 8;
    public static final int TOTAL_BYTES = LAYERS * BYTES_PER_LAYER;
    public ProcessorDiskLayout {
        if (offsetX < -63 || offsetX > -48 || offsetZ < -63 || offsetZ > -48)
            throw new IllegalArgumentException("Invalid HDD footprint");
    }
    public static ProcessorDiskLayout at(BlockPos origin) {
        return new ProcessorDiskLayout(-48 - Math.floorMod(origin.getX(), 16), -48 - Math.floorMod(origin.getZ(), 16));
    }
    public int minX(BlockPos p) { return p.getX() + offsetX; }
    public int minZ(BlockPos p) { return p.getZ() + offsetZ; }
    public boolean contains(BlockPos p, int x, int z) {
        long dx = (long) x - minX(p), dz = (long) z - minZ(p);
        return dx >= 0 && dx < WIDTH && dz >= 0 && dz < WIDTH;
    }
    public boolean intersectsChunk(BlockPos p, int x, int z) {
        return x >= (minX(p) >> 4) && x <= ((minX(p) + WIDTH - 1) >> 4)
                && z >= (minZ(p) >> 4) && z <= ((minZ(p) + WIDTH - 1) >> 4);
    }
    public ZonePlan clip(ZonePlan plan, BlockPos origin) {
        ZonePlan result = new ZonePlan();
        if (plan == null) return result;
        int x0 = minX(origin), z0 = minZ(origin);
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++)
            if (plan.get(x0 + x, z0 + z)) result.set(x0 + x, z0 + z, true);
        return result;
    }
    public byte[] encode(ZonePlan[] zones, BlockPos origin) {
        byte[] data = new byte[TOTAL_BYTES];
        int x0 = minX(origin), z0 = minZ(origin);
        for (int layer = 0; layer < LAYERS; layer++) {
            ZonePlan zone = zones[layer];
            if (zone == null || zone.getChunkMapping().isEmpty()) continue;
            for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
                int bit = (layer * WIDTH + z) * WIDTH + x;
                if (zone.get(x0 + x, z0 + z)) data[bit >>> 3] |= (byte) (1 << (bit & 7));
            }
        }
        int length = data.length;
        while (length > 0 && data[length - 1] == 0) length--;
        return java.util.Arrays.copyOf(data, length);
    }
    public ZonePlan[] decode(byte[] data, BlockPos origin) {
        if (data.length > TOTAL_BYTES) throw new IllegalArgumentException("Invalid HDD layer data");
        data = java.util.Arrays.copyOf(data, TOTAL_BYTES);
        ZonePlan[] zones = new ZonePlan[LAYERS];
        int x0 = minX(origin), z0 = minZ(origin);
        for (int layer = 0; layer < LAYERS; layer++) {
            ZonePlan zone = new ZonePlan(); zones[layer] = zone;
            for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
                int bit = (layer * WIDTH + z) * WIDTH + x;
                if ((data[bit >>> 3] & (1 << (bit & 7))) != 0) zone.set(x0 + x, z0 + z, true);
            }
        }
        return zones;
    }
}
