package ae2.crafting;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.crafting.inv.ChildCraftingSimulationState;
import ae2.crafting.inv.CraftingSimulationState;
import ae2.crafting.inv.ICraftingInventory;
import ae2.crafting.inv.NetworkCraftingSimulationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static ae2.crafting.CraftingTestNetwork.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingSimulationTransactionTest {
    private static ChildCraftingSimulationState child(CraftingSimulationState parent, boolean direct) {
        if (direct) return new ChildCraftingSimulationState(parent);
        // Exercise the ICraftingInventory fallback against the same observable parent behavior.
        return new ChildCraftingSimulationState(new ICraftingInventory() {
            @Override
            public void insert(AEKey what, long amount, Actionable mode) {
                parent.insert(what, amount, mode);
            }

            @Override
            public long extract(AEKey what, long amount, Actionable mode) {
                return parent.extract(what, amount, mode);
            }

            @Override
            public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
                return parent.findFuzzyTemplates(input);
            }
        });
    }

    @Test
    void customInventoryOverridesAreNotBypassedByGroupCopying() {
        CraftingTestNetwork.bootstrap();
        var material = key("overridden_inventory");
        var stock = new KeyCounter();
        stock.add(material, 10);
        var customNetwork = new NetworkCraftingSimulationState(stock) {
            @Override
            protected long simulateExtractParent(AEKey what) {
                return Math.min(4, super.simulateExtractParent(what));
            }
        };
        assertEquals(4, new ChildCraftingSimulationState(customNetwork).getAvailableAmount(material));
        var customChild = new ChildCraftingSimulationState(new NetworkCraftingSimulationState(stock)) {
            @Override
            public long extract(AEKey what, long amount, Actionable mode) {
                return super.extract(what, Math.min(3, amount), mode);
            }
        };
        assertEquals(3, new ChildCraftingSimulationState(customChild).getAvailableAmount(material));
        var inheritedHook = new ChildCraftingSimulationState(new NetworkCraftingSimulationState(stock)) {
            @Override
            protected long simulateExtractParent(AEKey what) {
                return Math.min(2, super.simulateExtractParent(what));
            }
        };
        assertEquals(2, inheritedHook.getAvailableAmount(material));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritedFuzzyGroupsKeepSnapshotIsolationIncludingAbsentVariants(boolean direct) {
        CraftingTestNetwork.bootstrap();
        var stock = new KeyCounter();
        var first = key("inherited@first");
        var second = key("inherited@second");
        var added = key("inherited@added");
        stock.add(first, 5);
        stock.add(second, 7);
        var parent = new NetworkCraftingSimulationState(stock);
        var child = child(parent, direct);
        child.recordInventoryReads();
        assertEquals(2, child.extract(first, 2, Actionable.MODULATE));
        var snapshot = child.snapshotInventoryReads();
        parent.extract(second, 6, Actionable.MODULATE);
        parent.insert(added, 9, Actionable.MODULATE);
        assertEquals(7, child.getAvailableAmount(second));
        assertEquals(0, child.getAvailableAmount(added));
        assertEquals(3, child.getAvailableAmount(first));
        assertFalse(snapshot.matches(parent));
        assertTrue(snapshot.matches(new NetworkCraftingSimulationState(stock)));

        var absent = child(parent, direct);
        var newKey = key("previously_absent");
        absent.recordInventoryReads();
        assertEquals(0, absent.getAvailableAmount(newKey));
        var absentSnapshot = absent.snapshotInventoryReads();
        parent.insert(newKey, 1, Actionable.MODULATE);
        assertEquals(0, absent.getAvailableAmount(newKey));
        assertFalse(absentSnapshot.matches(parent));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritedGroupIncludesBoundaryStockAndClampsAvailableOverflow(boolean direct) {
        CraftingTestNetwork.bootstrap();
        var stock = new KeyCounter();
        var material = key("inherited_boundary");
        stock.add(material, Long.MAX_VALUE - 2);
        var parent = new NetworkCraftingSimulationState(stock);
        parent.insertBoundary(material, 10);
        var child = child(parent, direct);
        assertEquals(Long.MAX_VALUE, child.getAvailableAmount(material));
        assertEquals(Long.MAX_VALUE, child.extract(material, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(0, child.getAvailableAmount(material));
        assertEquals(Long.MAX_VALUE, child.getRequiredExtractAmount(material));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitPreservesExactVariantsAndPeakCatalystRequirement(boolean frozen) {
        CraftingTestNetwork.bootstrap();
        var stock = new KeyCounter();
        stock.add(key("transaction_material@tool"), 1);
        stock.add(key("transaction_material@ore"), 3);
        var parent = new NetworkCraftingSimulationState(stock);
        var child = new ChildCraftingSimulationState(parent);
        assertEquals(1, child.extract(key("transaction_material@tool"), 1, Actionable.MODULATE));
        child.insert(key("transaction_material@tool"), 1, Actionable.MODULATE);
        assertEquals(2, child.extract(key("transaction_material@ore"), 2, Actionable.MODULATE));
        child.insert(key("transaction_plate"), 2, Actionable.MODULATE);
        if (frozen) {
            var diff = child.freezeDiff();
            // Freezing must detach from later child changes, and applying a copy must not consume the snapshot.
            child.insert(key("transaction_plate"), 100, Actionable.MODULATE);
            diff.copy().applyTo(parent);
            var second = new NetworkCraftingSimulationState(stock);
            diff.copy().applyTo(second);
            assertEquals(2, second.getAvailableAmount(key("transaction_plate")));
            assertEquals(1, second.getRequiredExtractAmount(key("transaction_material@tool")));
        } else {
            child.applyDiff(parent);
        }
        assertEquals(1, parent.getAvailableAmount(key("transaction_material@tool")));
        assertEquals(1, parent.getRequiredExtractAmount(key("transaction_material@tool")));
        assertEquals(1, parent.getAvailableAmount(key("transaction_material@ore")));
        assertEquals(2, parent.getAvailableAmount(key("transaction_plate")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedCommitDoesNotPartiallyConsumeOrPublishOutputs(boolean frozen) {
        CraftingTestNetwork.bootstrap();
        var stock = new KeyCounter();
        stock.add(key("transaction_a"), 2);
        stock.add(key("transaction_b"), 2);
        var parent = new NetworkCraftingSimulationState(stock);
        var child = new ChildCraftingSimulationState(parent);
        child.extract(key("transaction_a"), 2, Actionable.MODULATE);
        child.extract(key("transaction_b"), 2, Actionable.MODULATE);
        child.insert(key("transaction_output"), 1, Actionable.MODULATE);
        parent.extract(key("transaction_b"), 1, Actionable.MODULATE);
        if (frozen) assertThrows(IllegalStateException.class, () -> child.freezeDiff().applyTo(parent));
        else assertThrows(IllegalStateException.class, () -> child.applyDiff(parent));
        assertEquals(2, parent.getAvailableAmount(key("transaction_a")));
        assertEquals(1, parent.getAvailableAmount(key("transaction_b")));
        assertEquals(0, parent.getAvailableAmount(key("transaction_output")));
        assertEquals(0, parent.getRequiredExtractAmount(key("transaction_a")));
    }
}
