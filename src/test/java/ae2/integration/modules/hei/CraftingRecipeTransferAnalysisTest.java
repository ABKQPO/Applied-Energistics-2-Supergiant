package ae2.integration.modules.hei;

import ae2.container.me.items.ContainerCraftingTerm;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingRecipeTransferAnalysisTest {
    @Test
    void onlyEnablesMissingTransferWhenMaterialsCanBeMovedOrRequested() {
        var missing = new IntOpenHashSet(new int[] { 0 });
        var none = new IntOpenHashSet();

        assertFalse(CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, none, 1, false, false).canTransfer());
        assertTrue(CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, none, 1, true, false).canTransfer());
        assertTrue(CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, none, 1, false, true).canTransfer());
        assertTrue(CraftingRecipeTransferAnalysis.analyzeGuiSlots(none, missing, 1, false, false).canTransfer());
    }

    @Test
    void networkAvailableSlotsAreExposedSeparatelyFromCraftableSlots() {
        var missing = new IntOpenHashSet(new int[] { 1 });
        var craftable = new IntOpenHashSet();
        var network = new IntOpenHashSet(new int[] { 0 });

        var analysis = CraftingRecipeTransferAnalysis.analyzeGuiSlots(missing, craftable, network, 2,
            false, false);

        assertTrue(analysis.hasNetworkAvailableIngredients());
        assertTrue(analysis.getNetworkGuiSlots().contains(0));
        assertFalse(analysis.getCraftableGuiSlots().contains(0));
        assertTrue(analysis.canTransfer());
    }

    @Test
    void directMeGuiAnalysisDoesNotExposeWirelessNetworkHighlights() {
        var missing = new IntOpenHashSet(new int[] { 0 });
        var craftable = new IntOpenHashSet();

        var analysis = CraftingRecipeTransferAnalysis.analyze(
            new ContainerCraftingTerm.MissingIngredientSlots(missing, craftable), 1);

        assertFalse(analysis.hasNetworkAvailableIngredients());
        assertTrue(analysis.getNetworkGuiSlots().isEmpty());
    }
}
