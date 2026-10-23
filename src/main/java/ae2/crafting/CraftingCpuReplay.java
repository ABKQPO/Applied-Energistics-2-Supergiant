package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import com.google.common.math.LongMath;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Replays a plan the way a crafting CPU runs it. The CPU starts with the used, emitted and missing items of the plan
 * and visits its tasks in turn, in the order of {@link ae2.crafting.execution.ExecutingCraftingJob#tasks}, pushing one
 * craft per visit whose inputs it holds, see {@link ae2.crafting.execution.CraftingCpuLogic}. Like
 * {@link ae2.crafting.execution.CraftingCpuHelper#extractPatternInputs}, an input takes its possible stacks, and the keys
 * of their fuzzy groups the input accepts, in order until it has enough.
 */
final class CraftingCpuReplay {
    private static final int MAX_PASSES = 1_000_000;

    private final World level;
    private final Object2LongOpenHashMap<AEKey> inventory = new Object2LongOpenHashMap<>();
    // The same kind of map filled in the same order as the tasks of the executing job, so it is visited in their order
    private final Object2ObjectOpenHashMap<IPatternDetails, long[]> left = new Object2ObjectOpenHashMap<>();
    private final Object2LongOpenHashMap<AEKey> passDelta = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<AEKey> passTaken = new Object2LongOpenHashMap<>();
    // Every key the replay can hold, by fuzzy group, and the candidates of each input among them
    private final Object2ObjectOpenHashMap<Object, ObjectArrayList<AEKey>> keysByGroup = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<IPatternDetails.IInput, List<GenericStack>> candidates =
        new Reference2ObjectOpenHashMap<>();

    private CraftingCpuReplay(World level, ICraftingPlan plan, KeyCounter extra) {
        this.level = level;
        for (var counter : List.of(plan.usedItems(), plan.emittedItems(), plan.missingItems(), extra)) {
            for (var entry : counter) {
                this.inventory.addTo(entry.getKey(), entry.getLongValue());
            }
        }
        for (var entry : plan.patternTimes().object2LongEntrySet()) {
            this.left.computeIfAbsent(entry.getKey(), ignored -> new long[1])[0] += entry.getLongValue();
        }
        for (var key : this.inventory.keySet()) {
            index(key);
        }
        for (var pattern : this.left.keySet()) {
            for (var output : pattern.getOutputs()) {
                index(output.what());
            }
            for (var input : pattern.getInputs()) {
                for (var possible : input.possibleInputs()) {
                    index(possible.what());
                    var container = input.getRemainingKey(possible.what());
                    if (container != null) {
                        index(container);
                    }
                }
            }
        }
    }

    /**
     * @return whether the CPU runs every craft of the plan and ends up with the requested output
     */
    static boolean runs(ICraftingPlan plan, World level) {
        var replay = new CraftingCpuReplay(level, plan, new KeyCounter());
        if (replay.orderFree()) {
            return replay.balances(plan);
        }
        return replay.run() && replay.coversOutput(plan);
    }

    /**
     * The items the CPU would have to be given on top of the plan to run it, found by running the plan and, whenever
     * no craft can run, adding everything the stuck crafts lack. Null when the replay gives up.
     */
    static @Nullable KeyCounter lacking(ICraftingPlan plan, World level) {
        var extra = new KeyCounter();
        for (int attempt = 0; attempt < 1024; attempt++) {
            var replay = new CraftingCpuReplay(level, plan, extra);
            if (replay.run()) {
                var output = plan.finalOutput();
                long shortOfOutput = output.amount() - replay.inventory.getLong(output.what());
                if (shortOfOutput <= 0) {
                    return extra;
                }
                // Crafts may take some of the output given to them as well, so check again
                extra.add(output.what(), shortOfOutput);
                continue;
            }
            var shortages = replay.shortages();
            if (shortages.isEmpty()) {
                return null;
            }
            extra.addAll(shortages);
        }
        return null;
    }

    private void index(AEKey key) {
        var group = this.keysByGroup.computeIfAbsent(key.getPrimaryKey(), g -> new ObjectArrayList<>());
        if (!group.contains(key)) {
            group.add(key);
        }
    }

    private boolean coversOutput(ICraftingPlan plan) {
        var output = plan.finalOutput();
        return this.inventory.getLong(output.what()) >= output.amount();
    }

    /*
     * Without a cycle among the crafts, inputs with a choice of stacks, or containers, no craft can keep another from
     * ever running: every key is made by crafts that do not wait for its consumers, and what one consumer takes is not
     * what another needs instead. Whether the plan runs then only depends on its totals.
     */
    private boolean orderFree() {
        var producers = new Object2ObjectOpenHashMap<AEKey, ObjectArrayList<IPatternDetails>>();
        for (var pattern : this.left.keySet()) {
            for (var input : pattern.getInputs()) {
                var possible = input.possibleInputs();
                if (possible.length > 1 || candidates(input).size() > 1) return false;
                if (possible.length == 1 && input.getRemainingKey(possible[0].what()) != null) return false;
            }
            for (var output : pattern.getOutputs()) {
                producers.computeIfAbsent(output.what(), k -> new ObjectArrayList<>()).add(pattern);
            }
        }
        var done = new ReferenceOpenHashSet<IPatternDetails>();
        var onPath = new ReferenceOpenHashSet<IPatternDetails>();
        for (var pattern : this.left.keySet()) {
            if (hasCycle(pattern, producers, done, onPath)) return false;
        }
        return true;
    }

    // Depth-first search from a craft to the producers of its inputs
    private static boolean hasCycle(IPatternDetails pattern,
                                    Object2ObjectOpenHashMap<AEKey, ObjectArrayList<IPatternDetails>> producers,
                                    ReferenceOpenHashSet<IPatternDetails> done,
                                    ReferenceOpenHashSet<IPatternDetails> onPath) {
        if (done.contains(pattern)) return false;
        if (!onPath.add(pattern)) return true;
        for (var input : pattern.getInputs()) {
            var possible = input.possibleInputs();
            if (possible.length == 0) continue;
            var makers = producers.get(possible[0].what());
            if (makers == null) continue;
            for (var maker : makers) {
                if (hasCycle(maker, producers, done, onPath)) return true;
            }
        }
        onPath.remove(pattern);
        done.add(pattern);
        return false;
    }

    private boolean balances(ICraftingPlan plan) {
        var balance = new Object2LongOpenHashMap<AEKey>(this.inventory);
        for (var entry : this.left.entrySet()) {
            long times = entry.getValue()[0];
            var pattern = entry.getKey();
            for (var input : pattern.getInputs()) {
                var possible = input.possibleInputs();
                if (possible.length == 0) continue;
                balance.addTo(possible[0].what(), -LongMath.saturatedMultiply(
                    LongMath.saturatedMultiply(possible[0].amount(), input.getMultiplier()), times));
            }
            for (var output : pattern.getOutputs()) {
                balance.addTo(output.what(), LongMath.saturatedMultiply(output.amount(), times));
            }
        }
        for (var value : balance.values()) {
            if (value < 0) return false;
        }
        var output = plan.finalOutput();
        return balance.getLong(output.what()) >= output.amount();
    }

    /*
     * Each pass visits every task once and pushes one craft of it if its inputs are there. A pass the CPU just ran the
     * same way twice runs the same way again as long as what it takes stays there and no idle task gets what it waits
     * for, so it is repeated at once that often: a catalyst or container passed between crafts costs a few passes, not
     * one per craft.
     */
    private boolean run() {
        var crafted = new ObjectArrayList<IPatternDetails>();
        var previousCrafted = new ObjectArrayList<IPatternDetails>();
        var previousDelta = new Object2LongOpenHashMap<AEKey>();
        var idle = new ObjectArrayList<IPatternDetails>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            crafted.clear();
            idle.clear();
            this.passDelta.clear();
            this.passTaken.clear();
            boolean exact = true;
            for (var entry : this.left.entrySet()) {
                var times = entry.getValue();
                if (times[0] <= 0) continue;
                var pattern = entry.getKey();
                var taken = takeOnce(pattern);
                if (taken == null) {
                    idle.add(pattern);
                    continue;
                }
                apply(pattern, taken);
                times[0]--;
                crafted.add(pattern);
                exact &= taken.exact;
            }
            if (crafted.isEmpty()) {
                return idle.isEmpty();
            }
            if (exact && crafted.equals(previousCrafted) && this.passDelta.equals(previousDelta)) {
                repeatPass(crafted, idle);
            }
            previousCrafted.clear();
            previousCrafted.addAll(crafted);
            previousDelta.clear();
            previousDelta.putAll(this.passDelta);
        }
        return false;
    }

    private void repeatPass(List<IPatternDetails> crafted, List<IPatternDetails> idle) {
        // A task visited before the crafts that made its inputs waits for the next pass, which then runs differently
        for (var pattern : idle) {
            if (takeOnce(pattern) != null) {
                return;
            }
        }
        long repeats = Long.MAX_VALUE;
        for (var pattern : crafted) {
            repeats = Math.min(repeats, this.left.get(pattern)[0]);
        }
        for (var entry : this.passDelta.object2LongEntrySet()) {
            long delta = entry.getLongValue();
            if (delta >= 0) continue;
            // Every repeat starts with at least what the pass took, so each of its crafts finds its inputs
            long spare = this.inventory.getLong(entry.getKey()) - this.passTaken.getLong(entry.getKey());
            repeats = Math.min(repeats, spare < 0 ? 0 : spare / -delta);
        }
        // An idle task must stay idle: stop before any of its short inputs could be covered
        for (var pattern : idle) {
            for (var input : pattern.getInputs()) {
                var possible = input.possibleInputs();
                if (possible.length == 0) continue;
                long held = 0;
                long gained = 0;
                for (var candidate : candidates(input)) {
                    held = LongMath.saturatedAdd(held, Math.max(0, this.inventory.getLong(candidate.what())));
                    gained = LongMath.saturatedAdd(gained, Math.max(0, this.passDelta.getLong(candidate.what())));
                }
                long need = LongMath.saturatedMultiply(possible[0].amount(), input.getMultiplier());
                if (gained > 0 && held < need) {
                    repeats = Math.min(repeats, (need - 1 - held) / gained);
                }
            }
        }
        if (repeats <= 0 || repeats == Long.MAX_VALUE) {
            return;
        }
        for (var pattern : crafted) {
            this.left.get(pattern)[0] -= repeats;
        }
        for (var entry : this.passDelta.object2LongEntrySet()) {
            this.inventory.addTo(entry.getKey(), LongMath.saturatedMultiply(entry.getLongValue(), repeats));
        }
    }

    private void apply(IPatternDetails pattern, Taken taken) {
        for (var entry : taken.keys.object2LongEntrySet()) {
            this.inventory.addTo(entry.getKey(), -entry.getLongValue());
            this.passDelta.addTo(entry.getKey(), -entry.getLongValue());
            this.passTaken.addTo(entry.getKey(), entry.getLongValue());
        }
        for (var entry : taken.containers.object2LongEntrySet()) {
            this.inventory.addTo(entry.getKey(), entry.getLongValue());
            this.passDelta.addTo(entry.getKey(), entry.getLongValue());
        }
        for (var output : pattern.getOutputs()) {
            this.inventory.addTo(output.what(), output.amount());
            this.passDelta.addTo(output.what(), output.amount());
        }
    }

    /**
     * What one craft takes. Exact when every input took its first possible stack alone, so the next craft takes the
     * same as long as that stack lasts; otherwise an input that moved on to a later stack may come back to an earlier
     * one as soon as a craft makes it.
     */
    private record Taken(Object2LongOpenHashMap<AEKey> keys, Object2LongOpenHashMap<AEKey> containers,
                         boolean exact) {
    }

    // What one craft takes from the current inventory, or null when it cannot run
    private @Nullable Taken takeOnce(IPatternDetails pattern) {
        var keys = new Object2LongOpenHashMap<AEKey>();
        var containers = new Object2LongOpenHashMap<AEKey>();
        boolean exact = true;
        for (var input : pattern.getInputs()) {
            if (input.possibleInputs().length == 0) continue;
            // Counted in templates, each one stack of its candidate, see CraftingCpuHelper#extractPatternInputs
            long need = input.getMultiplier();
            var first = input.possibleInputs()[0].what();
            for (var candidate : candidates(input)) {
                long per = candidate.amount();
                long available = this.inventory.getLong(candidate.what()) - keys.getLong(candidate.what());
                long templates = Math.min(Math.max(0, available) / per, need);
                if (templates <= 0) continue;
                if (candidate.what() != first || templates < need) {
                    exact = false;
                }
                keys.addTo(candidate.what(), templates * per);
                var container = input.getRemainingKey(candidate.what());
                if (container != null) {
                    containers.addTo(container, templates);
                }
                need -= templates;
                if (need <= 0) break;
            }
            if (need > 0) {
                return null;
            }
        }
        return new Taken(keys, containers, exact);
    }

    // The possible stacks of an input in order, each followed by the other keys of its fuzzy group it accepts
    private List<GenericStack> candidates(IPatternDetails.IInput input) {
        var result = this.candidates.get(input);
        if (result == null) {
            var list = new ObjectArrayList<GenericStack>();
            for (var possible : input.possibleInputs()) {
                list.add(possible);
                var group = this.keysByGroup.get(possible.what().getPrimaryKey());
                if (group == null) continue;
                for (var key : group) {
                    if (key != possible.what() && input.isValid(key, this.level)) {
                        list.add(new GenericStack(key, possible.amount()));
                    }
                }
            }
            result = list;
            this.candidates.put(input, result);
        }
        return result;
    }

    // What each planned craft that cannot run lacks for one craft, on the first possible stack of each short input
    private KeyCounter shortages() {
        var result = new KeyCounter();
        for (var entry : this.left.entrySet()) {
            if (entry.getValue()[0] <= 0) continue;
            var reserved = new Object2LongOpenHashMap<AEKey>();
            for (var input : entry.getKey().getInputs()) {
                var possible = input.possibleInputs();
                if (possible.length == 0) continue;
                long need = input.getMultiplier();
                for (var candidate : candidates(input)) {
                    long available = this.inventory.getLong(candidate.what()) - reserved.getLong(candidate.what());
                    long templates = Math.min(Math.max(0, available) / candidate.amount(), need);
                    if (templates > 0) {
                        reserved.addTo(candidate.what(), templates * candidate.amount());
                        need -= templates;
                    }
                    if (need <= 0) break;
                }
                if (need > 0) {
                    result.add(possible[0].what(), LongMath.saturatedMultiply(need, possible[0].amount()));
                }
            }
        }
        return result;
    }
}
