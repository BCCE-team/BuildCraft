package buildcraft.silicon.container;

import java.io.IOException;

import buildcraft.lib.gui.ContainerBCTile;
import buildcraft.lib.gui.slot.SlotBase;
//? if mc_26_x && (>=26.3) {

//? } else {
import buildcraft.lib.gui.slot.SlotDisplay;
//?}
import buildcraft.lib.gui.slot.SlotOutput;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconGuis;
import buildcraft.silicon.tile.TileProgrammingTable_Neptune;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import buildcraft.lib.net.BCNetworkSide;
//? if mc_26_x && (>=26.3) {
import buildcraft.lib.gui.ItemProvider;
import buildcraft.silicon.compat.SiliconDisplayContainer263;
//? } else {
import net.neoforged.neoforge.items.IItemHandler;
//?}
import buildcraft.lib.net.BCPacketContext;

public class ContainerProgrammingTable extends ContainerBCTile<TileProgrammingTable_Neptune> {
    public static final int NET_SELECT_OPTION = NET_DATA;

    public ContainerProgrammingTable(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, new ItemHandlerSimple(1), new ItemHandlerSimple(1),
//? if mc_26_x && (>=26.3) {
                new ItemProvider(index -> net.minecraft.world.item.ItemStack.EMPTY, TileProgrammingTable_Neptune.OPTION_COUNT), createLevelAccess(playerInventory, buf));
//? } else {
                new ItemHandlerSimple(TileProgrammingTable_Neptune.OPTION_COUNT), createLevelAccess(playerInventory, buf));
//?}
    }

    public ContainerProgrammingTable(int containerId, Inventory playerInventory, IItemHandlerAdv invInput,
//? if mc_26_x && (>=26.3) {
                                     IItemHandlerAdv invOutput, ItemProvider options,
//? } else {
                                     IItemHandlerAdv invOutput, IItemHandler options,
//?}
                                     ContainerLevelAccess access) {
        super(BCSiliconGuis.MENU_PROGRAMMING_TABLE.get(), playerInventory, containerId, access);

        addSlot(new SlotBase(invInput, 0, 8, 36));
        addSlot(new SlotOutput(invOutput, 0, 8, 90));

//? if mc_26_x && (>=26.3) {
        SiliconDisplayContainer263 optionSlots = new SiliconDisplayContainer263(options);
//? } else {
//?}
        for (int y = 0; y < TileProgrammingTable_Neptune.HEIGHT; y++) {
            for (int x = 0; x < TileProgrammingTable_Neptune.WIDTH; x++) {
                int index = x + y * TileProgrammingTable_Neptune.WIDTH;
//? if mc_26_x && (>=26.3) {
                addSlot(optionSlots.slot( index, 43 + x * 18, 36 + y * 18));
//? } else {
                addSlot(new SlotDisplay(options, index, 43 + x * 18, 36 + y * 18));
//?}
            }
        }

        addFullPlayerInventory(123);
    }

    public void sendSelectPage(int page) {
        if (page >= 0) sendSelectOption(-page - 2);
    }

    public void sendSelectOption(int option) {
        sendMessage(NET_SELECT_OPTION, buffer -> buffer.writeVarInt(option));
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void readMessage(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readMessage(id, buffer, side, ctx);
        if (side == BCNetworkSide.SERVER && id == NET_SELECT_OPTION && tile != null) {
            tile.selectOption(buffer.readVarInt());
        }
    }
}
