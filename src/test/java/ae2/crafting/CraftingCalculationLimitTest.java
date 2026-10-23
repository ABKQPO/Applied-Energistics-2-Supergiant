package ae2.crafting;

import ae2.core.AEConfig;
import ae2.core.localization.PlayerMessages;
import org.junit.jupiter.api.Test;

import static ae2.crafting.CraftingTestNetwork.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calculation time a dedicated server allows one crafting calculation.
 */
class CraftingCalculationLimitTest {

    // Inputs with several possible stacks are still crafted one at a time, so a dedicated server stops the request
    @Test
    void calculationLimitStopsLongCalculations() {
        int previous = AEConfig.CRAFTING.craftingCalculationLimitSeconds;
        AEConfig.CRAFTING.craftingCalculationLimitSeconds = 1;
        try {
            var net = new CraftingTestNetwork();
            net.container("cell", "empty_cell");
            net.pattern("dust", of("ore", 1, "cell|other_cell", 1), of("dust", 1));
            net.pattern("cell", of("empty_cell", 1, "water", 1), of("cell", 1));
            net.store("ore", Long.MAX_VALUE / 4);
            net.store("water", Long.MAX_VALUE / 4);
            net.store("empty_cell", 1);

            var start = System.nanoTime();
            var failure = assertThrows(CraftingCalculationFailure.class, () -> net.plan("dust", 100_000_000));

            assertEquals(PlayerMessages.CraftingTooLong, failure.getMessageKey());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
        } finally {
            AEConfig.CRAFTING.craftingCalculationLimitSeconds = previous;
        }
    }

}
