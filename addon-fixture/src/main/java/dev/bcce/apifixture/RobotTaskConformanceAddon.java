package dev.bcce.apifixture;

import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.robot.RobotTask;
import buildcraft.api.v2.robot.RobotTaskContext;
import buildcraft.api.v2.robot.RobotTaskLifecycle;
import buildcraft.api.v2.robot.RobotTaskResult;
import buildcraft.api.v2.robot.RobotTaskType;
import java.nio.ByteBuffer;
import net.minecraft.resources.ResourceLocation;

/** A stateful robot task distributed by an addon independently from BCCE's scheduler. */
public final class RobotTaskConformanceAddon implements RobotTask, RobotTaskLifecycle {
    public static final ResourceLocation ID = ResourceLocation.tryParse("api_fixture:inspect_area");
    private static final ResourceLocation FORMAT = ResourceLocation.tryParse("api_fixture:inspection_progress");
    public static final RobotTaskType<RobotTaskConformanceAddon> TYPE = new RobotTaskType<>(
        ID, RobotTaskConformanceAddon.class,
        PersistentType.<RobotTaskConformanceAddon, OpaqueData>builder(ID, 1, new ApiCodec<>() {
            @Override public CodecResult<OpaqueData> encode(RobotTaskConformanceAddon task) {
                return CodecResult.success(new OpaqueData(FORMAT,
                    ByteBuffer.allocate(Integer.BYTES).putInt(task.progress).array()));
            }
            @Override public CodecResult<RobotTaskConformanceAddon> decode(OpaqueData value) {
                if (!FORMAT.equals(value.format()) || value.size() != Integer.BYTES)
                    return CodecResult.failure("Invalid inspection progress");
                int progress = ByteBuffer.wrap(value.bytes()).getInt();
                return progress < 0 || progress > 3 ? CodecResult.failure("Invalid inspection counter")
                    : CodecResult.success(new RobotTaskConformanceAddon(progress));
            }
        }).build());

    private int progress;
    private static int ticks;
    private static int resumes;
    private static int completions;

    public RobotTaskConformanceAddon(int progress) { this.progress = progress; }
    public int progress() { return progress; }
    public static int ticks() { return ticks; }
    public static int resumes() { return resumes; }
    public static int completions() { return completions; }

    @Override public ResourceLocation typeId() { return ID; }
    @Override public RobotTaskResult tick(RobotTaskContext context) {
        progress++;
        ticks++;
        return progress >= 3 ? RobotTaskResult.complete() : RobotTaskResult.running();
    }
    @Override public void onResumed(RobotTaskContext context) { resumes++; }
    @Override public void onCompleted(RobotTaskContext context) { completions++; }
}
