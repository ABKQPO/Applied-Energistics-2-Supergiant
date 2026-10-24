package ae2.crafting.graph;

import ae2.api.crafting.IPatternDetails;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.crafting.execution.CraftingSupplierLocation;
import com.google.common.math.LongMath;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * Immutable graph execution decisions with deterministic display ownership for produced output.
 * Callers transfer ownership of the supplied collections and must not mutate their backing storage afterwards.
 */
public record CraftingGraphDisplaySnapshot(AEKey rootWhat, long requestedAmount, List<Node> nodes) {
    public CraftingGraphDisplaySnapshot {
        nodes = Collections.unmodifiableList(nodes);
    }

    static Map<PatternContainerGroup, List<CraftingSupplierLocation>> filterLocations(
        List<PatternContainerGroup> machines, Map<PatternContainerGroup, List<CraftingSupplierLocation>> locations) {
        if (machines.isEmpty() || locations.isEmpty()) return Map.of();
        if (locationsMatch(machines, locations)) return Collections.unmodifiableMap(locations);
        Object2ObjectLinkedOpenHashMap<PatternContainerGroup, List<CraftingSupplierLocation>> filtered = null;
        var iterator = machines instanceof RandomAccess ? null : machines.iterator();
        for (int i = 0; i < machines.size(); i++) {
            var machine = iterator == null ? machines.get(i) : iterator.next();
            var values = locations.get(machine);
            if (values != null && !values.isEmpty()) {
                if (filtered == null) filtered = new Object2ObjectLinkedOpenHashMap<>(
                    Math.min(machines.size(), locations.size()));
                filtered.put(machine, values);
            }
        }
        return filtered == null ? Map.of() : Collections.unmodifiableMap(filtered);
    }

    public static Builder builder(CraftingGraph graph) {
        return new Builder(graph);
    }

    private static boolean locationsMatch(List<PatternContainerGroup> machines,
                                          Map<PatternContainerGroup, List<CraftingSupplierLocation>> locations) {
        if (machines.size() != locations.size()) return false;
        var keys = locations.keySet().iterator();
        var iterator = machines instanceof RandomAccess ? null : machines.iterator();
        for (int i = 0; i < machines.size(); i++) {
            var machine = iterator == null ? machines.get(i) : iterator.next();
            var values = locations.get(machine);
            if (!machine.equals(keys.next()) || values == null || values.isEmpty()) return false;
        }
        return true;
    }

    public record Edge(AEKey inputKey, long amountPerCraft, int producerIndex,
                       boolean cycleCut, long needed, long allocatedOutput,
                       long extractedExternal, long missing, long seedRequired,
                       long seedAllocated, long seedMissing, long reserve) {
        @SuppressWarnings("unused")
        public Edge(AEKey inputKey, long amountPerCraft, int producerIndex,
                    boolean cycleCut, long needed, long allocatedOutput,
                    long extractedExternal, long missing) {
            this(inputKey, amountPerCraft, producerIndex, cycleCut, needed, allocatedOutput,
                extractedExternal, missing, 0, 0, 0, 0);
        }
    }

    public record Node(AEKey what, @Nullable IPatternDetails pattern, long outputPerCraft,
                       long demandAmount, long craftTimes, boolean executed, List<Edge> inputs,
                       List<PatternContainerGroup> machines,
                       Map<PatternContainerGroup, List<CraftingSupplierLocation>> machineLocations,
                       @Nullable LocalDisplayFragment localDisplay, long external, long missing,
                       int componentId, int componentOrder) {
        public Node {
            inputs = Collections.unmodifiableList(inputs);
            machineLocations = filterLocations(machines, machineLocations);
            machines = Collections.unmodifiableList(machines);
        }

        public Node(AEKey what, @Nullable IPatternDetails pattern, long outputPerCraft,
                    long demandAmount, long craftTimes, boolean executed, List<Edge> inputs) {
            this(what, pattern, outputPerCraft, demandAmount, craftTimes, executed, inputs, List.of(), Map.of(), null,
                0, 0, -1, -1);
        }
    }

    public static final class Builder {
        // Graph reuse only changes planning amounts. Its nodes, edges and topology remain structurally immutable.
        private final CraftingGraphNode root;
        private final List<CraftingGraphNode> allNodes;
        private final CraftingGraphTopology topology;
        private final boolean[] executed;
        private @Nullable Reference2ObjectMap<CraftingGraphNode, AEKey2LongMap> remainingOutput;
        private long @Nullable [] remainingPrimaryOutput;
        private @Nullable Reference2ObjectMap<CraftingGraphNode, LocalDisplayFragment> localDisplays;
        private @Nullable Reference2LongMap<CraftingGraphNode> nodeMissing;
        private @Nullable Reference2LongMap<CraftingGraphNode> nodeExternal;
        // Indexed by the graph's stable edge ordinal; zero is also the result for an unexecuted edge.
        private long @Nullable [] edgeResults;
        private @Nullable Reference2ObjectMap<CraftingGraphEdge, EdgePlanning> edgePlanning;
        private long[] planningAmounts;
        private long buildRequestedAmount;

