package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.IRecipeTransferCustomization;
import ae2.api.integrations.recipetransfer.RecipeTransferContext;
import ae2.api.integrations.recipetransfer.RecipeTransferQuantityPolicy;
import ae2.api.integrations.recipetransfer.RecipeTransferReceipt;
import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTransferExecutorTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void refusedNormalInputRefundsTheNetworkAndDoesNotBecomeMissingCraftingDemand() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var source = new Source(key, 4);
        var context = new RecipeTransferContext(source);
        var request = new RecipeTransferExecutor.Request(
            List.of(new RecipeTransferExecutor.Input(key, 2, 64,
                InsertionReceipt::refused)),
            false, true, false, null);

        var result = RecipeTransferExecutor.execute(context, request, null);

        assertEquals(0, result.movedAmount());
        assertTrue(result.inputRejected());
        assertEquals(4, source.available(key), "the refused receipt is returned to its source");
        assertEquals(0, result.missingForOneSet().getOrDefault(key, 0L),
            "a target refusal is not reinterpreted as a reason to order more materials");
    }

    @Test
    void ctrlReportsOnlyTheActualOneSetShortage() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var source = new Source(key, 2);
        var request = new RecipeTransferExecutor.Request(
            List.of(new RecipeTransferExecutor.Input(key, 3, 64, InsertionReceipt::accepted)),
            false, true, false, null);

        var result = RecipeTransferExecutor.execute(new RecipeTransferContext(source), request, null);

        assertEquals(2, result.movedAmount());
        assertEquals(1, result.missingForOneSet().getLong(key));
        assertEquals(0, source.available(key));
    }

    @Test
    void rejectedTargetSlotIsExcludedFromPendingRefill() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var result = RecipeTransferExecutor.execute(new RecipeTransferContext(new Source(key, 4)),
            new RecipeTransferExecutor.Request(
                List.of(new RecipeTransferExecutor.Input(23, key, 2, 64, InsertionReceipt::refused)),
                false, true, false, null), null);

        assertTrue(result.inputRejected());
        assertTrue(result.rejectedTargetSlots().contains(23));
        assertEquals(IntSet.of(23), result.rejectedTargetSlots());
    }

    @Test
    void customizationCanKeepAesSingleSetPolicyAndReceiveTheRecipeId() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var source = new Source(key, 8);
        var context = new RecipeTransferContext(source);
        var customization = new IRecipeTransferCustomization() {
            @Override
            public void customizeRecipeTransfer(RecipeTransferContext context) {
                context.setQuantityPolicy(RecipeTransferQuantityPolicy.SINGLE_SET);
            }

            @Override
            public void onRecipeTransferApplied(RecipeTransferReceipt receipt) {
                assertEquals(new ResourceLocation("ae2", "test"), receipt.recipeId());
            }
        };
        var request = new RecipeTransferExecutor.Request(
            List.of(new RecipeTransferExecutor.Input(key, 2, 64,
                InsertionReceipt::accepted)),
            true, false, true, new ResourceLocation("ae2", "test"));

        var result = RecipeTransferExecutor.execute(context, request, customization);

        assertEquals(2, result.movedAmount());
        assertTrue(result.complete());
        assertEquals(6, source.available(key));
        assertFalse(Objects.requireNonNull(result.receipt().recipeId()).toString().isEmpty());
    }

    @Test
    void repeatedMaterialKeepsItsPerSlotMaximumQuantities() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var source = new Source(key, 10);
        var received = new IntArrayList();
        var context = new RecipeTransferContext(source);
        var request = new RecipeTransferExecutor.Request(
            List.of(
                new RecipeTransferExecutor.Input(key, 2, 64, stack -> {
                    received.add(stack.getCount());
                    return InsertionReceipt.accepted(stack);
                }),
                new RecipeTransferExecutor.Input(key, 3, 64, stack -> {
                    received.add(stack.getCount());
                    return InsertionReceipt.accepted(stack);
                })),
            true, false, true, null);

        RecipeTransferExecutor.execute(context, request, null);

        assertEquals(List.of(4, 6), received);
        assertEquals(0, source.available(key));
    }

    @Test
    void existingTargetAmountIsNotExtractedAgain() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var source = new Source(key, 4);
        var received = new IntArrayList();
        var request = new RecipeTransferExecutor.Request(
            List.of(new RecipeTransferExecutor.Input(0, key, 5, 2, 64, stack -> {
                received.add(stack.getCount());
                return InsertionReceipt.accepted(stack);
            })),
            false, false, false, null);

        var result = RecipeTransferExecutor.execute(new RecipeTransferContext(source), request, null);

        assertEquals(List.of(3), received);
        assertEquals(3, result.movedAmount());
        assertEquals(1, source.available(key));
    }

    private static final class Source implements RecipeTransferSource {
        private final AEKey2LongMap amounts = new AEKey2LongMap.OpenHashMap();

        private Source(AEKey key, long amount) {
            amounts.put(key, amount);
        }

        @Override
        public long available(AEKey key) {
            return amounts.getOrDefault(key, 0L);
        }

        @Override
        public ItemStack extract(AEKey key, int amount) {
            long available = available(key);
            int extracted = (int) Math.min(available, amount);
            if (extracted <= 0) {
                return ItemStack.EMPTY;
            }
            amounts.put(key, available - extracted);
            return ((AEItemKey) key).toStack(extracted);
        }

        @Override
        public ItemStack refund(ItemStack stack) {
            var key = AEItemKey.of(stack);
            amounts.put(key, amounts.getLong(key) + stack.getCount());
            return ItemStack.EMPTY;
        }
    }
}
