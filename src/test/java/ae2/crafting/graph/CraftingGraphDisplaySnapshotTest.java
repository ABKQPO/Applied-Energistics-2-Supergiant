package ae2.crafting.graph;

import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.stacks.AEItemKey;
import ae2.crafting.execution.CraftingSupplierLocation;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftingGraphDisplaySnapshotTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void locationsKeepMachineOrderExcludeEmptyAndForeignGroupsAndRemainReadOnly(boolean randomAccess) {
        var first = new PatternContainerGroup(null, new TextComponentString("first"), List.of());
        var second = new PatternContainerGroup(null, new TextComponentString("second"), List.of());
        var empty = new PatternContainerGroup(null, new TextComponentString("empty"), List.of());
        var foreign = new PatternContainerGroup(null, new TextComponentString("foreign"), List.of());
        var position = List.of(new CraftingSupplierLocation(0, 1, 2, 3));
        List<PatternContainerGroup> machines = randomAccess
            ? new ArrayList<>(List.of(first, second, empty)) : new LinkedList<>(List.of(first, second, empty));
        var source = new LinkedHashMap<PatternContainerGroup, List<CraftingSupplierLocation>>();
        source.put(foreign, position);
        source.put(second, position);
        source.put(empty, List.of());
        source.put(first, position);
        var node = new CraftingGraphDisplaySnapshot.Node(AEItemKey.of(Items.APPLE), null, 1,
            1, 1, true, List.of(), machines, source, null, 0, 0, -1, -1);
        var process = new LocalDisplayFragment.Process(1, null, machines, source, List.of());
        for (var filtered : List.of(node.machineLocations(), process.locations())) {
            assertEquals(List.of(first, second), new ArrayList<>(filtered.keySet()));
            assertEquals(Map.of(first, position, second, position), filtered);
            assertThrows(UnsupportedOperationException.class, () -> filtered.put(foreign, position));
            assertThrows(UnsupportedOperationException.class,
                () -> filtered.entrySet().iterator().next().setValue(List.of()));
            var arrayEntry = (Map.Entry<?, ?>) filtered.entrySet().toArray()[0];
            assertThrows(UnsupportedOperationException.class, () -> arrayEntry.setValue(null));
            // Already filtered data must keep the same semantics through another record boundary.
            var complete = new LocalDisplayFragment.Process(1, null, List.of(first, second), filtered, List.of());
            assertEquals(filtered, complete.locations());
            assertEquals(List.of(first, second), new ArrayList<>(complete.locations().keySet()));
            assertThrows(UnsupportedOperationException.class, () -> complete.locations().clear());
        }
    }

    @Test
    void deferredBuildPreservesAmountsAndSeedsAfterGraphReuse() {
        var root = new CraftingGraphNode(AEItemKey.of(Items.APPLE), null, List.of(), 1);
        var child = new CraftingGraphNode(AEItemKey.of(Items.STICK), null, List.of(), 1);
        var graph = new CraftingGraph();
        graph.putNode(new CraftingGraph.NodeKey(root.getWhat(), null), root);
        graph.putNode(new CraftingGraph.NodeKey(child.getWhat(), null), child);
        graph.setRootNode(root);
        var edge = new CraftingGraphEdge(child.getWhat(), 2, child, false);
        root.addInput(edge);
        child.addParent(root);
        root.setDemandAmount(3);
        root.setCraftTimes(3);
        child.setDemandAmount(6);
        child.setCraftTimes(6);
        edge.markCycleCut(4);
        edge.recordSeedResult(3, 1);
        edge.setReserveDemand(2);
        var builder = CraftingGraphDisplaySnapshot.builder(graph);
        builder.recordNode(root);
        builder.recordNode(child);
        builder.recordEdge(edge, 6, 5);
        builder.setRequestedAmount(3);

        graph.resetForReuse();
        root.setDemandAmount(17);
        root.setCraftTimes(17);
        child.setDemandAmount(34);
        child.setCraftTimes(34);
        edge.markCycleCut(10);
        edge.recordSeedResult(8, 2);
        edge.setReserveDemand(9);

        var snapshot = builder.build();
        assertEquals(3, snapshot.requestedAmount());
        assertEquals(3, snapshot.nodes().get(0).demandAmount());
        assertEquals(3, snapshot.nodes().get(0).craftTimes());
        assertEquals(6, snapshot.nodes().get(1).demandAmount());
        assertEquals(6, snapshot.nodes().get(1).craftTimes());
        assertEquals(new CraftingGraphDisplaySnapshot.Edge(child.getWhat(), 2, 1, true,
            6, 0, 5, 1, 4, 3, 1, 2), snapshot.nodes().getFirst().inputs().getFirst());
        graph.resetForReuse();
        assertEquals(snapshot, builder.build());
    }

    @Test
    void primaryAndSecondaryOutputsKeepIndependentOwnershipAcrossConsumersAndReuse() {
        var primary = AEItemKey.of(Items.STICK);
        var secondary = AEItemKey.of(Items.POTATO);
        var root = new CraftingGraphNode(AEItemKey.of(Items.APPLE), null, List.of(), 1);
        var producer = new CraftingGraphNode(primary, null, List.of(), 1);
        var graph = new CraftingGraph();
        graph.putNode(new CraftingGraph.NodeKey(root.getWhat(), null), root);
        graph.putNode(new CraftingGraph.NodeKey(primary, null), producer);
        graph.setRootNode(root);
        var first = new CraftingGraphEdge(primary, 3, producer, false);
        var side = new CraftingGraphEdge(secondary, 2, producer, false);
        var later = new CraftingGraphEdge(primary, 10, producer, false);
        var sideLater = new CraftingGraphEdge(secondary, 5, producer, false);
        for (var edge : List.of(first, side, later, sideLater)) {
            root.addInput(edge);
            producer.addParent(root);
        }
        var builder = CraftingGraphDisplaySnapshot.builder(graph);
        builder.recordOutput(producer, primary, 10);
        builder.recordOutput(producer, secondary, 4);
        builder.recordEdge(first, 3, 3);
        builder.recordEdge(side, 2, 2);
        builder.recordEdge(later, 10, 9);
        builder.recordEdge(sideLater, 5, 3);
        builder.setRequestedAmount(1);
        graph.resetForReuse();
        var next = CraftingGraphDisplaySnapshot.builder(graph);
        next.recordOutput(producer, primary, Long.MAX_VALUE);
        next.recordOutput(producer, primary, 7);
        next.recordEdge(first, 3, 3);
        next.setRequestedAmount(1);
        var edges = builder.build().nodes().getFirst().inputs();
        assertEquals(List.of(3L, 2L, 7L, 2L), edges.stream().map(CraftingGraphDisplaySnapshot.Edge::allocatedOutput).toList());
        assertEquals(List.of(0L, 0L, 2L, 1L), edges.stream().map(CraftingGraphDisplaySnapshot.Edge::extractedExternal).toList());
        assertEquals(List.of(0L, 0L, 1L, 2L), edges.stream().map(CraftingGraphDisplaySnapshot.Edge::missing).toList());
        assertEquals(3, next.build().nodes().getFirst().inputs().getFirst().allocatedOutput());
        assertEquals(0, next.build().nodes().getFirst().inputs().get(1).allocatedOutput());
    }

    @Test
    void laterCycleDoesNotChangeEarlierOrdinaryEdge() {
        var root = new CraftingGraphNode(AEItemKey.of(Items.APPLE), null, List.of(), 1);
        var graph = new CraftingGraph();
        graph.putNode(new CraftingGraph.NodeKey(root.getWhat(), null), root);
        graph.setRootNode(root);
        var edge = new CraftingGraphEdge(AEItemKey.of(Items.STICK), 1, null, false);
        root.addInput(edge);
        root.setDemandAmount(1);
        var builder = CraftingGraphDisplaySnapshot.builder(graph);
        builder.setRequestedAmount(1);
        graph.resetForReuse();
        edge.markCycleCut(7);
        edge.recordSeedResult(2, 5);
        edge.setReserveDemand(4);
        var captured = builder.build().nodes().getFirst().inputs().getFirst();
        assertFalse(captured.cycleCut());
        assertEquals(0, captured.seedRequired());
        assertEquals(0, captured.seedAllocated());
        assertEquals(0, captured.seedMissing());
        assertEquals(0, captured.reserve());
    }
}
