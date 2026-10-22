package ae2.integration.modules.itemlists.transfer;

import ae2.api.stacks.AEItemKey;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRecipeTransferTest {
    private static AEItemKey apple;
    private static AEItemKey carrot;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
        apple = AEItemKey.of(new ItemStack(Items.APPLE));
        carrot = AEItemKey.of(new ItemStack(Items.CARROT));
    }

    private static PendingRecipeTransfer pending(PendingRecipeTransfer.SlotSpec spec) {
        return new PendingRecipeTransfer(UUID.randomUUID(), new Object(), new Object(), 1, List.of(spec));
    }

    private static EntityPlayerMP player() throws Exception {
        var player = (ProbePlayer) unsafe().allocateInstance(ProbePlayer.class);
        player.uuid = UUID.randomUUID();
        return player;
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    @Test
    void playerAdditionsReduceTheQuotaButMachineConsumptionDoesNotGrowIt() {
        var pending = pending(new PendingRecipeTransfer.SlotSpec(4, apple, 5, null, 0));

        assertEquals(4, pending.recordDelivered(4, 1));
        assertEquals(3, pending.observe(4, apple, 2));
        assertEquals(3, pending.observe(4, apple, 1),
            "a machine consuming one item does not create a new order");
        assertFalse(pending.isComplete());
    }

    @Test
    void initialTargetItemsAreAlreadyCountedTowardTheFiniteDeliveryQuota() {
        var pending = pending(new PendingRecipeTransfer.SlotSpec(4, apple, 5, apple, 2));

        assertEquals(3, pending.views().getFirst().remainingAmount());
        assertEquals(3, pending.observe(4, apple, 2));
    }

    @Test
    void differentPlayerInputReleasesOnlyThatSlot() {
        var pending = new PendingRecipeTransfer(UUID.randomUUID(), new Object(), new Object(), 1, List.of(
            new PendingRecipeTransfer.SlotSpec(0, apple, 1, null, 0),
            new PendingRecipeTransfer.SlotSpec(1, carrot, 1, null, 0)));

        pending.observe(0, carrot, 1);

        assertEquals(1, pending.views().size());
        assertEquals(1, pending.views().getFirst().targetSlot());
    }

    @Test
    void managerReplacesAnOldRequestForTheSamePlayer() throws Exception {
        var player = player();
        var manager = new PendingRecipeTransferManager();
        var first = pending(new PendingRecipeTransfer.SlotSpec(0, apple, 1, null, 0));
        var second = pending(new PendingRecipeTransfer.SlotSpec(1, apple, 1, null, 0));

        manager.replace(player, first);
        manager.replace(player, second);

        assertSame(second, manager.get(player));
        assertFalse(manager.isCurrent(player, first));
        assertTrue(manager.isCurrent(player, second));
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
}
