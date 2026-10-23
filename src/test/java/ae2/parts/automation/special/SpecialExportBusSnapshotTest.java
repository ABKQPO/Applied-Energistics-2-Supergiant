package ae2.parts.automation.special;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecialExportBusSnapshotTest {
    @Test
    void snapshotKeepsAllVariantsInOrderWhenTransfersChangeTheLiveInventory() {
        Bootstrap.register();
        var inventory = new KeyCounter();
        var expectedKeys = new HashSet<AEKey>();
        for (int i = 0; i < 32; i++) {
            var stack = new ItemStack(Items.STICK);
            var tag = new NBTTagCompound();
            tag.setInteger("variant", i);
            stack.setTagCompound(tag);
            var key = AEItemKey.of(stack);
            inventory.add(key, i + 1);
            expectedKeys.add(key);
        }
        var apple = AEItemKey.of(Items.APPLE);
        inventory.add(apple, 3);
        expectedKeys.add(apple);
        var expectedOrder = new ArrayList<AEKey>();
        for (var entry : inventory) expectedOrder.add(entry.getKey());

        var snapshot = SpecialExportBusPart.snapshotKeys(inventory);
        inventory.clear();
        inventory.add(AEItemKey.of(Items.POTATO), 5);

        assertEquals(expectedKeys.size(), snapshot.size());
        assertEquals(expectedKeys, new HashSet<>(snapshot));
        assertEquals(expectedOrder, snapshot);
    }
}
