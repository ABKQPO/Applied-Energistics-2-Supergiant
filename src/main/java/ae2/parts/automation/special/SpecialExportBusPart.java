package ae2.parts.automation.special;

import ae2.api.networking.IGrid;
import ae2.api.parts.IPartItem;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.parts.automation.ExportBusPart;
import ae2.util.prioritylist.IPartitionList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;

abstract class SpecialExportBusPart extends ExportBusPart {

    private IPartitionList filter;

    SpecialExportBusPart(IPartItem<?> partItem) {
        super(partItem);
    }

    @Override
    public void readFromNBT(NBTTagCompound extra) {
        super.readFromNBT(extra);
        this.filter = null;
    }

    protected void invalidateSpecialFilter() {
        this.filter = null;
        this.getHost().markForSave();
        this.getMainNode().ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    protected IPartitionList getSpecialFilter() {
        if (this.filter == null) {
            this.filter = createSpecialFilter();
        }
        return this.filter;
    }

    protected abstract IPartitionList createSpecialFilter();

    static ObjectArrayList<AEKey> snapshotKeys(KeyCounter inventory) {
        // Transfers can change the live counter. Snapshot keys in traversal order; its fast entries are reused.
        var keys = new ObjectArrayList<AEKey>(inventory.size());
        inventory.forEach((key, amount) -> keys.add(key));
        return keys;
    }

    @Override
    protected boolean doBusWork(IGrid grid) {
        var storageService = grid.getStorageService();
        var context = createTransferContext(storageService, grid.getEnergyService());
        var filter = getSpecialFilter();

        for (AEKey what : snapshotKeys(storageService.getCachedInventory())) {
            if (!filter.isListed(what)) {
                continue;
            }
            long amount = (long) context.getOperationsRemaining() * what.getAmountPerOperation();
            amount = getExportStrategy().transfer(context, what, amount);
            if (amount > 0) {
                context.reduceOperationsRemaining(Math.max(1, amount / what.getAmountPerOperation()));
            }
            if (!context.hasOperationsLeft()) {
                break;
            }
        }

        return context.hasDoneWork();
    }
}
