//? source if >=1.21.11
package buildcraft.lib.compat.transfer;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;

/** One identity-shared journal per entity, never per short-lived transactor wrapper. */
public final class EntityTransferStorage {
    private static final Map<ItemEntity, ItemState> ITEMS = new WeakHashMap<>();
    private static final Map<AbstractArrow, ArrowState> ARROWS = new WeakHashMap<>();
    private EntityTransferStorage() {}

    public static synchronized ItemState item(ItemEntity entity) {
        return ITEMS.computeIfAbsent(entity, ItemState::new);
    }
    public static synchronized ArrowState arrow(AbstractArrow entity) {
        return ARROWS.computeIfAbsent(entity, ArrowState::new);
    }

    private record ItemSnapshot(boolean pending, ItemStack stack) {}
    public static final class ItemState {
        private final WeakReference<ItemEntity> entity;
        private boolean pending;
        private ItemStack stack = ItemStack.EMPTY;
        private final TransferJournal<ItemSnapshot> journal = new TransferJournal<>(
            () -> new ItemSnapshot(pending, stack.copy()),
            old -> { pending = old.pending(); stack = old.stack().copy(); }, old -> commit());
        private ItemState(ItemEntity entity) { this.entity = new WeakReference<>(entity); }
        public ItemStack get() {
            ItemEntity value = entity.get();
            return pending ? stack : value == null || value.isRemoved() ? ItemStack.EMPTY : value.getItem();
        }
        public void set(ItemStack remainder) {
            journal.record();
            stack = remainder.copy();
            pending = true;
        }
        private void commit() {
            ItemEntity value = entity.get();
            if (value != null && !value.isRemoved()) {
                if (stack.isEmpty()) value.discard();
                else value.setItem(stack.copy());
            }
            pending = false;
            stack = ItemStack.EMPTY;
        }
    }

    public static final class ArrowState {
        private final WeakReference<AbstractArrow> entity;
        private boolean extracted;
        private final TransferJournal<Boolean> journal = new TransferJournal<>(() -> extracted,
            old -> extracted = old, old -> commit());
        private ArrowState(AbstractArrow entity) { this.entity = new WeakReference<>(entity); }
        private void commit() {
            AbstractArrow value = entity.get();
            if (extracted && value != null) value.discard();
            extracted = false;
        }
        public boolean available() { return !extracted; }
        public void extract() { journal.record(); extracted = true; }
    }
}
