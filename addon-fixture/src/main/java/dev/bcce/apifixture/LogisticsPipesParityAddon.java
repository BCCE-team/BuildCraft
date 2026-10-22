package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.pipe.ItemTransitPayload;
import buildcraft.api.v2.request.ItemRequest;
import buildcraft.api.v2.request.ManagedRequestProvider;
import buildcraft.api.v2.request.RequestLedger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** A porting fixture for LP-style suppliers, reservations and persistent route metadata. */
public final class LogisticsPipesParityAddon implements ManagedRequestProvider {
    public static final ResourceLocation REQUEST_ID = ResourceLocation.tryParse("lp_fixture:inventory_supply");
    private static final String ROUTE_KEY = "lp_fixture:destination";
    private final RequestLedger ledger = new RequestLedger();
    private int stock;
    private int capacity;
    private long gameTime;

    public LogisticsPipesParityAddon(int capacity, long gameTime) {
        if (capacity < 0) throw new IllegalArgumentException("negative capacity");
        this.capacity = capacity;
        this.gameTime = gameTime;
    }

    public void request(int quantity, AutomationActor owner, long duration) {
        ledger.open(new ItemRequest(REQUEST_ID, ItemMatcher.any(), quantity, quantity, 10),
            owner, gameTime, duration);
    }

    public RequestLedger.PromiseView promise(UUID token, int quantity, AutomationActor owner, long duration) {
        return ledger.reserve(REQUEST_ID, token, quantity, owner, gameTime, duration).orElseThrow();
    }

    public RequestLedger.DeliveryResult accept(UUID token, UUID receipt, ItemStack stack,
                                                AutomationActor actor, OperationMode mode) {
        return ledger.deliver(this, token, receipt, stack, actor, mode);
    }

    public int stock() { return stock; }
    public void time(long gameTime) { this.gameTime = gameTime; }
    public byte[] saveLedger() { return ledger.save(); }
    public boolean loadLedger(byte[] data) { return ledger.loadPreserving(data); }
    public long requestTime() { return gameTime; }
    public RequestLedger requestLedger() { return ledger; }

    @Override
    public ItemTransferResult insertRequested(ResourceLocation requestId, ItemStack stack, OperationMode mode) {
        Objects.requireNonNull(requestId, "requestId");
        if (!REQUEST_ID.equals(requestId)) return ItemTransferResult.nothing(stack.getCount());
        int accepted = Math.min(stack.getCount(), capacity - stock);
        if (mode == OperationMode.EXECUTE) stock += accepted;
        return ItemTransferResult.ofInsertion(stack, accepted);
    }

    public static ItemTransitPayload route(ItemTransitPayload payload, String destination) {
        return Objects.requireNonNull(payload, "payload").with(ROUTE_KEY,
            Objects.requireNonNull(destination, "destination").getBytes(StandardCharsets.UTF_8));
    }

    public static String destination(ItemTransitPayload payload) {
        return new String(payload.get(ROUTE_KEY).orElseThrow(), StandardCharsets.UTF_8);
    }
}
