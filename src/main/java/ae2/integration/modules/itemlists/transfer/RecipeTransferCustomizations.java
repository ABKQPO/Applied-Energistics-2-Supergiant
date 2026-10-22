/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2026, TeamAppliedEnergistics, All rights reserved.
 */

package ae2.integration.modules.itemlists.transfer;

import ae2.api.integrations.recipetransfer.IRecipeTransferCustomization;
import ae2.api.integrations.recipetransfer.RecipeTransferCustomizationRegistry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * Server-side registry for optional target customizations. Standard targets need no registration.
 */
public final class RecipeTransferCustomizations {
    private RecipeTransferCustomizations() {
    }

    public static <C extends Container> void register(Class<C> containerClass,
                                                      BiFunction<? super C, EntityPlayerMP,
                                                          ? extends IRecipeTransferCustomization> factory) {
        RecipeTransferCustomizationRegistry.register(containerClass, factory);
    }

    @Nullable
    public static IRecipeTransferCustomization find(Container container, EntityPlayerMP player) {
        return RecipeTransferCustomizationRegistry.find(container, player);
    }
}
