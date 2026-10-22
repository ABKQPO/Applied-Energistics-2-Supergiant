package ae2.integration.modules.itemlists.transfer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalSlotInputTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    private static ProbePlayer player() throws Exception {
        var player = (ProbePlayer) unsafe().allocateInstance(ProbePlayer.class);
        player.inventory = new InventoryPlayer(player);
        player.uuid = UUID.randomUUID();
        return player;
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    @Test
    void copyingConfigurationSlotDoesNotConsumeTheOfferedItems() throws Exception {
        var player = player();
        var container = new CopyingContainer();
        player.openContainer = container;

        var receipt = NormalSlotInput.insert(container, player, 0, new ItemStack(Items.APPLE, 3));

        assertEquals(0, receipt.accepted());
        assertEquals(InsertionReceipt.Status.REFUSED, receipt.status());
        assertEquals(3, receipt.remainder().getCount());
        assertEquals(3, container.inventory.getStackInSlot(0).getCount(),
            "the target may keep its configuration copy without owning the real input");
        assertTrue(player.inventory.getItemStack().isEmpty(), "the executor restores the player's cursor");
    }

    @Test
    void normalSlotUsesTheActualCarriedStackRemainder() throws Exception {
        var player = player();
        var container = new NormalContainer();
        player.openContainer = container;

        var receipt = NormalSlotInput.insert(container, player, 0, new ItemStack(Items.APPLE, 3));

        assertEquals(3, receipt.accepted());
        assertTrue(receipt.remainder().isEmpty());
        assertEquals(3, container.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void existingCarriedItemsRejectTheOperationBeforeTouchingTheTarget() throws Exception {
        var player = player();
        var container = new NormalContainer();
        player.openContainer = container;
        player.inventory.setItemStack(new ItemStack(Items.CARROT));

        var receipt = NormalSlotInput.insert(container, player, 0, new ItemStack(Items.APPLE, 3));

        assertEquals(0, receipt.accepted());
        assertEquals(1, player.inventory.getItemStack().getCount());
        assertTrue(container.inventory.getStackInSlot(0).isEmpty());
    }

    private static final class ProbePlayer extends EntityPlayerMP {
        private UUID uuid;

        @SuppressWarnings("DataFlowIssue")
        private ProbePlayer() {
            super(null, null, null, null);
        }

        @Override
        public @NonNull UUID getUniqueID() {
            return uuid;
        }
    }

    private static class NormalContainer extends Container {
        final IInventory inventory = new InventoryBasic("test", false, 1);

        protected NormalContainer() {
            addSlotToContainer(new Slot(inventory, 0, 0, 0));
        }

        @Override
        public boolean canInteractWith(@NonNull EntityPlayer player) {
            return true;
        }
    }

    private static final class CopyingContainer extends NormalContainer {
        @Override
        public @NonNull ItemStack slotClick(int slotId, int dragType, @NonNull ClickType clickType,
                                            @NonNull EntityPlayer player) {
            if (slotId == 0 && clickType == ClickType.PICKUP
                && !player.inventory.getItemStack().isEmpty()) {
                inventory.setInventorySlotContents(0, player.inventory.getItemStack().copy());
                return player.inventory.getItemStack().copy();
            }
            return super.slotClick(slotId, dragType, clickType, player);
        }
    }
}
