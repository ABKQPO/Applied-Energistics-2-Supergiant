package ae2.integration.modules.hei;

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
}
