package ae2.crafting;

import ae2.api.crafting.IPatternDetails;
import ae2.api.stacks.AEKey;
import ae2.core.AELog;
import ae2.core.localization.PlayerMessages;
import ae2.crafting.execution.CraftingSupplierLocation;
import ae2.crafting.execution.CraftingSupplierLocator;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The crafting cycle a request ran into that makes no more of what it was asked for than it takes, as it is told to
 * the player: the items around it, and each pattern with where a machine holding it is. A branch failing this way is
 * often tried and given up during a calculation, so where the machines are is only looked up for the one reported.
 *
 * @param locations per pattern, where it is, once looked up
 */
public record NoNetOutputCycle(List<AEKey> items, List<IPatternDetails> patterns, List<Location> locations) {
    private static final int MAX_LISTED = 8;

    /**
     * @param machine  the name of a machine holding the pattern, if any holds it
     * @param location where that machine is
     */
    public record Location(@Nullable ITextComponent machine, @Nullable CraftingSupplierLocation location) {
    }

    public static NoNetOutputCycle of(List<AEKey> items, List<IPatternDetails> patterns) {
        return new NoNetOutputCycle(items, patterns, List.of());
    }

    NoNetOutputCycle locate(Function<IPatternDetails, CraftingTreeProcess.MachineInfo> machines) {
        var located = new ArrayList<Location>(this.patterns.size());
        for (var pattern : this.patterns) {
            ITextComponent machine = null;
            CraftingSupplierLocation location = null;
            try {
                var info = machines.apply(pattern);
                for (var group : info.groups()) {
                    var groupLocations = info.locations().get(group);
                    if (groupLocations != null && !groupLocations.isEmpty()) {
                        machine = group.name();
                        location = groupLocations.getFirst();
                        break;
                    }
                }
            } catch (RuntimeException lookupFailed) {
                // Reported as held by no machine rather than losing the report
                AELog.debug("Could not locate the machines of %s: %s", pattern, lookupFailed);
            }
            located.add(new Location(machine, location));
        }
        return new NoNetOutputCycle(this.items, this.patterns, located);
    }

    public List<ITextComponent> describe() {
        var lines = new ArrayList<ITextComponent>();
        var chain = new TextComponentString("");
        for (int i = 0; i < this.items.size() && i <= MAX_LISTED; i++) {
            if (i > 0) {
                var arrow = new TextComponentString(" → ");
                arrow.getStyle().setColor(TextFormatting.GRAY);
                chain.appendSibling(arrow);
            }
            chain.appendSibling(i == MAX_LISTED ? new TextComponentString("…")
                : this.items.get(i).getDisplayName());
        }
        lines.add(PlayerMessages.CraftingNoNetOutputCycle.text(chain));
        for (int i = 0; i < this.patterns.size() && i < MAX_LISTED; i++) {
            var output = this.patterns.get(i).getPrimaryOutput().what().getDisplayName();
            var where = i < this.locations.size() ? this.locations.get(i) : null;
            var location = where == null ? null : where.location();
            if (where == null || where.machine() == null || location == null) {
                lines.add(PlayerMessages.CraftingNoNetOutputPatternUnplaced.text(output));
            } else {
                lines.add(PlayerMessages.CraftingNoNetOutputPattern.text(output, where.machine(),
                    location.x(), location.y(), location.z(),
                    CraftingSupplierLocator.getDimensionName(location.dimensionId())));
            }
        }
        if (this.patterns.size() > MAX_LISTED) {
            lines.add(new TextComponentString("  …"));
        }
        return lines;
    }
}
