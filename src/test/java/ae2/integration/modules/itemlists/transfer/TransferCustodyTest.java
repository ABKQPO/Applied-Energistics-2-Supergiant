package ae2.integration.modules.itemlists.transfer;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferCustodyTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void refusedInputReturnsTheOfferedItemsToTheirOriginalSource() {
        var returned = new ObjectArrayList<ItemStack>();
        var custody = new TransferCustody();
        var held = custody.hold(new ItemStack(Items.APPLE, 5), stack -> {
            returned.add(stack.copy());
            return ItemStack.EMPTY;
        });

        var offered = custody.offer(held, 3);
        custody.settle(held, InsertionReceipt.refused(offered));

        assertTrue(custody.refundAll().isEmpty());
        assertEquals(5, returned.getFirst().getCount());
    }

    @Test
    void acceptedInputOnlyReturnsTheUnacceptedRemainder() {
        var returned = new ObjectArrayList<ItemStack>();
        var custody = new TransferCustody();
        var held = custody.hold(new ItemStack(Items.APPLE, 5), stack -> {
            returned.add(stack.copy());
            return ItemStack.EMPTY;
        });

        var offered = custody.offer(held, 3);
        custody.settle(held, InsertionReceipt.accepted(offered));
        custody.refundAll();

        assertEquals(2, returned.getFirst().getCount());
    }

    @Test
    void refundFailureIsReturnedToTheCallerForPlayerInventoryOrDrop() {
        var custody = new TransferCustody();
        custody.hold(new ItemStack(Items.APPLE, 2), stack -> stack);
        List<ItemStack> unreturned = custody.refundAll();

        assertEquals(1, unreturned.size());
        assertEquals(2, unreturned.getFirst().getCount());
    }

    @Test
    void invalidReceiptDoesNotLoseTheInFlightOffer() {
        var returned = new ObjectArrayList<ItemStack>();
        var custody = new TransferCustody();
        var held = custody.hold(new ItemStack(Items.APPLE, 5), stack -> {
            returned.add(stack.copy());
            return ItemStack.EMPTY;
        });
        var offered = custody.offer(held, 3);
        assertEquals(3, offered.getCount());

        assertThrows(IllegalArgumentException.class,
            () -> custody.settle(held, InsertionReceipt.accepted(new ItemStack(Items.CARROT, 3))));
        custody.refundAll();

        assertEquals(5, returned.stream().mapToInt(ItemStack::getCount).sum());
    }
}
