package ae2.integration.modules.itemlists.transfer;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferQuantityPlannerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    private static AEItemKey key(Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    private static AEKey2LongMap available(AEKey key, long amount) {
        var result = new AEKey2LongMap.OpenHashMap();
        result.put(key, amount);
        return result;
    }

    @Test
    void normalTransferUsesTheRecipeAmountPerSet() {
        var key = key(Items.APPLE);
        var plan = TransferQuantityPlanner.plan(
            List.of(new TransferQuantityPlanner.Demand(key, 3, 64)),
            available(key, 10),
            new TransferQuantityPlanner.Request(false, false, false));

        assertEquals(3, plan.transferAmounts().getLong(key));
        assertTrue(plan.missingForOneSet().isEmpty());
        assertTrue(plan.complete());
    }

    @Test
    void ctrlTransfersOnlyAvailableItemsAndLeavesOneSetMissing() {
        var key = key(Items.IRON_INGOT);
        var plan = TransferQuantityPlanner.plan(
            List.of(new TransferQuantityPlanner.Demand(key, 3, 64)),
            available(key, 2),
            new TransferQuantityPlanner.Request(false, true, false));

        assertEquals(2, plan.transferAmounts().getLong(key));
        assertEquals(1, plan.missingForOneSet().getLong(key));
        assertFalse(plan.complete());
    }

    @Test
    void maximumCompleteTransferAccountsForRepeatedInputsAndCapacity() {
        var key = key(Items.REDSTONE);
        var plan = TransferQuantityPlanner.plan(
            List.of(
                new TransferQuantityPlanner.Demand(key, 2, 64),
                new TransferQuantityPlanner.Demand(key, 3, 64)),
            available(key, 10),
            new TransferQuantityPlanner.Request(true, false, true));

        assertEquals(10, plan.transferAmounts().getLong(key));
        assertEquals(4, plan.transferAmountsPerDemand().getLong(0));
        assertEquals(6, plan.transferAmountsPerDemand().getLong(1));
        assertEquals(2, plan.completeSets());
        assertTrue(plan.complete());
    }

    @Test
    void maximumCompleteTransferCountsItemsAlreadyInTheTargetSlot() {
        var key = key(Items.IRON_INGOT);
        var plan = TransferQuantityPlanner.plan(
            List.of(new TransferQuantityPlanner.Demand(key, 3, 2, 64)),
            available(key, 2),
            new TransferQuantityPlanner.Request(true, false, true));

        assertEquals(1, plan.completeSets());
        assertEquals(1, plan.transferAmountsPerDemand().getLong(0));
        assertTrue(plan.complete());
    }

    @Test
    void ctrlMissingAmountExcludesItemsAlreadyInTheTargetSlot() {
        var key = key(Items.IRON_INGOT);
        var plan = TransferQuantityPlanner.plan(
            List.of(new TransferQuantityPlanner.Demand(key, 3, 2, 64)),
            available(key, 0),
            new TransferQuantityPlanner.Request(false, true, false));

        assertEquals(1, plan.missingForOneSet().getLong(key));
        assertEquals(0, plan.transferAmounts().getLong(key));
    }

    @Test
    void maximumTransferDoesNotTurnARecipeAmountIntoAStackOfSixtyFour() {
        var key = key(Items.PAPER);
        var plan = TransferQuantityPlanner.plan(
            List.of(new TransferQuantityPlanner.Demand(key, 3, 4)),
            available(key, 20),
            new TransferQuantityPlanner.Request(true, false, false));

        assertEquals(4, plan.transferAmounts().getLong(key));
    }

    @Test
    void oneSlotCapacityLimitsCompleteSetsEvenWhenOtherSlotsHaveRoom() {
        var key = key(Items.STICK);
        var plan = TransferQuantityPlanner.plan(
            List.of(
                new TransferQuantityPlanner.Demand(key, 3, 2),
                new TransferQuantityPlanner.Demand(key, 1, 64)),
            available(key, 10),
            new TransferQuantityPlanner.Request(true, false, true));

        assertEquals(0, plan.completeSets());
        assertTrue(plan.transferAmounts().isEmpty());
        assertFalse(plan.complete());
    }

    @Test
    void repeatedLongDemandsSaturateWithoutOverflowExceptions() {
        var key = key(Items.STICK);
        var plan = TransferQuantityPlanner.plan(
            List.of(
                new TransferQuantityPlanner.Demand(key, Long.MAX_VALUE, Long.MAX_VALUE),
                new TransferQuantityPlanner.Demand(key, Long.MAX_VALUE, Long.MAX_VALUE)),
            available(key, Long.MAX_VALUE),
            new TransferQuantityPlanner.Request(false, false, false));

        assertEquals(Long.MAX_VALUE, plan.transferAmounts().getLong(key));
        assertEquals(Long.MAX_VALUE, plan.missingForOneSet().getLong(key));
        assertFalse(plan.complete());
    }
}
