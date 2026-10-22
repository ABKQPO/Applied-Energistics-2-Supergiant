/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.implementations.items.WirelessTerminalApi;
import ae2.api.integrations.recipetransfer.RecipeTransferSource;
import ae2.api.networking.security.IActionSource;
import ae2.util.inv.PlayerInternalInventory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayerMP;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Factory for the default local-inventory plus optional wireless-network source order.
 */
public final class RecipeTransferSources {
    private RecipeTransferSources() {
    }

    /**
     * Builds the default source order. The caller supplies the allowed local-slot predicate so locked slots and the
     * item holding the wireless connection can be excluded by the current container rules.
     */
    public static RecipeTransferSource forPlayer(EntityPlayerMP player,
                                                 @Nullable WirelessTerminalApi.TerminalContext wireless,
                                                 IntPredicate allowedLocalSlot) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(allowedLocalSlot, "allowedLocalSlot");

        var sources = new ObjectArrayList<RecipeTransferSource>();
        sources.add(new InternalInventoryRecipeTransferSource(
            new PlayerInternalInventory(player.inventory), allowedLocalSlot));
        if (wireless != null && wireless.node() != null && wireless.node().isActive()
            && wireless.host().getLinkStatus().connected()) {
            var storage = wireless.node().grid().getStorageService();
            sources.add(new NetworkRecipeTransferSource(
                wireless.energySource(), wireless.host().getInventory(),
                IActionSource.ofPlayer(player, wireless.actionHost()), storage.getCachedInventory()));
        }
        return new CompositeRecipeTransferSource(sources);
    }

}
