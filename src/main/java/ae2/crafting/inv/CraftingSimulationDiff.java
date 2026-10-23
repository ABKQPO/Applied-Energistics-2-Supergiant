package ae2.crafting.inv;

import ae2.api.crafting.IPatternDetails;
import ae2.api.config.Actionable;
import ae2.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * Frozen, single-use changes produced by an isolated crafting simulation.
 */
public final class CraftingSimulationDiff {
    final KeyCounter requiredExtract;
    final KeyCounter inventoryDelta;
    final KeyCounter emittedItems;
    final KeyCounter pseudoItems;
    final Object2LongMap<IPatternDetails> crafts;
    final double bytes;
    private boolean applied;

    CraftingSimulationDiff(KeyCounter requiredExtract, KeyCounter inventoryDelta,
                           KeyCounter emittedItems, KeyCounter pseudoItems,
                           Object2LongMap<IPatternDetails> crafts, double bytes) {
        this(copy(requiredExtract), copy(inventoryDelta), copy(emittedItems), copy(pseudoItems),
            new Object2LongOpenHashMap<>(crafts), bytes, true);
    }

    private CraftingSimulationDiff(KeyCounter requiredExtract, KeyCounter inventoryDelta,
                                   KeyCounter emittedItems, KeyCounter pseudoItems,
                                   Object2LongMap<IPatternDetails> crafts, double bytes, boolean ignored) {
        this.requiredExtract = requiredExtract;
        this.inventoryDelta = inventoryDelta;
        this.emittedItems = emittedItems;
        this.pseudoItems = pseudoItems;
        this.crafts = crafts;
        this.bytes = bytes;
    }

    /**
     * A diff over the given counters as they are, for one that is applied right away: the state it comes from is
     * discarded or no longer read, so copying counters as large as its whole subtree is not needed.
     */
    static CraftingSimulationDiff shared(KeyCounter requiredExtract, KeyCounter inventoryDelta,
                                         KeyCounter emittedItems, KeyCounter pseudoItems,
                                         Object2LongMap<IPatternDetails> crafts, double bytes) {
        return new CraftingSimulationDiff(requiredExtract, inventoryDelta, emittedItems, pseudoItems, crafts, bytes,
            true);
    }

    public void applyTo(CraftingSimulationState parent) {
        if (this.applied) {
            throw new IllegalStateException("Crafting simulation diff was already applied");
        }
        if (!canApplyTo(parent)) {
            throw new IllegalStateException("Crafting simulation diff cannot be applied to parent");
        }
        // No copy of the parent to roll back: once the check above passed, applying can only fail on an internal
        // error, which aborts the whole calculation, and the copy made every applied diff cost as much as the entire
        // simulation state.
        parent.applyDiff(this);
        this.applied = true;
    }

    public boolean canApplyTo(CraftingSimulationState parent) {
        var test = new ChildCraftingSimulationState(parent);
        for (var entry : inventoryDelta) {
            if (entry.getLongValue() < 0
                && test.extract(entry.getKey(), -entry.getLongValue(), Actionable.MODULATE)
                != -entry.getLongValue()) {
                return false;
            }
        }
        return true;
    }

    public double getBytes() {
        return this.bytes;
    }

    private static KeyCounter copy(KeyCounter source) {
        var result = new KeyCounter();
        result.addAll(source);
        return result;
    }
}
