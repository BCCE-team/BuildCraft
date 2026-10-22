package buildcraft.api.v2.tool;

import java.util.Objects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

/** Details of a successfully handled BuildCraft wrench interaction. */
public record WrenchUseContext(Player player, InteractionHand hand, ItemStack stack, HitResult hit) {
    public WrenchUseContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hand, "hand");
        stack = Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(hit, "hit");
    }
}