        private Builder(CraftingGraph graph) {
            var root = graph.getRootNode();
            if (root == null) {
                throw new IllegalStateException("Graph has no root node");
            }
            this.root = root;
            this.allNodes = graph.getAllNodes();
            this.topology = graph.getTopology();
            this.executed = new boolean[allNodes.size()];
        }

        void recordNode(CraftingGraphNode node) {
            executed[node.getOrdinal()] = true;
        }

        /**
         * Seals the attempt before the graph is returned to the cache. Keep mutable amounts now; traversal and
         * display records remain lazy. Reading these amounts from the reused graph on first GUI open is too late.
         */
        public void setRequestedAmount(long requestedAmount) {
            if (requestedAmount <= 0 || planningAmounts != null) {
                throw new IllegalStateException("Graph display attempt must be sealed once with a positive amount");
            }
            this.planningAmounts = new long[allNodes.size() * 2];
            for (int i = 0; i < allNodes.size(); i++) {
                var node = allNodes.get(i);
                planningAmounts[i * 2] = node.getDemandAmount();
                planningAmounts[i * 2 + 1] = node.getCraftTimes();
                var inputs = node.getInputs();
                for (int j = 0; j < inputs.size(); j++) {
                    var edge = inputs.get(j);
                    if (edge.cycleCut() || edge.seedRequired() != 0 || edge.seedAllocated() != 0
                        || edge.seedMissing() != 0 || edge.reserveDemand() != 0) {
                        if (edgePlanning == null) edgePlanning = new Reference2ObjectOpenHashMap<>(4);
                        edgePlanning.put(edge, new EdgePlanning(edge.cycleCut(), edge.seedRequired(),
                            edge.seedAllocated(), edge.seedMissing(), edge.reserveDemand()));
                    }
                }
            }
            remainingOutput = null;
            remainingPrimaryOutput = null;
            this.buildRequestedAmount = requestedAmount;
        }

        void recordLocalDisplay(CraftingGraphNode node, LocalDisplayFragment display) {
            if (localDisplays == null) localDisplays = new Reference2ObjectOpenHashMap<>(1);
            localDisplays.put(node, display);
        }

        void recordEdge(CraftingGraphEdge edge, long needed, long extracted) {
            long allocated = edge.cycleCut() ? 0 : consume(edge.producer(), edge.inputKey(), extracted);
            recordEdgeResult(edge, needed, allocated, extracted - allocated, needed - extracted);
        }

        void recordInternalEdge(CraftingGraphEdge edge, long consumed) {
            consume(edge.producer(), edge.inputKey(), consumed);
            if (edge.cycleCut() && edge.seedMissing() > 0) {
                // Surface a component-local seed shortfall as a terminal leaf so it is claimed from the global
                // missing budget instead of disappearing from the display tree.
                recordEdgeResult(edge, 0, 0, 0, edge.seedMissing());
            } else {
                recordEdgeResult(edge, 0, 0, 0, 0);
            }
        }

        private void recordEdgeResult(CraftingGraphEdge edge, long needed, long allocated, long external, long missing) {
            if (edgeResults == null) {
                if (needed == 0 && allocated == 0 && external == 0 && missing == 0) return;
                edgeResults = new long[topology.edgeCount() * 4];
            }
            int offset = edge.ordinal() * 4;
            edgeResults[offset] = needed;
            edgeResults[offset + 1] = allocated;
            edgeResults[offset + 2] = external;
            edgeResults[offset + 3] = missing;
        }

        void recordOutput(CraftingGraphNode node, AEKey key, long amount) {
            if (amount > 0) {
                if (key.equals(node.getWhat())) {
                    if (remainingPrimaryOutput == null) remainingPrimaryOutput = new long[allNodes.size()];
                    int index = node.getOrdinal();
                    remainingPrimaryOutput[index] = LongMath.saturatedAdd(remainingPrimaryOutput[index], amount);
                    return;
                }
                if (remainingOutput == null) remainingOutput = new Reference2ObjectOpenHashMap<>(4);
                var outputs = remainingOutput.computeIfAbsent(node,
                    ignored -> new AEKey2LongMap.OpenHashMap(Math.max(1, node.getOutputs().size())));
                long current = outputs.getLong(key);
                if (current >= Long.MAX_VALUE - amount) {
                    outputs.put(key, Long.MAX_VALUE);
                } else {
                    outputs.addTo(key, amount);
                }
            }
        }

        void recordMissing(CraftingGraphNode node, long amount) {
            if (amount > 0) {
                if (nodeMissing == null) nodeMissing = new Reference2LongOpenHashMap<>(1);
                nodeMissing.put(node, amount);
            }
        }

