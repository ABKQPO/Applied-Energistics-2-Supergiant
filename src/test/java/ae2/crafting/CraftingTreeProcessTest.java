package ae2.crafting;

import ae2.api.networking.crafting.ICraftingService;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ae2.crafting.CraftingTestNetwork.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftingTreeProcessTest {
    @Test
    void lazyNodeViewPreservesSlotOrderAndRejectsEntryMutation() {
        var net = new CraftingTestNetwork();
        var pattern = net.pattern("view", new Object[]{"second", 2, "first", 3}, new Object[]{"out", 1});
        var calculation = net.calculation(key("out"), 1);
        var service = calculation.simRequester.getGridNode().grid().getService(ICraftingService.class);
        var process = new CraftingTreeProcess(service, calculation, pattern, null);
        var nodes = process.getNodes();
        assertSame(nodes, process.getNodes());
        var actual = new ArrayList<GenericStack>();
        for (var entry : nodes.object2LongEntrySet()) {
            actual.add(new GenericStack(entry.getKey().getWhat(), entry.getKey().getAmount() * entry.getLongValue()));
            assertThrows(UnsupportedOperationException.class, () -> entry.setValue(99L));
            assertThrows(UnsupportedOperationException.class, () -> nodes.put(entry.getKey(), 99L));
        }
        assertEquals(List.of(new GenericStack(key("second"), 2), new GenericStack(key("first"), 3)), actual);
        Map<CraftingTreeNode, Long> boxedNodes = nodes;
        var boxed = boxedNodes.entrySet().iterator().next();
        assertThrows(UnsupportedOperationException.class, () -> boxed.setValue(Long.valueOf(99)));
        var arrayEntry = (Object2LongMap.Entry<?>) nodes.object2LongEntrySet().toArray()[0];
        assertThrows(UnsupportedOperationException.class, () -> arrayEntry.setValue(99L));
        var iterator = nodes.object2LongEntrySet().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, nodes::clear);
    }
}
