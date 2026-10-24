package ae2.integration.modules.hei;

import ae2.container.me.items.ContainerCraftingTerm;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

record CraftingRecipeTransferAnalysis(
    Outcome outcome,
    IntSet missingGuiSlots,
    IntSet craftableGuiSlots,
    IntSet networkGuiSlots,
    int ingredientCount,
    boolean hasAvailableIngredients,
    boolean hasPendingNetworkQuery) {

    CraftingRecipeTransferAnalysis {
        missingGuiSlots = IntSets.unmodifiable(missingGuiSlots);
        craftableGuiSlots = IntSets.unmodifiable(craftableGuiSlots);
        networkGuiSlots = IntSets.unmodifiable(networkGuiSlots);
    }

    static CraftingRecipeTransferAnalysis analyze(ContainerCraftingTerm.MissingIngredientSlots missingSlots,
                                                  int ingredientCount) {
        return analyze(missingSlots.missingSlots(), missingSlots.craftableSlots(), IntSets.EMPTY_SET, ingredientCount,
            true, missingSlots.missingSlots().size() + missingSlots.craftableSlots().size() < ingredientCount, false);
    }

    static CraftingRecipeTransferAnalysis analyzeGuiSlots(IntSet missingSlots, IntSet craftableSlots,
                                                          int ingredientCount, boolean hasAvailableIngredients,
                                                          boolean hasPendingNetworkQuery) {
        return analyzeGuiSlots(missingSlots, craftableSlots, IntSets.EMPTY_SET, ingredientCount,
            hasAvailableIngredients, hasPendingNetworkQuery);
    }

    static CraftingRecipeTransferAnalysis analyzeGuiSlots(IntSet missingSlots, IntSet craftableSlots,
                                                          IntSet networkSlots, int ingredientCount,
                                                          boolean hasAvailableIngredients,
                                                          boolean hasPendingNetworkQuery) {
        return analyze(missingSlots, craftableSlots, networkSlots, ingredientCount, false,
            hasAvailableIngredients, hasPendingNetworkQuery);
    }

    private static CraftingRecipeTransferAnalysis analyze(IntSet missingSlots, IntSet craftableSlots,
                                                          IntSet networkSlots, int ingredientCount,
                                                          boolean craftingGridSlots,
                                                          boolean hasAvailableIngredients,
                                                          boolean hasPendingNetworkQuery) {
        int unavailableCount = missingSlots.size() + craftableSlots.size();

        Outcome outcome;
        if (unavailableCount == 0) {
            outcome = Outcome.READY;
        } else if (unavailableCount >= ingredientCount) {
            outcome = Outcome.BLOCK_ALL_MISSING;
        } else if (!missingSlots.isEmpty() && !craftableSlots.isEmpty()) {
            outcome = Outcome.PARTIAL_MIXED;
        } else if (!craftableSlots.isEmpty()) {
            outcome = Outcome.PARTIAL_CRAFTABLE;
        } else {
            outcome = Outcome.PARTIAL_UNCRAFTABLE;
        }

        return new CraftingRecipeTransferAnalysis(outcome,
            craftingGridSlots ? toGuiSlots(missingSlots) : missingSlots,
            craftingGridSlots ? toGuiSlots(craftableSlots) : craftableSlots,
            craftingGridSlots ? toGuiSlots(networkSlots) : networkSlots, ingredientCount,
            hasAvailableIngredients, hasPendingNetworkQuery);
    }

    private static IntSet toGuiSlots(IntSet slots) {
        IntRBTreeSet result = new IntRBTreeSet();
        IntIterator iterator = slots.iterator();
        while (iterator.hasNext()) {
            result.add(iterator.nextInt() + 1);
        }
        return result;
    }

    boolean hasImmediatelyAvailableIngredients() {
        return hasAvailableIngredients;
    }

    boolean hasCraftableMissingIngredients() {
        return !craftableGuiSlots.isEmpty();
    }

    boolean hasNetworkAvailableIngredients() {
        return !networkGuiSlots.isEmpty();
    }

    boolean hasPotentiallyCraftableMissingIngredients() {
        return hasCraftableMissingIngredients() || hasPendingNetworkQuery;
    }

    boolean canTransfer() {
        return hasAvailableIngredients || hasNetworkAvailableIngredients() || hasPotentiallyCraftableMissingIngredients();
    }

    boolean hasUncraftableMissingIngredients() {
        return !missingGuiSlots.isEmpty();
    }

    IntCollection getMissingGuiSlots() {
        IntList result = new IntArrayList(missingGuiSlots.size());
        result.addAll(missingGuiSlots);
        return result;
    }

    IntCollection getCraftableGuiSlots() {
        IntList result = new IntArrayList(craftableGuiSlots.size());
        result.addAll(craftableGuiSlots);
        return result;
    }

    IntCollection getNetworkGuiSlots() {
        IntList result = new IntArrayList(networkGuiSlots.size());
        result.addAll(networkGuiSlots);
        return result;
    }

    enum Outcome {
        READY,
        BLOCK_ALL_MISSING,
        PARTIAL_UNCRAFTABLE,
        PARTIAL_CRAFTABLE,
        PARTIAL_MIXED
    }
}
