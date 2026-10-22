package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeRecipeTransferSourceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void refundsToTheSourceThatOwnedEachExtractedPortion() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var local = new CountingSource(key, 2);
        var network = new CountingSource(key, 3);
        var source = new CompositeRecipeTransferSource(List.of(local, network));

        ItemStack extracted = source.extract(key, 4);
        ItemStack remainder = source.refund(extracted);

        assertTrue(Objects.requireNonNull(remainder).isEmpty());
        assertEquals(2, local.available(key));
        assertEquals(3, network.available(key));
    }

    @Test
    void committedMaterialDoesNotLeaveRefundProvenanceBehind() {
        var key = AEItemKey.of(new ItemStack(Items.APPLE));
        var backing = new CountingSource(key, 4);
        var source = new CompositeRecipeTransferSource(List.of(backing));

        ItemStack extracted = source.extract(key, 4);
        source.commit(extracted);
        ItemStack unowned = source.refund(new ItemStack(Items.APPLE));

        assertEquals(1, Objects.requireNonNull(unowned).getCount());
        assertEquals(0, backing.available(key));
        assertEquals(4, backing.committed);
    }

    private static final class CountingSource implements RecipeTransferSource {
        private final AEKey2LongMap amounts = new AEKey2LongMap.OpenHashMap();
        private int committed;

        private CountingSource(AEKey key, long amount) {
            amounts.put(key, amount);
        }

        @Override
        public long available(AEKey key) {
            return amounts.getOrDefault(key, 0L);
        }

        @Override
        public ItemStack extract(AEKey key, int amount) {
            int extracted = (int) Math.min(available(key), amount);
            amounts.put(key, available(key) - extracted);
            return extracted <= 0 ? ItemStack.EMPTY : ((AEItemKey) key).toStack(extracted);
        }

        @Override
        public ItemStack refund(ItemStack stack) {
            var key = AEItemKey.of(stack);
            amounts.addTo(key, stack.getCount());
            return ItemStack.EMPTY;
        }

        @Override
        public void commit(ItemStack stack) {
            committed += stack.getCount();
        }
    }
}