        void recordExternal(CraftingGraphNode node, long amount) {
            if (amount > 0) {
                if (nodeExternal == null) nodeExternal = new Reference2LongOpenHashMap<>(1);
                nodeExternal.put(node, amount);
            }
        }

        public CraftingGraphDisplaySnapshot build() {
            long requestedAmount = this.buildRequestedAmount;
            if (requestedAmount <= 0) {
                throw new IllegalStateException("Graph display snapshot was built without a requested amount");
            }
            return build(requestedAmount);
        }

        private CraftingGraphDisplaySnapshot build(long requestedAmount) {
            var indexes = new int[allNodes.size()];
            Arrays.fill(indexes, -1);
            var orderedNodes = collect(indexes);
            var componentOrder = topology.isCondensed() ? new int[allNodes.size()] : null;
            var components = topology.getComponents();
            if (componentOrder != null) {
                for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
                    var component = components.get(componentIndex);
                    var nodes = component.nodes();
                    for (int i = 0, size = nodes.size(); i < size; i++) {
                        componentOrder[nodes.get(i).getOrdinal()] = i;
                    }
                }
            }
            var snapshots = new ObjectArrayList<Node>(orderedNodes.size());
            for (int nodeIndex = 0; nodeIndex < orderedNodes.size(); nodeIndex++) {
                var node = orderedNodes.get(nodeIndex);
                var inputs = node.getInputs();
                var edges = new ObjectArrayList<Edge>(inputs.size());
                for (int edgeIndex = 0; edgeIndex < inputs.size(); edgeIndex++) {
                    var edge = inputs.get(edgeIndex);
                    int offset = edge.ordinal() * 4;
                    var producer = edge.producer();
                    var planning = edgePlanning == null ? null : edgePlanning.get(edge);
                    edges.add(new Edge(edge.inputKey(), edge.amountPerCraft(),
                        producer == null ? -1 : indexes[producer.getOrdinal()],
                        planning != null && planning.cycleCut,
                        edgeResults == null ? 0 : edgeResults[offset],
                        edgeResults == null ? 0 : edgeResults[offset + 1],
                        edgeResults == null ? 0 : edgeResults[offset + 2],
                        edgeResults == null ? 0 : edgeResults[offset + 3],
                        planning == null ? 0 : planning.seedRequired, planning == null ? 0 : planning.seedAllocated,
                        planning == null ? 0 : planning.seedMissing, planning == null ? 0 : planning.reserve));
                }
                int ordinal = node.getOrdinal();
                snapshots.add(new Node(node.getWhat(), node.getPattern(), node.getOutputPerCraft(),
                    planningAmounts[ordinal * 2], planningAmounts[ordinal * 2 + 1], executed[ordinal], edges,
                    List.of(), Map.of(),
                    localDisplays == null ? null : localDisplays.get(node),
                    nodeExternal == null ? 0 : nodeExternal.getLong(node), nodeMissing == null ? 0 : nodeMissing.getLong(node),
                    topology.getComponentId(node), componentOrder == null ? -1 : componentOrder[ordinal]));
            }
            return new CraftingGraphDisplaySnapshot(orderedNodes.getFirst().getWhat(), requestedAmount, snapshots);
        }

        private long consume(@Nullable CraftingGraphNode producer, AEKey key, long amount) {
            if (producer == null || amount <= 0) return 0;
            if (key.equals(producer.getWhat())) {
                if (remainingPrimaryOutput == null) return 0;
                int index = producer.getOrdinal();
                long allocated = Math.min(remainingPrimaryOutput[index], amount);
                remainingPrimaryOutput[index] -= allocated;
                return allocated;
            }
            if (remainingOutput == null) return 0;
            var outputs = remainingOutput.get(producer);
            if (outputs == null) return 0;
            long available = outputs.getLong(key);
            long allocated = Math.min(available, amount);
            if (allocated == available) {
                outputs.removeLong(key);
                if (outputs.isEmpty()) remainingOutput.remove(producer);
            } else {
                outputs.addTo(key, -allocated);
            }
            return allocated;
        }

        private ObjectArrayList<CraftingGraphNode> collect(int[] indexes) {
            var orderedNodes = new ObjectArrayList<CraftingGraphNode>(allNodes.size());
            var pending = new ObjectArrayList<CraftingGraphNode>(Math.min(16, allNodes.size()));
            pending.add(root);
            while (!pending.isEmpty()) {
                var node = pending.removeLast();
                int ordinal = node.getOrdinal();
                if (indexes[ordinal] >= 0) continue;
                indexes[ordinal] = orderedNodes.size();
                orderedNodes.add(node);
                var inputs = node.getInputs();
                for (int i = inputs.size() - 1; i >= 0; i--) {
                    var producer = inputs.get(i).producer();
                    if (producer != null && indexes[producer.getOrdinal()] < 0) pending.add(producer);
                }
            }
            return orderedNodes;
        }

        private record EdgePlanning(boolean cycleCut, long seedRequired, long seedAllocated, long seedMissing,
                                    long reserve) {
        }

    }
}
