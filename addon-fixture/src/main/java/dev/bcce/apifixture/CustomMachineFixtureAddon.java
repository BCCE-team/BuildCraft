package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.machine.MachineActionComponent;
import buildcraft.api.v2.machine.MachineComponent;
import buildcraft.api.v2.machine.MachineComponentLifecycle;
import buildcraft.api.v2.machine.MachineComponentPresentation;
import buildcraft.api.v2.machine.MachineComponentType;
import buildcraft.api.v2.machine.MachineExecutionContext;
import buildcraft.api.v2.machine.MachineTickComponent;
import buildcraft.api.v2.machine.MachineWorkComponent;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.WorkState;
import buildcraft.api.v2.machine.WorkStatus;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.persistence.SchemaMigration;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Minimal API-only example for an addon block entity to host, save and display. */
public final class CustomMachineFixtureAddon {
    public static final ResourceLocation TYPE = id("fixture:press");
    public static final ResourceLocation COMPONENT = id("fixture:press_counter");
    public static final ResourceLocation OLD_COMPONENT = id("fixture:old_press_counter");
    public static final ResourceLocation FORMAT = id("fixture:int");
    public static final ResourceLocation RESET = id("fixture:reset");

    private CustomMachineFixtureAddon() { }
    private static ResourceLocation id(String value) { return ResourceLocation.tryParse(value); }
    public record CounterView(int completed) { }

    private static final ApiCodec<Press, OpaqueData> STORAGE = new ApiCodec<>() {
        public CodecResult<Press> decode(OpaqueData source) {
            if (!source.format().equals(FORMAT) || source.size() != 4) return CodecResult.failure("Invalid press state");
            return CodecResult.success(new Press(ByteBuffer.wrap(source.bytes()).getInt()));
        }
        public CodecResult<OpaqueData> encode(Press component) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(component.completed).array()));
        }
    };
    private static final ApiCodec<CounterView, OpaqueData> PRESENTATION = new ApiCodec<>() {
        public CodecResult<CounterView> decode(OpaqueData data) {
            if (!FORMAT.equals(data.format()) || data.size() != 4) return CodecResult.failure("Invalid menu state");
            return CodecResult.success(new CounterView(ByteBuffer.wrap(data.bytes()).getInt()));
        }
        public CodecResult<OpaqueData> encode(CounterView value) {
            return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(value.completed()).array()));
        }
    };
    public static final MachineComponentType<Press> PRESS = new MachineComponentType<>(COMPONENT,
        PersistentType.<Press, OpaqueData>builder(COMPONENT, 1, STORAGE)
            .alias(OLD_COMPONENT)
            .migration(new SchemaMigration<>() {
                public int fromVersion() { return 0; }
                public int toVersion() { return 1; }
                public CodecResult<OpaqueData> migrate(OpaqueData data) {
                    if (data.size() != 4 || !FORMAT.equals(data.format())) return CodecResult.failure("Invalid old state");
                    int old = ByteBuffer.wrap(data.bytes()).getInt();
                    return CodecResult.success(new OpaqueData(FORMAT, ByteBuffer.allocate(4).putInt(old + 1).array()));
                }
            }).build(),
        context -> new Press(0),
        new MachineComponentPresentation<>(
            PersistentType.<CounterView, OpaqueData>builder(COMPONENT, 1, PRESENTATION).build(),
            component -> new CounterView(component.completed)));
    public static final MachineType MACHINE = MachineType.builder(TYPE).component(PRESS.id()).build();

    /** The actual inventory and MJ buffers belong to the addon block entity, not to the machine API. */
    public static final class Press implements MachineComponent, MachineTickComponent,
        MachineComponentLifecycle, MachineActionComponent, MachineWorkComponent {
        private int completed;
        private MachineExecutionContext machine;
        public Press(int completed) { this.completed = completed; }
        @Override public ResourceLocation typeId() { return COMPONENT; }
        public int completed() { return completed; }
        @Override public boolean hasWork(MachineExecutionContext context) { return true; }
        @Override public void attached(MachineExecutionContext context) { this.machine = context; }
        @Override public void tick(MachineExecutionContext context) {
            MjPort energy = context.mjPort(Direction.UP).orElse(null);
            ItemPort products = context.itemPort(Direction.DOWN).orElse(null);
            if (energy == null || products == null) {
                context.setWorkStatus(new WorkStatus(WorkState.BLOCKED, 0, "Ports missing"));
                return;
            }
            MjAmount cost = MjAmount.ofMj(1);
            ItemStack output = new ItemStack(Items.IRON_INGOT, 1);
            if (!energy.extract(cost, OperationMode.SIMULATE).completed()
                || !products.insert(output, OperationMode.SIMULATE).completed()) {
                context.setWorkStatus(new WorkStatus(WorkState.BLOCKED, 0, "Resources missing"));
                return;
            }
            if (!energy.extract(cost, OperationMode.EXECUTE).completed()) return;
            if (!products.insert(output, OperationMode.EXECUTE).completed()) {
                // This example uses same-tick, owner-thread-confined ports; a real recipe adapter
                // should reserve both outputs and inputs before committing any multi-port recipe.
                context.setWorkStatus(new WorkStatus(WorkState.ERROR, 0, "Output changed during commit"));
                return;
            }
            completed++;
            context.setWorkStatus(new WorkStatus(WorkState.RUNNING, 1, "Pressing"));
            context.markChanged();
        }
        @Override public boolean handleAction(MachineExecutionContext ctx, Player player,
            ResourceLocation action, byte[] payload) {
            if (!action.equals(RESET) || payload.length != 0) return false;
            completed = 0;
            ctx.setWorkStatus(new WorkStatus(WorkState.IDLE, 0, ""));
            return true;
        }
        @Override public void addDrops(List<ItemStack> drops) {
            // Components may drop their own inventories; here the processing record has no items.
        }
    }
}
